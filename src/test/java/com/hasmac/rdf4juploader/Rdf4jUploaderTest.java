package com.hasmac.rdf4juploader;

import org.eclipse.rdf4j.common.transaction.IsolationLevel;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rdf4jUploaderTest {

    @Test
    void parseArgumentsDefaultsIsolationLevelToReadCommitted() {
        Rdf4jUploader.UploadConfig config = Rdf4jUploader.parseArguments(requiredArgs());

        assertSame(IsolationLevels.READ_COMMITTED, config.isolationLevel());
    }

    @Test
    void parseArgumentsSetsDefaultProgressFileEvenWithoutResume() {
        Rdf4jUploader.UploadConfig config = Rdf4jUploader.parseArguments(requiredArgs());

        assertFalse(config.resume());
        assertEquals(Path.of("/tmp/data", UploadProgress.DEFAULT_FILE_NAME), config.progressFile());
    }

    @Test
    void parseArgumentsEnablesResumeWithDefaultProgressFile() {
        Rdf4jUploader.UploadConfig config = Rdf4jUploader.parseArguments(requiredArgs("--resume"));

        assertTrue(config.resume());
        assertEquals(Path.of("/tmp/data", UploadProgress.DEFAULT_FILE_NAME), config.progressFile());
    }

    @Test
    void parseArgumentsResumeFileImpliesResume() {
        Rdf4jUploader.UploadConfig config = Rdf4jUploader.parseArguments(requiredArgs("--progress-file", "/tmp/progress.properties"));

        assertTrue(config.resume());
        assertEquals(Path.of("/tmp/progress.properties"), config.progressFile());
    }

    @Test
    void resumeCommandIncludesResumeAndProgressFile() {
        Rdf4jUploader.UploadConfig config = Rdf4jUploader.parseArguments(requiredArgs(
                "--threads", "8",
                "--base-uri", "http://example.org/",
                "--progress-file", "/tmp/progress.properties"));

        assertEquals("java -jar rdf4j-uploader-1.0.0.jar"
                        + " --endpoint http://localhost:8080/rdf4j-server"
                        + " --repository repo"
                        + " --folder /tmp/data"
                        + " --threads 8"
                        + " --base-uri http://example.org/"
                        + " --isolation-level READ_COMMITTED"
                        + " --resume"
                        + " --progress-file /tmp/progress.properties",
                Rdf4jUploader.resumeCommand(config));
    }

    @ParameterizedTest
    @CsvSource({
            "none, NONE",
            "read-uncommitted, READ_UNCOMMITTED",
            "read_committed, READ_COMMITTED",
            "SNAPSHOT_READ, SNAPSHOT_READ",
            "snapshot, SNAPSHOT",
            "serializable, SERIALIZABLE"
    })
    void parseArgumentsAcceptsSupportedIsolationLevels(String input, IsolationLevels expected) {
        Rdf4jUploader.UploadConfig config = Rdf4jUploader.parseArguments(requiredArgs("--isolation-level", input));

        assertSame(expected, config.isolationLevel());
    }

    @Test
    void parseArgumentsRejectsUnknownIsolationLevel() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                Rdf4jUploader.parseArguments(requiredArgs("--isolation-level", "dirty-read")));

        assertTrue(exception.getMessage().contains("dirty-read"));
        assertTrue(exception.getMessage().contains("READ_COMMITTED"));
    }

    @TempDir
    Path tempDir;

    @Test
    void addFileBeginsTransactionWithIsolationLevelAndCommits() throws Exception {
        List<String> calls = new ArrayList<>();
        AtomicReference<IsolationLevel> isolationLevel = new AtomicReference<>();
        RepositoryConnection connection = connectionRecordingTransaction(calls, isolationLevel);
        Path file = tempDir.resolve("data.ttl");
        Files.writeString(file, "<urn:s> <urn:p> <urn:o> .");

        Rdf4jUploader.addFile(connection, file, null, RDFFormat.TURTLE, IsolationLevels.SERIALIZABLE);

        assertEquals(List.of("begin", "add", "commit"), calls);
        assertSame(IsolationLevels.SERIALIZABLE, isolationLevel.get());
    }

    @Test
    void addFileDropsUnparsableLineDelimitedStatements() throws Exception {
        List<String> calls = new ArrayList<>();
        AtomicReference<IsolationLevel> isolationLevel = new AtomicReference<>();
        AtomicReference<Integer> statementCount = new AtomicReference<>(0);
        RepositoryConnection connection = connectionRecordingTransaction(calls, isolationLevel, statementCount);
        Path file = tempDir.resolve("data.nt");
        Files.writeString(file, """
                <urn:s1> <urn:p> <urn:o1> .
                this is not rdf
                <urn:s2> <urn:p> <urn:o2> .
                """);

        Rdf4jUploader.addFile(connection, file, null, RDFFormat.NTRIPLES, IsolationLevels.READ_COMMITTED);

        assertEquals(List.of("begin", "add", "add", "commit"), calls);
        assertEquals(2, statementCount.get());
    }

    @Test
    void uploadRetrierTriesTenTimesAndSleepsBetweenFailures() throws Exception {
        List<Duration> sleeps = new ArrayList<>();
        List<Integer> retries = new ArrayList<>();
        AtomicReference<Integer> attempts = new AtomicReference<>(0);
        UploadRetrier.RetryPolicy policy = new UploadRetrier.RetryPolicy(10, Duration.ofSeconds(10));

        UploadRetrier.run(() -> {
                    int attempt = attempts.get() + 1;
                    attempts.set(attempt);
                    if (attempt < 10) {
                        throw new IllegalStateException("temporary failure");
                    }
                },
                policy,
                sleeps::add,
                (failedAttempt, maxAttempts, delay, failure) -> retries.add(failedAttempt));

        assertEquals(10, attempts.get());
        assertEquals(9, sleeps.size());
        assertTrue(sleeps.stream().allMatch(Duration.ofSeconds(10)::equals));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), retries);
    }

    @Test
    void uploadRetrierFailsAfterTenAttempts() {
        AtomicReference<Integer> attempts = new AtomicReference<>(0);
        UploadRetrier.RetryPolicy policy = new UploadRetrier.RetryPolicy(10, Duration.ofSeconds(10));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                UploadRetrier.run(() -> {
                            attempts.set(attempts.get() + 1);
                            throw new IllegalStateException("still failing");
                        },
                        policy,
                        delay -> {
                        },
                        (failedAttempt, maxAttempts, delay, failure) -> {
                        }));

        assertEquals("still failing", exception.getMessage());
        assertEquals(10, attempts.get());
    }

    @Test
    void uploadProgressPersistsUploadedFilesForResume() throws Exception {
        Path root = tempDir.resolve("rdf");
        Files.createDirectories(root);
        Path uploaded = root.resolve("uploaded.ttl");
        Path failed = root.resolve("failed.ttl");
        Path progressFile = tempDir.resolve("progress.properties");

        UploadProgress progress = UploadProgress.load(root, progressFile);
        progress.markUploaded(uploaded);
        progress.markFailed(failed);

        UploadProgress resumed = UploadProgress.load(root, progressFile);

        assertTrue(resumed.isUploaded(uploaded));
        assertFalse(resumed.isUploaded(failed));
        assertEquals(UploadProgress.Status.FAILED, resumed.statusOf(failed));
    }

    @Test
    void uploadSchedulerStopsStartingNewFilesAfterFirstInterrupt() throws Exception {
        List<Path> files = List.of(
                Path.of("one.ttl"),
                Path.of("two.ttl"),
                Path.of("three.ttl"),
                Path.of("four.ttl"),
                Path.of("five.ttl"));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        CountDownLatch firstBatchStarted = new CountDownLatch(2);
        CountDownLatch releaseUploads = new CountDownLatch(1);
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        AtomicBoolean uploadInterrupted = new AtomicBoolean(false);
        List<Path> started = Collections.synchronizedList(new ArrayList<>());

        try {
            Future<UploadScheduler.Result> result = scheduler.submit(() ->
                    UploadScheduler.run(files, 2, workers, stopRequested::get, file -> {
                        started.add(file);
                        firstBatchStarted.countDown();
                        try {
                            releaseUploads.await();
                        } catch (InterruptedException e) {
                            uploadInterrupted.set(true);
                            Thread.currentThread().interrupt();
                        }
                    }));

            assertTrue(firstBatchStarted.await(5, TimeUnit.SECONDS));
            stopRequested.set(true);
            releaseUploads.countDown();

            assertEquals(2, result.get(5, TimeUnit.SECONDS).submittedCount());
            assertEquals(List.of(Path.of("one.ttl"), Path.of("two.ttl")), started);
            assertFalse(uploadInterrupted.get());
        } finally {
            scheduler.shutdownNow();
            workers.shutdownNow();
        }
    }

    @Test
    void uploadInterruptControllerInterruptsRunningUploadsOnlyAfterSecondInterrupt() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(1);
        UploadInterruptController controller = new UploadInterruptController(
                Rdf4jUploader.parseArguments(requiredArgs("--resume")),
                new PrintStream(OutputStream.nullOutputStream()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        try {
            controller.attach(workers);
            workers.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(started.await(5, TimeUnit.SECONDS));

            controller.handleInterrupt();

            assertTrue(controller.stopRequested());
            assertFalse(controller.forceStopRequested());
            assertFalse(interrupted.await(100, TimeUnit.MILLISECONDS));

            controller.handleInterrupt();

            assertTrue(controller.forceStopRequested());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void formatIsolationLevelShowsSelectedLevel() {
        assertEquals("READ_COMMITTED", Rdf4jUploader.formatIsolationLevel(IsolationLevels.READ_COMMITTED));
    }

    private static String[] requiredArgs(String... extraArgs) {
        String[] args = new String[6 + extraArgs.length];
        args[0] = "--endpoint";
        args[1] = "http://localhost:8080/rdf4j-server";
        args[2] = "--repository";
        args[3] = "repo";
        args[4] = "--folder";
        args[5] = "/tmp/data";
        System.arraycopy(extraArgs, 0, args, 6, extraArgs.length);
        return args;
    }

    private static RepositoryConnection connectionRecordingTransaction(List<String> calls,
                                                                      AtomicReference<IsolationLevel> isolationLevel) {
        return connectionRecordingTransaction(calls, isolationLevel, new AtomicReference<>(0));
    }

    private static RepositoryConnection connectionRecordingTransaction(List<String> calls,
                                                                      AtomicReference<IsolationLevel> isolationLevel,
                                                                      AtomicReference<Integer> statementCount) {
        return (RepositoryConnection) Proxy.newProxyInstance(
                Rdf4jUploaderTest.class.getClassLoader(),
                new Class<?>[]{RepositoryConnection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "begin" -> {
                            calls.add("begin");
                            isolationLevel.set((IsolationLevel) args[0]);
                            return null;
                        }
                        case "add" -> {
                            calls.add("add");
                            if (args[0] instanceof Iterable<?> statements) {
                                for (Object statement : statements) {
                                    if (statement instanceof Statement) {
                                        statementCount.set(statementCount.get() + 1);
                                    }
                                }
                            } else if (args[0] instanceof Statement) {
                                statementCount.set(statementCount.get() + 1);
                            }
                            return null;
                        }
                        case "commit" -> {
                            calls.add("commit");
                            return null;
                        }
                        case "setIsolationLevel" -> throw new AssertionError("setIsolationLevel must not be used");
                        default -> {
                        }
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }
}

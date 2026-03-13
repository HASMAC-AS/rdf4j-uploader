package com.hasmac.rdf4juploader;

import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rdf4jUploader — connects to a remote RDF4J repository and uploads RDF files
 * from a local folder in parallel using a fixed thread pool.
 *
 * <pre>
 * Usage:
 *   java -jar rdf4j-uploader-1.0.0.jar \
 *        --endpoint http://localhost:8080/rdf4j-server \
 *        --repository myrepo \
 *        --folder /path/to/rdf/files \
 *        --threads 8
 *        [--base-uri http://example.org/]
 * </pre>
 */
public class Rdf4jUploader {

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {

        // --- Parse command-line arguments ------------------------------------
        String endpointUrl = null;
        String repositoryId = null;
        String folderPath = null;
        String baseUri = null;    // optional base URI for resolving relative IRIs in RDF files
        int threadCount = 4;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--endpoint",   "-e" -> {
                    if (i + 1 >= args.length) { System.err.println("[ERROR] Missing value for " + args[i]); System.exit(1); }
                    endpointUrl  = args[++i];
                }
                case "--repository", "-r" -> {
                    if (i + 1 >= args.length) { System.err.println("[ERROR] Missing value for " + args[i]); System.exit(1); }
                    repositoryId = args[++i];
                }
                case "--folder",     "-f" -> {
                    if (i + 1 >= args.length) { System.err.println("[ERROR] Missing value for " + args[i]); System.exit(1); }
                    folderPath   = args[++i];
                }
                case "--threads",    "-t" -> {
                    if (i + 1 >= args.length) { System.err.println("[ERROR] Missing value for " + args[i]); System.exit(1); }
                    try {
                        threadCount = Integer.parseInt(args[++i]);
                        if (threadCount < 1) throw new NumberFormatException("must be >= 1");
                    } catch (NumberFormatException e) {
                        System.err.println("[ERROR] Invalid thread count '" + args[i] + "': " + e.getMessage());
                        System.exit(1);
                    }
                }
                case "--base-uri",   "-b" -> {
                    if (i + 1 >= args.length) { System.err.println("[ERROR] Missing value for " + args[i]); System.exit(1); }
                    baseUri = args[++i];
                }
                default -> System.out.println("[WARN] Unknown argument ignored: " + args[i]);
            }
        }

        // --- Validate required arguments -------------------------------------
        if (endpointUrl == null || repositoryId == null || folderPath == null) {
            System.err.println("Usage: rdf4j-uploader --endpoint <serverUrl> --repository <repoId>"
                    + " --folder <folderPath> [--threads <n>] [--base-uri <uri>]");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  java -jar rdf4j-uploader-1.0.0.jar \\");
            System.err.println("       --endpoint http://localhost:8080/rdf4j-server \\");
            System.err.println("       --repository myrepo \\");
            System.err.println("       --folder /data/rdf \\");
            System.err.println("       --threads 8 \\");
            System.err.println("       --base-uri http://example.org/");
            System.exit(1);
        }

        printBanner();
        System.out.println("[INFO] Endpoint      : " + endpointUrl);
        System.out.println("[INFO] Repository    : " + repositoryId);
        System.out.println("[INFO] Folder        : " + folderPath);
        System.out.println("[INFO] Thread count  : " + threadCount);
        System.out.println("[INFO] Base URI      : " + (baseUri != null ? baseUri : "(none — relative IRIs resolved per-file)"));
        System.out.println(separator());

        // --- Validate folder -------------------------------------------------
        Path folder = Paths.get(folderPath);
        if (!Files.isDirectory(folder)) {
            System.err.println("[ERROR] Not a directory (or does not exist): " + folderPath);
            System.exit(1);
        }

        // --- Discover RDF files ----------------------------------------------
        System.out.println("[INFO] Scanning folder for RDF files: " + folder.toAbsolutePath());
        List<Path> rdfFiles = findRdfFiles(folder);

        if (rdfFiles.isEmpty()) {
            System.out.println("[WARN] No supported RDF files found in: " + folderPath);
            System.out.println("[INFO] Supported extensions: .ttl, .rdf, .xml, .nt, .nq, .trig, .jsonld, .n3, ...");
            System.exit(0);
        }

        System.out.println("[INFO] Found " + rdfFiles.size() + " RDF file(s):");
        for (Path f : rdfFiles) {
            System.out.printf("[INFO]   %-40s  %s%n",
                    f.getFileName(), formatSize(Files.size(f)));
        }
        System.out.println(separator());

        // --- Connect to RDF4J server -----------------------------------------
        // HTTPRepository is thread-safe: multiple threads may each call
        // repository.getConnection() concurrently to obtain independent connections.
        System.out.println("[INFO] Initialising connection to RDF4J server ...");
        HTTPRepository repository = new HTTPRepository(endpointUrl, repositoryId);
        repository.init();
        System.out.println("[INFO] Connection to repository '" + repositoryId + "' established.");
        System.out.println(separator());

        // --- Set up fixed thread pool ----------------------------------------
        System.out.println("[INFO] Creating fixed thread pool with " + threadCount + " thread(s).");
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        AtomicInteger uploadedCount = new AtomicInteger(0);
        AtomicInteger failedCount   = new AtomicInteger(0);
        int totalFiles = rdfFiles.size();
        long startTime = System.currentTimeMillis();

        System.out.println("[INFO] Submitting " + totalFiles + " upload task(s) to the thread pool ...");
        System.out.println(separator());

        // Capture effectively-final copy for use in lambdas
        final String effectiveBaseUri = baseUri;

        // --- Submit one upload task per file ---------------------------------
        for (Path filePath : rdfFiles) {
            executor.submit(() ->
                uploadFile(repository, filePath, effectiveBaseUri, uploadedCount, failedCount, totalFiles));
        }

        // --- Wait for all tasks to complete ----------------------------------
        executor.shutdown();
        System.out.println("[INFO] All tasks submitted. Waiting for threads to finish ...");

        boolean finished = executor.awaitTermination(1, TimeUnit.HOURS);
        if (!finished) {
            System.out.println("[WARN] Upload timed out after 1 hour. Forcing shutdown.");
            // Interrupt running threads; uploaded/failed counts may be incomplete.
            executor.shutdownNow();
            // Wait briefly for interrupted tasks to release connections before shutdown.
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }

        // --- Print summary ---------------------------------------------------
        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println(separator());
        System.out.println("[INFO] ===  Upload Summary  ===");
        System.out.println("[INFO] Total files  : " + totalFiles);
        System.out.println("[INFO] Uploaded     : " + uploadedCount.get());
        System.out.println("[INFO] Failed       : " + failedCount.get());
        System.out.println("[INFO] Elapsed time : " + formatElapsed(elapsed));
        System.out.println(separator());

        // --- Clean up --------------------------------------------------------
        repository.shutDown();
        System.out.println("[INFO] Repository connection closed. Goodbye.");
    }

    // -------------------------------------------------------------------------
    // Upload a single RDF file (executed by a worker thread)
    // -------------------------------------------------------------------------

    private static void uploadFile(HTTPRepository repository,
                                   Path filePath,
                                   String baseUri,
                                   AtomicInteger uploadedCount,
                                   AtomicInteger failedCount,
                                   int totalFiles) {

        String fileName  = filePath.getFileName().toString();
        String thread    = Thread.currentThread().getName();

        System.out.println("[" + thread + "] Preparing  : " + fileName);

        // Detect RDF format from file extension
        Optional<RDFFormat> formatOpt = Rio.getParserFormatForFileName(fileName);
        if (formatOpt.isEmpty()) {
            System.out.println("[" + thread + "] [SKIP] No RDF parser registered for: " + fileName);
            failedCount.incrementAndGet();
            return;
        }

        RDFFormat format = formatOpt.get();
        long fileSize;
        try {
            fileSize = Files.size(filePath);
        } catch (IOException e) {
            fileSize = -1;
        }

        System.out.printf("[%s] Format      : %s  |  Size : %s%n",
                thread, format.getName(), formatSize(fileSize));
        System.out.println("[" + thread + "] Uploading  : " + fileName + " ...");

        long t0 = System.currentTimeMillis();

        try (RepositoryConnection conn = repository.getConnection()) {

            // baseUri is used to resolve relative IRIs in the RDF data.
            // When null, RDF4J falls back to the file's own URI as the base,
            // which is correct for most well-formed RDF files.
            conn.add(filePath.toFile(), baseUri, format);

            long elapsed = System.currentTimeMillis() - t0;
            int done = uploadedCount.incrementAndGet();

            System.out.printf("[%s] [OK] %-35s  %s  in %s  [%d/%d]%n",
                    thread,
                    fileName,
                    formatSize(fileSize),
                    formatElapsed(elapsed),
                    done,
                    totalFiles);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            int failed = failedCount.incrementAndGet();

            System.out.printf("[%s] [ERROR] Failed to upload: %s  (after %s)  [failed=%d]%n",
                    thread, fileName, formatElapsed(elapsed), failed);
            System.out.println("[" + thread + "] [ERROR] Cause: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Recursively walks {@code folder} and returns every regular file whose
     * name matches a parser registered with RDF4J.
     */
    private static List<Path> findRdfFiles(Path folder) throws IOException {
        try (Stream<Path> stream = Files.walk(folder)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> Rio.getParserFormatForFileName(
                            p.getFileName().toString()).isPresent())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /** Returns a fixed-width separator line. */
    private static String separator() {
        return "------------------------------------------------------------";
    }

    private static void printBanner() {
        System.out.println("============================================================");
        System.out.println("            RDF4J Parallel Uploader  v1.0.0                ");
        System.out.println("============================================================");
    }

    /** Human-readable file size. */
    private static String formatSize(long bytes) {
        if (bytes < 0)               return "unknown";
        if (bytes < 1_024)           return bytes + " B";
        if (bytes < 1_048_576)       return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824)   return String.format("%.1f MB", bytes / 1_048_576.0);
        return                              String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    /** Human-readable elapsed time. */
    private static String formatElapsed(long millis) {
        if (millis < 1_000)  return millis + " ms";
        if (millis < 60_000) return String.format("%.2f s", millis / 1_000.0);
        long minutes = millis / 60_000;
        long seconds = (millis % 60_000) / 1_000;
        return minutes + "m " + seconds + "s";
    }
}

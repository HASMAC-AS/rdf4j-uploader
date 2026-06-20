package com.hasmac.rdf4juploader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

final class UploadScheduler {

	private static final Duration COMPLETION_TIMEOUT = Duration.ofHours(1);
	private static final Duration FORCED_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

	private UploadScheduler() {
	}

	static Result run(List<Path> files,
	                  int maxConcurrentUploads,
	                  ExecutorService executor,
	                  StopRequested stopRequested,
	                  UploadTask uploadTask) throws Exception {
		Objects.requireNonNull(files, "files");
		Objects.requireNonNull(executor, "executor");
		Objects.requireNonNull(stopRequested, "stopRequested");
		Objects.requireNonNull(uploadTask, "uploadTask");
		if (maxConcurrentUploads < 1) {
			throw new IllegalArgumentException("maxConcurrentUploads must be >= 1");
		}

		ExecutorCompletionService<Void> completions = new ExecutorCompletionService<>(executor);
		int nextFileIndex = 0;
		int submittedCount = 0;
		int completedCount = 0;
		boolean timedOut = false;

		while (submittedCount < maxConcurrentUploads && nextFileIndex < files.size() && !stopRequested.get()) {
			if (!submit(completions, uploadTask, files.get(nextFileIndex))) {
				break;
			}
			nextFileIndex++;
			submittedCount++;
		}

		while (completedCount < submittedCount) {
			if (stopRequested.get()) {
				executor.shutdown();
			}

			Future<Void> completed = completions.poll(COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (completed == null) {
				timedOut = true;
				executor.shutdownNow();
				break;
			}

			completedCount++;
			rethrowTaskFailure(completed);

			while (submittedCount - completedCount < maxConcurrentUploads
					&& nextFileIndex < files.size()
					&& !stopRequested.get()
					&& !executor.isShutdown()) {
				if (!submit(completions, uploadTask, files.get(nextFileIndex))) {
					break;
				}
				nextFileIndex++;
				submittedCount++;
			}
		}

		executor.shutdown();
		boolean terminated = executor.awaitTermination(FORCED_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		return new Result(files.size(), submittedCount, completedCount, nextFileIndex == files.size(), stopRequested.get(),
				timedOut, terminated);
	}

	private static boolean submit(ExecutorCompletionService<Void> completions,
	                              UploadTask uploadTask,
	                              Path file) {
		try {
			completions.submit(() -> {
				uploadTask.upload(file);
				return null;
			});
			return true;
		} catch (RejectedExecutionException e) {
			return false;
		}
	}

	private static void rethrowTaskFailure(Future<Void> completed) throws Exception {
		try {
			completed.get();
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception exception) {
				throw exception;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(cause);
		}
	}

	record Result(int totalCount,
	              int submittedCount,
	              int completedCount,
	              boolean allFilesSubmitted,
	              boolean stopRequested,
	              boolean timedOut,
	              boolean executorTerminated) {
		boolean completedAll() {
			return allFilesSubmitted && completedCount == totalCount && !timedOut && executorTerminated;
		}
	}

	@FunctionalInterface
	interface StopRequested {
		boolean get();
	}

	@FunctionalInterface
	interface UploadTask {
		void upload(Path file) throws Exception;
	}
}

package com.hasmac.rdf4juploader;

import java.time.Duration;
import java.util.Objects;

final class UploadRetrier {

	static final RetryPolicy DEFAULT_POLICY = new RetryPolicy(10, Duration.ofSeconds(10));

	private UploadRetrier() {
	}

	static void run(UploadOperation operation,
	                RetryPolicy policy,
	                Sleeper sleeper,
	                RetryListener listener) throws Exception {
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(sleeper, "sleeper");
		Objects.requireNonNull(listener, "listener");

		for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
			try {
				operation.run();
				return;
			} catch (Exception failure) {
				if (attempt == policy.maxAttempts()) {
					throw failure;
				}
				listener.retrying(attempt, policy.maxAttempts(), policy.delay(), failure);
				try {
					sleeper.sleep(policy.delay());
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					failure.addSuppressed(interrupted);
					throw failure;
				}
			}
		}
	}

	record RetryPolicy(int maxAttempts, Duration delay) {
		RetryPolicy {
			Objects.requireNonNull(delay, "delay");
			if (maxAttempts < 1) {
				throw new IllegalArgumentException("maxAttempts must be >= 1");
			}
			if (delay.isNegative()) {
				throw new IllegalArgumentException("delay must not be negative");
			}
		}
	}

	@FunctionalInterface
	interface UploadOperation {
		void run() throws Exception;
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(Duration delay) throws InterruptedException;
	}

	@FunctionalInterface
	interface RetryListener {
		void retrying(int failedAttempt, int maxAttempts, Duration delay, Exception failure);
	}
}

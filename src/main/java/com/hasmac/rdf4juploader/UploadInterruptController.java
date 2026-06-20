package com.hasmac.rdf4juploader;

import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class UploadInterruptController {

	private final Rdf4jUploader.UploadConfig config;
	private final PrintStream err;
	private final AtomicBoolean uploadInProgress = new AtomicBoolean(true);
	private final AtomicBoolean stopRequested = new AtomicBoolean(false);
	private final AtomicBoolean forceStopRequested = new AtomicBoolean(false);
	private final AtomicInteger interruptCount = new AtomicInteger(0);
	private final AtomicReference<ExecutorService> executor = new AtomicReference<>();

	UploadInterruptController(Rdf4jUploader.UploadConfig config, PrintStream err) {
		this.config = Objects.requireNonNull(config, "config");
		this.err = Objects.requireNonNull(err, "err");
	}

	static UploadInterruptController install(Rdf4jUploader.UploadConfig config) {
		UploadInterruptController controller = new UploadInterruptController(config, System.err);
		if (!installSignalHandler(controller)) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				if (controller.uploadInProgress.get()) {
					ResumeCommand.printHint(config, System.err);
				}
			}, "rdf4j-uploader-resume-hint"));
		}
		return controller;
	}

	void attach(ExecutorService executor) {
		this.executor.set(Objects.requireNonNull(executor, "executor"));
		if (forceStopRequested.get()) {
			executor.shutdownNow();
		} else if (stopRequested.get()) {
			executor.shutdown();
		}
	}

	void finish() {
		uploadInProgress.set(false);
	}

	boolean stopRequested() {
		return stopRequested.get();
	}

	boolean forceStopRequested() {
		return forceStopRequested.get();
	}

	void handleInterrupt() {
		if (!uploadInProgress.get()) {
			return;
		}

		int count = interruptCount.incrementAndGet();
		stopRequested.set(true);
		if (count == 1) {
			err.println();
			err.println("[INFO] Ctrl-C received. No new files will start; waiting for current uploads.");
			err.println("[INFO] Press Ctrl-C again to interrupt active uploads.");
			ResumeCommand.printHint(config, err);
			ExecutorService attachedExecutor = executor.get();
			if (attachedExecutor != null) {
				attachedExecutor.shutdown();
			}
			return;
		}

		forceStopRequested.set(true);
		err.println();
		err.println("[WARN] Second Ctrl-C received. Interrupting active uploads.");
		ExecutorService attachedExecutor = executor.get();
		if (attachedExecutor != null) {
			attachedExecutor.shutdownNow();
		}
	}

	private static boolean installSignalHandler(UploadInterruptController controller) {
		try {
			Class<?> signalClass = Class.forName("sun.misc.Signal");
			Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
			Object signal = signalClass.getConstructor(String.class).newInstance("INT");
			Object handler = Proxy.newProxyInstance(
					UploadInterruptController.class.getClassLoader(),
					new Class<?>[]{handlerClass},
					(proxy, method, args) -> {
						if ("handle".equals(method.getName())) {
							controller.handleInterrupt();
						}
						return null;
					});
			signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
			return true;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return false;
		}
	}
}

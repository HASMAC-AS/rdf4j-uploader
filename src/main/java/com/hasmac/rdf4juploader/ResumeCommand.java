package com.hasmac.rdf4juploader;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class ResumeCommand {

	private static final String JAR_FILE = "rdf4j-uploader-1.0.0.jar";

	private ResumeCommand() {
	}

	static AtomicBoolean installCtrlCResumeHint(Rdf4jUploader.UploadConfig config) {
		AtomicBoolean uploadInProgress = new AtomicBoolean(true);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (uploadInProgress.get()) {
				printHint(config, System.err);
			}
		}, "rdf4j-uploader-resume-hint"));
		return uploadInProgress;
	}

	static String build(Rdf4jUploader.UploadConfig config) {
		StringBuilder command = new StringBuilder("java -jar ")
				.append(shellArg(JAR_FILE))
				.append(" --endpoint ").append(shellArg(config.endpointUrl()))
				.append(" --repository ").append(shellArg(config.repositoryId()))
				.append(" --folder ").append(shellArg(config.folderPath()))
				.append(" --threads ").append(config.threadCount());
		if (config.baseUri() != null) {
			command.append(" --base-uri ").append(shellArg(config.baseUri()));
		}
		command.append(" --isolation-level ").append(shellArg(Rdf4jUploader.formatIsolationLevel(config.isolationLevel())))
				.append(" --resume")
				.append(" --progress-file ").append(shellArg(config.progressFile().toString()));
		return command.toString();
	}

	static void printHint(Rdf4jUploader.UploadConfig config, PrintStream err) {
		err.println();
		err.println("[INFO] Upload interrupted.");
		err.println("[INFO] Resume with:");
		err.println(build(config));
	}

	private static String shellArg(String value) {
		if (value.matches("[A-Za-z0-9_@%+=:,./-]+")) {
			return value;
		}
		return "'" + value.replace("'", "'\"'\"'") + "'";
	}
}

package com.hasmac.rdf4juploader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

final class UploadProgress {

	static final String DEFAULT_FILE_NAME = ".rdf4j-uploader-progress.properties";

	private final Path root;
	private final Path progressFile;
	private final Properties entries;

	private UploadProgress(Path root, Path progressFile, Properties entries) {
		this.root = root.toAbsolutePath().normalize();
		this.progressFile = progressFile.toAbsolutePath().normalize();
		this.entries = entries;
	}

	static UploadProgress load(Path root, Path progressFile) throws IOException {
		Objects.requireNonNull(root, "root");
		Objects.requireNonNull(progressFile, "progressFile");

		Properties entries = new Properties();
		if (Files.isRegularFile(progressFile)) {
			try (InputStream input = Files.newInputStream(progressFile)) {
				entries.load(input);
			}
		}
		return new UploadProgress(root, progressFile, entries);
	}

	synchronized boolean isUploaded(Path file) {
		return statusOf(file) == Status.UPLOADED;
	}

	synchronized Status statusOf(Path file) {
		String value = entries.getProperty(key(file));
		if (value == null) {
			return Status.PENDING;
		}
		try {
			return Status.valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return Status.PENDING;
		}
	}

	synchronized void markUploaded(Path file) throws IOException {
		mark(file, Status.UPLOADED);
	}

	synchronized void markFailed(Path file) throws IOException {
		mark(file, Status.FAILED);
	}

	private void mark(Path file, Status status) throws IOException {
		entries.setProperty(key(file), status.name());
		save();
	}

	private String key(Path file) {
		Path absoluteFile = file.toAbsolutePath().normalize();
		if (absoluteFile.startsWith(root)) {
			return root.relativize(absoluteFile).toString().replace(File.separatorChar, '/');
		}
		return absoluteFile.toString();
	}

	private void save() throws IOException {
		Files.createDirectories(progressFile.getParent());
		Path tempFile = Files.createTempFile(progressFile.getParent(),
				progressFile.getFileName().toString(), ".tmp");
		try (OutputStream output = Files.newOutputStream(tempFile)) {
			entries.store(output, "rdf4j-uploader progress");
		}
		try {
			Files.move(tempFile, progressFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tempFile, progressFile, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	enum Status {
		PENDING,
		UPLOADED,
		FAILED
	}
}

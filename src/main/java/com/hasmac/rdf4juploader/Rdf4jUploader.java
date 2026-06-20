package com.hasmac.rdf4juploader;

import org.eclipse.rdf4j.common.transaction.IsolationLevel;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.repository.util.RDFInserter;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *        [--isolation-level read-committed]
 * </pre>
 */
public class Rdf4jUploader {

	// -------------------------------------------------------------------------
	// Entry point
	// -------------------------------------------------------------------------

	public static void main(String[] args) throws Exception {

		UploadConfig config;
		try {
			config = parseArguments(args);
		} catch (IllegalArgumentException e) {
			System.err.println("[ERROR] " + e.getMessage());
			printUsage();
			System.exit(1);
			return;
		}

		printBanner();
		System.out.println("[INFO] Endpoint      : " + config.endpointUrl());
		System.out.println("[INFO] Repository    : " + config.repositoryId());
		System.out.println("[INFO] Folder        : " + config.folderPath());
		System.out.println("[INFO] Thread count  : " + config.threadCount());
		System.out.println("[INFO] Isolation     : " + formatIsolationLevel(config.isolationLevel()));
		System.out.println("[INFO] Base URI      : " + (config.baseUri() != null ? config.baseUri() : "(none — relative IRIs resolved per-file)"));
		System.out.println("[INFO] Resume        : " + (config.resume() ? "on (" + config.progressFile() + ")" : "off"));
		System.out.println(separator());

		// --- Validate folder -------------------------------------------------
		Path folder = Paths.get(config.folderPath());
		if (!Files.isDirectory(folder)) {
			System.err.println("[ERROR] Not a directory (or does not exist): " + config.folderPath());
			System.exit(1);
		}

		// --- Discover RDF files ----------------------------------------------
		System.out.println("[INFO] Scanning folder for RDF files: " + folder.toAbsolutePath());
		List<Path> discoveredFiles = findRdfFiles(folder);

		if (discoveredFiles.isEmpty()) {
			System.out.println("[WARN] No supported RDF files found in: " + config.folderPath());
			System.out.println("[INFO] Supported extensions: .ttl, .rdf, .xml, .nt, .nq, .trig, .jsonld, .n3, ...");
			System.exit(0);
		}

		UploadProgress progress = UploadProgress.load(folder, config.progressFile());
		List<Path> rdfFiles = discoveredFiles;
		int skippedCount = 0;
		if (config.resume()) {
			rdfFiles = discoveredFiles.stream()
					.filter(file -> !progress.isUploaded(file))
					.collect(Collectors.toList());
			skippedCount = discoveredFiles.size() - rdfFiles.size();
			System.out.println("[INFO] Resume progress file: " + config.progressFile().toAbsolutePath().normalize());
			System.out.println("[INFO] Already uploaded   : " + skippedCount);
			if (rdfFiles.isEmpty()) {
				System.out.println("[INFO] Nothing left to upload.");
				System.exit(0);
			}
		}
		UploadInterruptController interruptController = UploadInterruptController.install(config);

		System.out.println("[INFO] Found " + discoveredFiles.size() + " RDF file(s); pending upload: " + rdfFiles.size());
		for (Path f : rdfFiles) {
			System.out.printf("[INFO]   %-40s  %s%n",
					f.getFileName(), formatSize(Files.size(f)));
		}
		System.out.println(separator());

		// --- Connect to RDF4J server -----------------------------------------
		// HTTPRepository is thread-safe: multiple threads may each call
		// repository.getConnection() concurrently to obtain independent connections.
		System.out.println("[INFO] Initialising connection to RDF4J server ...");
		HTTPRepository repository = new HTTPRepository(config.endpointUrl(), config.repositoryId());
		repository.init();
		System.out.println("[INFO] Connection to repository '" + config.repositoryId() + "' established.");
		System.out.println(separator());

		// --- Set up fixed thread pool ----------------------------------------
		System.out.println("[INFO] Creating fixed thread pool with " + config.threadCount() + " thread(s).");
		ExecutorService executor = Executors.newFixedThreadPool(config.threadCount());
		interruptController.attach(executor);

		AtomicInteger uploadedCount = new AtomicInteger(0);
		AtomicInteger failedCount = new AtomicInteger(0);
		int totalFiles = rdfFiles.size();
		long startTime = System.currentTimeMillis();

		System.out.println("[INFO] Uploading up to " + config.threadCount() + " file(s) concurrently ...");
		System.out.println(separator());

		// Capture effectively-final copy for use in lambdas
		final String effectiveBaseUri = config.baseUri();
		final IsolationLevel effectiveIsolationLevel = config.isolationLevel();
		final UploadProgress effectiveProgress = progress;

		UploadScheduler.Result uploadResult = UploadScheduler.run(rdfFiles, config.threadCount(), executor,
				interruptController::stopRequested,
				filePath -> uploadFile(repository, filePath, effectiveBaseUri, effectiveIsolationLevel,
						effectiveProgress, uploadedCount, failedCount, totalFiles));
		if (uploadResult.timedOut()) {
			System.out.println("[WARN] Upload timed out after 1 hour. Forced shutdown.");
		}
		if (!uploadResult.executorTerminated()) {
			System.out.println("[WARN] Upload workers did not stop within 30 seconds after shutdown.");
		}

		// --- Print summary ---------------------------------------------------
		long elapsed = System.currentTimeMillis() - startTime;
		System.out.println(separator());
		System.out.println("[INFO] ===  Upload Summary  ===");
		System.out.println("[INFO] Total files  : " + totalFiles);
		if (config.resume()) {
			System.out.println("[INFO] Skipped      : " + skippedCount);
		}
		if (interruptController.stopRequested()) {
			System.out.println("[INFO] Interrupted  : yes");
			System.out.println("[INFO] Not started  : " + (totalFiles - uploadResult.submittedCount()));
		}
		System.out.println("[INFO] Uploaded     : " + uploadedCount.get());
		System.out.println("[INFO] Failed       : " + failedCount.get());
		System.out.println("[INFO] Elapsed time : " + formatElapsed(elapsed));
		System.out.println(separator());

		// --- Clean up --------------------------------------------------------
		repository.shutDown();
		interruptController.finish();
		System.out.println("[INFO] Repository connection closed. Goodbye.");
		if (!uploadResult.completedAll() || failedCount.get() > 0) {
			System.exit(1);
		}
	}

	// -------------------------------------------------------------------------
	// Upload a single RDF file (executed by a worker thread)
	// -------------------------------------------------------------------------

	private static void uploadFile(HTTPRepository repository,
	                               Path filePath,
	                               String baseUri,
	                               IsolationLevel isolationLevel,
	                               UploadProgress progress,
	                               AtomicInteger uploadedCount,
	                               AtomicInteger failedCount,
	                               int totalFiles) {

		String fileName = filePath.getFileName().toString();
		String thread = Thread.currentThread().getName();

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
		AtomicInteger attemptCount = new AtomicInteger(0);

		try {
			UploadRetrier.run(() -> {
						attemptCount.incrementAndGet();
						try (RepositoryConnection conn = repository.getConnection()) {
							addFile(conn, filePath, baseUri, format, isolationLevel);
						}
					},
					UploadRetrier.DEFAULT_POLICY,
					delay -> Thread.sleep(delay.toMillis()),
					(failedAttempt, maxAttempts, delay, failure) ->
							System.out.printf("[%s] [WARN] Attempt %d/%d failed for %s: %s. Retrying in %s.%n",
									thread,
									failedAttempt,
									maxAttempts,
									fileName,
									failure.getMessage(),
									formatElapsed(delay.toMillis())));

			if (progress != null) {
				progress.markUploaded(filePath);
			}

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
			markFailed(progress, filePath, e);
			long elapsed = System.currentTimeMillis() - t0;
			int failed = failedCount.incrementAndGet();

			System.out.printf("[%s] [ERROR] Failed to upload: %s  (after %d attempt(s), %s)  [failed=%d]%n",
					thread, fileName, attemptCount.get(), formatElapsed(elapsed), failed);
			System.out.println("[" + thread + "] [ERROR] Cause: " + e.getMessage());
			e.printStackTrace(System.err);
		}
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	static String resumeCommand(UploadConfig config) {
		return ResumeCommand.build(config);
	}

	private static void markFailed(UploadProgress progress, Path filePath, Exception failure) {
		if (progress == null) {
			return;
		}
		try {
			progress.markFailed(filePath);
		} catch (IOException progressFailure) {
			failure.addSuppressed(progressFailure);
			System.err.println("[ERROR] Could not update progress file: " + progressFailure.getMessage());
		}
	}

	record UploadConfig(String endpointUrl,
	                           String repositoryId,
	                           String folderPath,
	                           String baseUri,
	                           int threadCount,
	                           IsolationLevel isolationLevel,
	                           boolean resume,
	                           Path progressFile) {
	}

	static UploadConfig parseArguments(String[] args) {
		String endpointUrl = null;
		String repositoryId = null;
		String folderPath = null;
		String baseUri = null;
		int threadCount = 4;
		IsolationLevel isolationLevel = IsolationLevels.READ_COMMITTED;
		boolean resume = false;
		Path progressFile = null;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--endpoint", "-e" -> endpointUrl = requireValue(args, ++i, args[i - 1]);
				case "--repository", "-r" -> repositoryId = requireValue(args, ++i, args[i - 1]);
				case "--folder", "-f" -> folderPath = requireValue(args, ++i, args[i - 1]);
				case "--threads", "-t" -> threadCount = parseThreadCount(requireValue(args, ++i, args[i - 1]));
				case "--base-uri", "-b" -> baseUri = requireValue(args, ++i, args[i - 1]);
				case "--isolation-level", "-i" -> isolationLevel = parseIsolationLevel(requireValue(args, ++i, args[i - 1]));
				case "--resume" -> resume = true;
				case "--progress-file" -> {
					resume = true;
					progressFile = Paths.get(requireValue(args, ++i, args[i - 1]));
				}
				default -> System.out.println("[WARN] Unknown argument ignored: " + args[i]);
			}
		}

		if (endpointUrl == null || repositoryId == null || folderPath == null) {
			throw new IllegalArgumentException("Missing required arguments: --endpoint, --repository, and --folder");
		}

		if (progressFile == null) {
			progressFile = Paths.get(folderPath).resolve(UploadProgress.DEFAULT_FILE_NAME);
		}

		return new UploadConfig(endpointUrl, repositoryId, folderPath, baseUri, threadCount,
				isolationLevel, resume, progressFile);
	}

	static void addFile(RepositoryConnection conn,
	                    Path filePath,
	                    String baseUri,
	                    RDFFormat format,
	                    IsolationLevel isolationLevel) throws Exception {
		conn.begin(isolationLevel);
		try {
			parseLeniently(conn, filePath, baseUri, format);
			conn.commit();
		} catch (Exception e) {
			try {
				conn.rollback();
			} catch (Exception rollbackFailure) {
				e.addSuppressed(rollbackFailure);
			}
			throw e;
		}
	}

	private static void parseLeniently(RepositoryConnection conn,
	                                   Path filePath,
	                                   String baseUri,
	                                   RDFFormat format) throws Exception {
		String effectiveBaseUri = baseUri != null ? baseUri : filePath.toUri().toString();
		if (RDFFormat.NTRIPLES.equals(format) || RDFFormat.NQUADS.equals(format)) {
			parseLineDelimitedLeniently(conn, filePath, effectiveBaseUri, format);
			return;
		}

		RDFParser parser = Rio.createParser(format);
		parser.setParserConfig(lenientParserConfig());
		parser.setRDFHandler(new RDFInserter(conn));
		try (InputStream input = Files.newInputStream(filePath)) {
			parser.parse(input, effectiveBaseUri);
		}
	}

	private static void parseLineDelimitedLeniently(RepositoryConnection conn,
	                                               Path filePath,
	                                               String baseUri,
	                                               RDFFormat format) throws IOException {
		ParserConfig parserConfig = lenientParserConfig();
		for (String line : Files.readAllLines(filePath)) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			try (InputStream input = new java.io.ByteArrayInputStream(line.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
				conn.add(Rio.parse(input, baseUri, format, parserConfig));
			} catch (RDFParseException e) {
				System.out.println("[WARN] Dropping unparsable statement in " + filePath.getFileName() + ": " + e.getMessage());
			}
		}
	}

	private static ParserConfig lenientParserConfig() {
		return new ParserConfig()
				.set(BasicParserSettings.VERIFY_DATATYPE_VALUES, true)
				.set(BasicParserSettings.VERIFY_LANGUAGE_TAGS, true)
				.set(BasicParserSettings.VERIFY_RELATIVE_URIS, true)
				.set(BasicParserSettings.VERIFY_URI_SYNTAX, true)
				.setNonFatalErrors(Set.of(
						BasicParserSettings.VERIFY_DATATYPE_VALUES,
						BasicParserSettings.FAIL_ON_UNKNOWN_DATATYPES,
						BasicParserSettings.VERIFY_LANGUAGE_TAGS,
						BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES,
						BasicParserSettings.VERIFY_RELATIVE_URIS,
						BasicParserSettings.VERIFY_URI_SYNTAX));
	}

	static String formatIsolationLevel(IsolationLevel isolationLevel) {
		if (isolationLevel instanceof Enum<?> enumValue) {
			return enumValue.name();
		}
		return isolationLevel.getValue();
	}

	private static String requireValue(String[] args, int valueIndex, String option) {
		if (valueIndex >= args.length) {
			throw new IllegalArgumentException("Missing value for " + option);
		}
		return args[valueIndex];
	}

	private static int parseThreadCount(String value) {
		try {
			int threadCount = Integer.parseInt(value);
			if (threadCount < 1) {
				throw new NumberFormatException("must be >= 1");
			}
			return threadCount;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid thread count '" + value + "': " + e.getMessage(), e);
		}
	}

	private static IsolationLevel parseIsolationLevel(String value) {
		String normalized = value.trim()
				.toUpperCase(Locale.ROOT)
				.replace('-', '_')
				.replace(' ', '_');
		try {
			return IsolationLevels.valueOf(normalized);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid isolation level '" + value + "'. Supported: "
					+ supportedIsolationLevels(), e);
		}
	}

	private static String supportedIsolationLevels() {
		return Stream.of(IsolationLevels.values())
				.map(IsolationLevels::name)
				.collect(Collectors.joining(", "));
	}

	private static void printUsage() {
		System.err.println("Usage: rdf4j-uploader --endpoint <serverUrl> --repository <repoId>"
				+ " --folder <folderPath> [--threads <n>] [--base-uri <uri>] [--isolation-level <level>]"
				+ " [--resume] [--progress-file <path>]");
		System.err.println();
		System.err.println("Isolation levels: " + supportedIsolationLevels());
		System.err.println("Resume: --resume skips files already marked UPLOADED in "
				+ UploadProgress.DEFAULT_FILE_NAME + ". --progress-file enables resume with a custom path.");
		System.err.println();
		System.err.println("Example:");
		System.err.println("  java -jar rdf4j-uploader-1.0.0.jar \\");
		System.err.println("       --endpoint http://localhost:8080/rdf4j-server \\");
		System.err.println("       --repository myrepo \\");
		System.err.println("       --folder /data/rdf \\");
		System.err.println("       --threads 8 \\");
		System.err.println("       --base-uri http://example.org/ \\");
		System.err.println("       --isolation-level read-committed \\");
		System.err.println("       --resume");
	}

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
		if (bytes < 0) {
			return "unknown";
		}
		if (bytes < 1_024) {
			return bytes + " B";
		}
		if (bytes < 1_048_576) {
			return String.format("%.1f KB", bytes / 1_024.0);
		}
		if (bytes < 1_073_741_824) {
			return String.format("%.1f MB", bytes / 1_048_576.0);
		}
		return String.format("%.2f GB", bytes / 1_073_741_824.0);
	}

	/** Human-readable elapsed time. */
	private static String formatElapsed(long millis) {
		if (millis < 1_000) {
			return millis + " ms";
		}
		if (millis < 60_000) {
			return String.format("%.2f s", millis / 1_000.0);
		}
		long minutes = millis / 60_000;
		long seconds = (millis % 60_000) / 1_000;
		return minutes + "m " + seconds + "s";
	}
}

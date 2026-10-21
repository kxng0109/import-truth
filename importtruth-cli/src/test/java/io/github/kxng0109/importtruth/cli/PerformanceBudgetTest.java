package io.github.kxng0109.importtruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import io.github.kxng0109.importtruth.index.JarIndexStore;

/**
 * Process level performance budget: the wall clock of the CLI check over
 * this repository, the bytes the index costs per symbol, the resident
 * memory of the MCP server, and the burst of eight concurrent requests.
 * <p>
 * Every budget is a promise the build has to keep, so every ceiling sits
 * well above the value measured on this machine. A budget that flakes on
 * a loaded machine is worse than no budget at all, and a budget that is
 * only printed is not a budget.
 */
@DisplayName("Process level performance budget")
@Tag("slow")
final class PerformanceBudgetTest {

	/** Wall clock ceiling for one warm check of every source in the repository. */
	private static final long CHECK_CEILING_MILLIS = 8_000L;

	/** Bytes one indexed symbol may cost on disk. */
	private static final double BYTES_PER_SYMBOL_CEILING = 200.0;

	/** Resident bytes the idle MCP server may hold. */
	private static final long IDLE_RESIDENT_CEILING = 160L * 1024L * 1024L;

	/** Resident bytes the MCP server may hold after the request burst. */
	private static final long BURST_RESIDENT_CEILING = 224L * 1024L * 1024L;

	/**
	 * Wall clock ceiling for the burst of eight requests. It is a hang
	 * detector, not a speed budget: the guard against a lost answer is
	 * the number of answers, not the time they took.
	 */
	private static final long BURST_CEILING_MILLIS = 15_000L;

	/** Milliseconds one request may take before the test gives up. */
	private static final long REQUEST_CEILING_MILLIS = 20_000L;

	/** Milliseconds the initialize handshake may take. */
	private static final long INITIALIZE_CEILING_MILLIS = 60_000L;

	/** The smallest number of sources the walk may find. */
	private static final int MINIMUM_SOURCES = 80;

	/** Directory names the source walk never enters. */
	private static final Set<String> EXCLUDED_DIRECTORIES = Set.of("target", ".tmp", ".git", ".idea");

	/** The JVM flags the shipped server runs with. */
	private static final List<String> SHIPPED_FLAGS = List.of(
			"-Xms32m", "-Xmx96m", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=10", "-Xss256k",
			"-XX:MaxMetaspaceSize=64m", "-XX:ReservedCodeCacheSize=16m",
			"-XX:ActiveProcessorCount=2", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=2");

	/** The state directory the tool keeps its index in. */
	private static final String STATE_DIRECTORY = ".importtruth";

	/** The index file name suffix the current format writes. */
	private static final String INDEX_SUFFIX = ".idx";

	@TempDir
	private Path scratch;

	@Test
	@DisplayName("checks every source of the repository inside the time budget")
	@Timeout(value = 900, unit = TimeUnit.SECONDS)
	void checksEveryRepositorySourceInsideTheTimeBudget() throws Exception {
		Path root = repositoryRoot();
		List<Path> sources = repositorySources(root);
		assertThat(sources).as("sources found under " + root).hasSizeGreaterThanOrEqualTo(MINIMUM_SOURCES);

		Path jar = builtJar();
		// The first run builds the index and warms the file cache. The
		// second run is the warm path the budget is about.
		CheckResult warmUp = runCheck(jar, root, sources);
		assertThat(warmUp.exitCode()).as("warm up exit code; stderr: %s", warmUp.stderr()).isZero();
		assertThat(warmUp.output()).as("warm up findings").isEmpty();

		CheckResult measured = runCheck(jar, root, sources);
		assertThat(measured.exitCode()).as("exit code; stderr: %s", measured.stderr()).isZero();
		assertThat(measured.output()).as("findings").isEmpty();
		System.out.println("budget: the check of " + sources.size() + " sources took "
				+ measured.millis() + " ms, ceiling " + CHECK_CEILING_MILLIS + " ms");
		assertThat(measured.millis()).as("warm check wall clock in milliseconds")
				.isLessThan(CHECK_CEILING_MILLIS);
	}

	@Test
	@DisplayName("keeps the index inside the byte budget per symbol")
	@Timeout(value = 900, unit = TimeUnit.SECONDS)
	void keepsTheIndexInsideTheByteBudgetPerSymbol() throws Exception {
		Path indexDirectory = stateDirectory().resolve("index");
		ensureIndexExists();

		long bytes = 0;
		long symbols = 0;
		int libraries = 0;
		try (JarIndexStore store = new JarIndexStore(indexDirectory)) {
			// Opening the store prunes the indexes of older formats, so
			// the listing that follows holds the current index only.
			for (Path file : indexFiles(indexDirectory)) {
				bytes += Files.size(file);
				// An empty prefix matches every recorded name, so the
				// rows that come back are the symbols the tool recorded.
				symbols += store.searchPrefix(file, "", Integer.MAX_VALUE).size();
				libraries++;
			}
		}
		assertThat(libraries).as("index files under " + indexDirectory).isPositive();
		assertThat(symbols).as("symbols in the index").isPositive();
		double perSymbol = (double) bytes / (double) symbols;
		System.out.println("budget: the index of " + libraries + " libraries holds " + symbols
				+ " symbols in " + bytes + " bytes, " + perSymbol
				+ " bytes per symbol, ceiling " + BYTES_PER_SYMBOL_CEILING);
		assertThat(perSymbol).as("bytes per symbol on disk")
				.isLessThanOrEqualTo(BYTES_PER_SYMBOL_CEILING);
	}

	@Test
	@DisplayName("keeps the resident memory of the MCP server inside the budget")
	@Timeout(value = 600, unit = TimeUnit.SECONDS)
	void keepsTheResidentMemoryOfTheServerInsideTheBudget() throws Exception {
		Path jar = builtJar();
		Path errors = scratch.resolve("memory-server.err");
		Server server = Server.start(jar, errors);
		try (server) {
			server.send(initializeRequest());
			String answer = server.await(1, INITIALIZE_CEILING_MILLIS);
			assertThat(answer).as("the initialize answer; server stderr: %s", readText(errors)).isNotNull();
			server.send(initializedNotification());

			long resident = peakResidentBytes(server.pid());

			System.out.println("budget: the idle server holds " + resident
					+ " resident bytes, ceiling " + IDLE_RESIDENT_CEILING);
			assertThat(resident).as("idle resident bytes of the server")
					.isLessThan(IDLE_RESIDENT_CEILING);
		}
		assertThat(server.alive()).as("the server process was stopped").isFalse();
	}

	@Test
	@DisplayName("answers eight concurrent requests and keeps the session alive")
	@Timeout(value = 600, unit = TimeUnit.SECONDS)
	void answersEightConcurrentRequestsAndKeepsTheSessionAlive() throws Exception {
		Path jar = builtJar();
		Path root = repositoryRoot();
		Path errors = scratch.resolve("burst-server.err");
		Server server = Server.start(jar, errors);
		try (server) {
			server.send(initializeRequest());
			String answer = server.await(1, INITIALIZE_CEILING_MILLIS);
			assertThat(answer).as("the initialize answer; server stderr: %s", readText(errors)).isNotNull();
			server.send(initializedNotification());

			// The requests are written without waiting, so the server
			// has to answer them while they are all in flight.
			long start = System.nanoTime();
			for (int i = 0; i < 8; i++) {
				server.send(lookupRequest(100 + i, root, "com.example.Zeta"));
			}
			Set<Integer> answered = server.awaitIds(8, BURST_CEILING_MILLIS);
			long elapsed = (System.nanoTime() - start) / 1_000_000L;

			System.out.println("budget: the burst answered " + answered.size()
					+ " of 8 requests in " + elapsed + " ms, ceiling " + BURST_CEILING_MILLIS + " ms");
			assertThat(answered).as("the request ids that answered").hasSize(8);
			assertThat(elapsed).as("burst wall clock in milliseconds").isLessThan(BURST_CEILING_MILLIS);

			server.send(lookupRequest(200, root, "org.junit.jupiter.api.Assertions"));
			String after = server.await(200, REQUEST_CEILING_MILLIS);
			assertThat(after).as("the answer after the burst").contains("org.junit.jupiter.api.Assertions");
			assertThat(server.alive()).as("the session is still alive").isTrue();

			long resident = peakResidentBytes(server.pid());
			System.out.println("budget: the server holds " + resident
					+ " resident bytes after the burst, ceiling " + BURST_RESIDENT_CEILING);
			assertThat(resident).as("resident bytes after the burst")
					.isLessThan(BURST_RESIDENT_CEILING);
		}
		assertThat(server.alive()).as("the server process was stopped").isFalse();
	}

	/**
	 * Runs the CLI check over the given sources and measures the child.
	 *
	 * @param jar     the built module jar, never null
	 * @param root    the project root, never null
	 * @param sources the files to check, never null
	 * @return what the run produced, never null
	 * @throws IOException          when the child cannot be started or read
	 * @throws InterruptedException when the wait for the child is interrupted
	 */
	private CheckResult runCheck(Path jar, Path root, List<Path> sources)
			throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		command.addAll(SHIPPED_FLAGS);
		command.add("-jar");
		command.add(jar.toString());
		command.add("check");
		command.add(root.toString());
		for (Path source : sources) {
			command.add(source.toString());
		}
		Path errors = Files.createTempFile(scratch, "check-", ".err");
		long start = System.nanoTime();
		Process process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
		String output;
		try (var stream = process.getInputStream()) {
			output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		int exitCode = process.waitFor();
		long millis = (System.nanoTime() - start) / 1_000_000L;
		return new CheckResult(exitCode, output, readText(errors), millis);
	}

	/**
	 * Builds the index when the machine has none, the way a user's
	 * first run does.
	 *
	 * @throws Exception when the index cannot be built
	 */
	private void ensureIndexExists() throws Exception {
		Path indexDirectory = stateDirectory().resolve("index");
		if (!indexFiles(indexDirectory).isEmpty()) {
			return;
		}
		Path root = repositoryRoot();
		runCheck(builtJar(), root, repositorySources(root));
	}

	/**
	 * Samples the resident bytes of a process and keeps the largest
	 * sample, because the working set grows a little after a start.
	 *
	 * @param pid the process to sample, positive
	 * @return the largest resident size seen, in bytes
	 * @throws IOException          when the process cannot be sampled
	 * @throws InterruptedException when the wait for the sampler is interrupted
	 */
	private static long peakResidentBytes(long pid) throws IOException, InterruptedException {
		long peak = 0;
		for (int i = 0; i < 3; i++) {
			peak = Math.max(peak, residentBytes(pid));
			Thread.sleep(250L);
		}
		return peak;
	}

	/**
	 * Reads the resident size of a process.
	 *
	 * @param pid the process to read, positive
	 * @return the resident size in bytes
	 * @throws IOException          when the process cannot be read
	 * @throws InterruptedException when the wait for the reader is interrupted
	 */
	private static long residentBytes(long pid) throws IOException, InterruptedException {
		if (isWindows()) {
			return windowsResidentBytes(pid);
		}
		return unixResidentBytes(pid);
	}

	/**
	 * Reads the working set of a Windows process from tasklist.
	 *
	 * @param pid the process to read, positive
	 * @return the working set in bytes
	 * @throws IOException          when the process cannot be read
	 * @throws InterruptedException when the wait for tasklist is interrupted
	 */
	private static long windowsResidentBytes(long pid) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("tasklist.exe", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")
				.redirectErrorStream(true)
				.start();
		String output;
		try (var stream = process.getInputStream()) {
			output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		process.waitFor();
		// The row is a CSV line: the fields are quoted, the separator is
		// a quote, a comma and a quote, and the memory field carries
		// its own thousands separator.
		String separator = "\",\"";
		for (String line : output.split("\\R")) {
			if (!line.contains(separator + pid + separator)) {
				continue;
			}
			int at = line.lastIndexOf(separator);
			if (at < 0) {
				continue;
			}
			String digits = line.substring(at + separator.length()).replaceAll("[^0-9]", "");
			if (!digits.isEmpty()) {
				return Long.parseLong(digits) * 1024L;
			}
		}
		throw new IOException("tasklist reported no memory for pid " + pid + ": " + output);
	}

	/**
	 * Reads the resident size of a Unix process from ps.
	 *
	 * @param pid the process to read, positive
	 * @return the resident size in bytes
	 * @throws IOException          when the process cannot be read
	 * @throws InterruptedException when the wait for ps is interrupted
	 */
	private static long unixResidentBytes(long pid) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid)).start();
		String output;
		try (var stream = process.getInputStream()) {
			output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
		process.waitFor();
		return Long.parseLong(output) * 1024L;
	}

	/**
	 * Finds the module directory of this build.
	 *
	 * @return the directory holding the importtruth-cli pom, never null
	 */
	private static Path moduleDirectory() {
		Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
		while (current != null) {
			Path pom = current.resolve("pom.xml");
			if (Files.isRegularFile(pom) && "importtruth-cli".equals(artifactId(pom))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException(
				"The importtruth-cli module was not found from " + System.getProperty("user.dir"));
	}

	/**
	 * Finds the repository root, the directory that holds the modules.
	 *
	 * @return the repository root, never null
	 */
	private static Path repositoryRoot() {
		Path root = moduleDirectory().getParent();
		if (root == null) {
			throw new IllegalStateException("The repository root was not found");
		}
		return root;
	}

	/**
	 * Reads the artifact id of a pom.
	 *
	 * @param pom the pom file, never null
	 * @return the artifact id, empty when it cannot be read
	 */
	private static String artifactId(Path pom) {
		try {
			return Pom.read(pom).artifactId();
		} catch (IOException unreadable) {
			return "";
		}
	}

	/**
	 * Reads the version of this module, taken from its pom rather than
	 * written down here.
	 *
	 * @return the project version, never null
	 * @throws IOException when the pom cannot be read
	 */
	private static String projectVersion() throws IOException {
		Path module = moduleDirectory();
		Pom pom = Pom.read(module.resolve("pom.xml"));
		String version = pom.version();
		if (!version.contains("${")) {
			return version;
		}
		String name = version.substring(version.indexOf("${") + 2, version.indexOf('}'));
		String resolved = pom.properties().get(name);
		Path rootPom = module.getParent() == null ? null : module.getParent().resolve("pom.xml");
		if (resolved == null && rootPom != null && Files.isRegularFile(rootPom)) {
			resolved = Pom.read(rootPom).properties().get(name);
		}
		if (resolved == null) {
			throw new IOException("The version " + version + " of the module is not a literal");
		}
		return resolved;
	}

	/**
	 * Locates the jar this module builds.
	 *
	 * @return the built jar, never null
	 * @throws IOException when the jar was not built
	 */
	private static Path builtJar() throws IOException {
		Path jar = moduleDirectory().resolve("target")
				.resolve("importtruth-cli-" + projectVersion() + ".jar");
		if (!Files.isRegularFile(jar)) {
			throw new IOException("The module jar was not built: " + jar);
		}
		return jar;
	}

	/**
	 * Lists every source file of the repository, leaving out the build
	 * output and the scratch directories.
	 *
	 * @param root the repository root, never null
	 * @return the sources, sorted by path, never null
	 * @throws IOException when the tree cannot be walked
	 */
	private static List<Path> repositorySources(Path root) throws IOException {
		List<Path> sources = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
				String name = directory.getFileName() == null ? "" : directory.getFileName().toString();
				if (EXCLUDED_DIRECTORIES.contains(name)) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				if (file.getFileName().toString().endsWith(".java")) {
					sources.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		sources.sort(Path::compareTo);
		return sources;
	}

	/**
	 * Lists the index files the tool wrote.
	 *
	 * @param indexDirectory the index directory, never null
	 * @return the index files, sorted by path, never null
	 * @throws IOException when the directory cannot be listed
	 */
	private static List<Path> indexFiles(Path indexDirectory) throws IOException {
		if (!Files.isDirectory(indexDirectory)) {
			return List.of();
		}
		try (var files = Files.list(indexDirectory)) {
			return files.filter(Files::isRegularFile)
					.filter(file -> file.getFileName().toString().endsWith(INDEX_SUFFIX))
					.sorted()
					.toList();
		}
	}

	/**
	 * Locates the state directory the tool keeps its index in.
	 *
	 * @return the state directory, never null
	 */
	private static Path stateDirectory() {
		return Paths.get(System.getProperty("user.home"), STATE_DIRECTORY);
	}

	/**
	 * The java executable of the JVM that runs this test.
	 *
	 * @return the path of the executable, never null
	 */
	private static String javaExecutable() {
		Path executable = Paths.get(System.getProperty("java.home"), "bin",
				isWindows() ? "java.exe" : "java");
		return Files.isRegularFile(executable) ? executable.toString() : "java";
	}

	/**
	 * Tells whether this machine runs Windows.
	 *
	 * @return true on Windows
	 */
	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
	}

	/**
	 * Reads a file as text.
	 *
	 * @param file the file, never null
	 * @return the content, empty when the file does not exist
	 * @throws IOException when the file cannot be read
	 */
	private static String readText(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return "";
		}
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	/**
	 * Builds the initialize request of the protocol handshake.
	 *
	 * @return the request line, never null
	 */
	private static String initializeRequest() {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
				+ "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"budget\",\"version\":\"1\"}}}";
	}

	/**
	 * Builds the notification that ends the protocol handshake.
	 *
	 * @return the notification line, never null
	 */
	private static String initializedNotification() {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
	}

	/**
	 * Builds a lookup request.
	 *
	 * @param id     the request id, positive
	 * @param root   the project root, never null
	 * @param symbol the symbol to look up, never null
	 * @return the request line, never null
	 */
	private static String lookupRequest(int id, Path root, String symbol) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"lookup_symbol\",\"arguments\":{\"projectPath\":\"" + jsonText(root.toString())
				+ "\",\"symbol\":\"" + jsonText(symbol) + "\"}}}";
	}

	/**
	 * Escapes a value for a JSON string.
	 *
	 * @param value the value, never null
	 * @return the escaped value, never null
	 */
	private static String jsonText(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * Reads the request id out of a response line.
	 *
	 * @param line the response line, never null
	 * @return the id, or -1 when the line carries none
	 */
	private static int responseId(String line) {
		int at = line.indexOf("\"id\":");
		if (at < 0) {
			return -1;
		}
		int from = at + 5;
		int to = from;
		while (to < line.length() && Character.isDigit(line.charAt(to))) {
			to++;
		}
		return to == from ? -1 : Integer.parseInt(line.substring(from, to));
	}

	/**
	 * What one CLI check produced.
	 *
	 * @param exitCode the exit code of the child
	 * @param output   what the child wrote to standard output
	 * @param stderr   what the child wrote to standard error
	 * @param millis   the wall clock of the child in milliseconds
	 */
	private record CheckResult(int exitCode, String output, String stderr, long millis) {
	}

	/**
	 * The pom of one module, read as far as the budget needs it.
	 *
	 * @param artifactId  the artifact id
	 * @param version     the version, the parent's when there is one
	 * @param properties  the declared properties
	 */
	private record Pom(String artifactId, String version, Map<String, String> properties) {

		/**
		 * Reads a pom.
		 *
		 * @param file the pom file, never null
		 * @return the pom, never null
		 * @throws IOException when the pom cannot be read
		 */
		static Pom read(Path file) throws IOException {
			try {
				DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
				factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
				factory.setXIncludeAware(false);
				factory.setExpandEntityReferences(false);
				Document document = factory.newDocumentBuilder().parse(file.toFile());
				Element project = document.getDocumentElement();
				Element parent = child(project, "parent");
				Element holder = parent == null ? project : parent;
				Map<String, String> properties = new LinkedHashMap<>();
				Element declared = child(project, "properties");
				if (declared != null) {
					for (Element property : children(declared)) {
						properties.put(property.getTagName(), property.getTextContent().trim());
					}
				}
				return new Pom(text(child(project, "artifactId")),
						text(child(holder, "version")),
						properties);
			} catch (ParserConfigurationException | SAXException failure) {
				throw new IOException("Cannot read " + file, failure);
			}
		}

		private static Element child(Element parent, String name) {
			if (parent == null) {
				return null;
			}
			NodeList nodes = parent.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				Node node = nodes.item(i);
				if (node instanceof Element element && name.equals(element.getTagName())) {
					return element;
				}
			}
			return null;
		}

		private static List<Element> children(Element parent) {
			List<Element> elements = new ArrayList<>();
			NodeList nodes = parent.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				if (nodes.item(i) instanceof Element element) {
					elements.add(element);
				}
			}
			return elements;
		}

		private static String text(Element element) {
			return element == null ? "" : element.getTextContent().trim();
		}
	}

	/**
	 * The MCP server running as a child process, spoken to over its
	 * standard input and standard output.
	 */
	private static final class Server implements AutoCloseable {

		/** The marker that tells the reader the pipe is closed. */
		private static final String END = " end-of-answers";

		private final Process process;
		private final BufferedWriter requests;
		private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();

		private Server(Process process) {
			this.process = process;
			this.requests = new BufferedWriter(
					new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
			Thread reader = new Thread(this::readAnswers, "importtruth-budget-reader");
			reader.setDaemon(true);
			reader.start();
		}

		/**
		 * Starts the server.
		 *
		 * @param jar    the built module jar, never null
		 * @param errors the file the server's standard error goes to, never null
		 * @return the running server, never null
		 * @throws IOException when the server cannot be started
		 */
		static Server start(Path jar, Path errors) throws IOException {
			List<String> command = new ArrayList<>();
			command.add(javaExecutable());
			command.addAll(SHIPPED_FLAGS);
			command.add("-jar");
			command.add(jar.toString());
			Process process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
			return new Server(process);
		}

		private void readAnswers() {
			try (BufferedReader lines = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = lines.readLine()) != null) {
					answers.offer(line);
				}
			} catch (IOException closed) {
				// The pipe closed, so the server stopped.
			}
			answers.offer(END);
		}

		/**
		 * Sends one request.
		 *
		 * @param request the request line, never null
		 * @throws IOException when the request cannot be written
		 */
		void send(String request) throws IOException {
			requests.write(request);
			requests.write("\n");
			requests.flush();
		}

		/**
		 * Waits for the answer to one request id.
		 *
		 * @param id     the request id
		 * @param millis how long to wait
		 * @return the answer line, or null when it did not come
		 * @throws InterruptedException when the wait is interrupted
		 */
		String await(int id, long millis) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
			while (true) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					return null;
				}
				String line = answers.poll(left, TimeUnit.NANOSECONDS);
				if (line == null || END.equals(line)) {
					return null;
				}
				if (responseId(line) == id) {
					return line;
				}
			}
		}

		/**
		 * Waits for answers to any request ids.
		 *
		 * @param count how many distinct ids to wait for, positive
		 * @param millis how long to wait
		 * @return the ids that answered, never null
		 * @throws InterruptedException when the wait is interrupted
		 */
		Set<Integer> awaitIds(int count, long millis) throws InterruptedException {
			Set<Integer> ids = new LinkedHashSet<>();
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
			while (ids.size() < count) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					break;
				}
				String line = answers.poll(left, TimeUnit.NANOSECONDS);
				if (line == null || END.equals(line)) {
					break;
				}
				int id = responseId(line);
				if (id >= 0) {
					ids.add(id);
				}
			}
			return ids;
		}

		/**
		 * The process id of the server.
		 *
		 * @return the process id
		 */
		long pid() {
			return process.pid();
		}

		/**
		 * Tells whether the server is still running.
		 *
		 * @return true while the process lives
		 */
		boolean alive() {
			return process.isAlive();
		}

		@Override
		public void close() {
			try {
				requests.close();
			} catch (IOException ignored) {
				// The server is going away anyway.
			}
			process.destroyForcibly();
			try {
				process.waitFor(15, TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}
}

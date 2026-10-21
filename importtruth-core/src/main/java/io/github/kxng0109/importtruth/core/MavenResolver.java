package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.Sha256;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Resolves a Maven project's exact dependency jars through the wrapper,
 * offline first, with a file cache keyed by build-file contents. Each module
 * resolves separately and the union is returned, so multi-module projects
 * never collapse to one arbitrary module.
 */
public final class MavenResolver implements DependencyResolver {

	/**
	 * Maximum pom-search depth. Module trees deeper than this are
	 * pathological; the crawl stops instead of trapping the server.
	 */
	static final int MAX_POM_DEPTH = 8;

	/**
	 * Fully qualified goal: short prefixes fail on machines without a warm
	 * plugin cache. Pinned version verified 2026-10-05.
	 */
	static final String BUILD_CLASSPATH =
			"org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath";

	private final Path stateDir;
	private final Map<Path, MemEntry> memory = new ConcurrentHashMap<>();
	private final Map<Path, CompletableFuture<List<Path>>> inFlight = new ConcurrentHashMap<>();

	/**
	 * How long a caller waits for a resolution already in flight before
	 * it gives up. Not final: a test shortens it to reach the timeout.
	 */
	Duration followerWait = Duration.ofMinutes(10);

	/**
	 * Runs before the leader performs the work. Not final: a test
	 * injects the failure the real resolver cannot reach on demand.
	 */
	Runnable beforeResolve = () -> { };

	/**
	 * Runs when a caller joins a resolution already in flight. Not
	 * final: a test observes the follower path.
	 */
	Runnable followerJoined = () -> { };

	/**
	 * Creates the resolver.
	 *
	 * @param stateDir directory holding the resolve cache, never null
	 * @throws IOException when the cache directory cannot be created
	 * @throws NullPointerException when {@code stateDir} is {@code null}
	 */
	public MavenResolver(Path stateDir) throws IOException {
		Objects.requireNonNull(stateDir, "stateDir");
		Files.createDirectories(stateDir.resolve("resolve"));
		this.stateDir = stateDir;
	}

	/**
	 * Returns the union of all modules' resolved jar files.
	 *
	 * @param projectDir  project root, never null
	 * @param allowNetwork true to retry online when offline resolution fails
	 * @return existing jar files, never null
	 * @throws IOException when any module fails or the union is empty
	 */
	@Override
	public List<Path> resolve(Path projectDir, boolean allowNetwork) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Path key = projectDir.toAbsolutePath().normalize();
		// One resolution at a time per project. Without this, a burst
		// of cold requests forks Maven once per request.
		CompletableFuture<List<Path>> pending = new CompletableFuture<>();
		CompletableFuture<List<Path>> started = inFlight.putIfAbsent(key, pending);
		if (started != null) {
			followerJoined.run();
			return awaitLeader(projectDir, started);
		}
		try {
			beforeResolve.run();
			List<Path> jars = resolveOnce(projectDir, allowNetwork, key);
			pending.complete(jars);
			return jars;
		} catch (Throwable failure) {
			// Every failure completes the shared future, an Error
			// included: a follower that waits on it must never wait
			// for a leader that died.
			pending.completeExceptionally(failure);
			if (failure instanceof IOException io) {
				throw io;
			}
			if (failure instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (failure instanceof Error error) {
				throw error;
			}
			throw new IOException("Resolution failed for " + projectDir, failure);
		} finally {
			inFlight.remove(key, pending);
		}
	}

	/**
	 * Waits for the leader of a resolution already in flight. The wait
	 * is bounded: a leader that dies without completing the shared
	 * future must not trap the followers.
	 *
	 * @param projectDir project root, never null
	 * @param started    the shared future, never null
	 * @return the jars the leader resolved, never null
	 * @throws IOException when the leader failed or the wait expired
	 */
	private List<Path> awaitLeader(Path projectDir, CompletableFuture<List<Path>> started) throws IOException {
		try {
			return started.get(followerWait.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException expired) {
			throw new IOException("Timed out after " + followerWait.toMillis()
					+ " ms waiting for the in-flight resolution of " + projectDir, expired);
		} catch (ExecutionException failure) {
			Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
			throw new IOException("Resolution failed for " + projectDir, cause);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted waiting for the resolution of " + projectDir, interrupted);
		}
	}

	private List<Path> resolveOnce(Path projectDir, boolean allowNetwork, Path key) throws IOException {
		MemEntry remembered = memory.get(key);
		if (remembered != null) {
			try {
				if (pomStats(projectDir).equals(remembered.poms())) {
					return remembered.jars();
				}
			} catch (IOException unreadable) {
				// Fall through to the authoritative hashed path below.
			}
		}
		String hash = hashPoms(projectDir);
		Path cached = stateDir.resolve("resolve").resolve(hash + ".cp");
		if (Files.exists(cached)) {
			try {
				// The cache is published atomically, so an empty file
				// means the project truly has no dependencies.
				List<Path> jars = readClasspath(cached);
				remember(key, projectDir, hash, jars);
				return jars;
			} catch (IOException stale) {
				Files.deleteIfExists(cached);
			}
		}
		Set<Path> union = new LinkedHashSet<>();
		List<String> failures = new ArrayList<>();
		boolean tooling = false;
		boolean projectMissing = false;
		String firstTail = null;
		for (Path module : modulesOf(projectDir)) {
			// The scratch file lives in the state directory so the
			// project tree is never written to. A path with a space
			// would break the command line on Windows, so such a
			// machine keeps the scratch next to the module.
			Path out = scratchFile(module, hash);
			Files.deleteIfExists(out);
			ResolveOutcome outcome = runBuildClasspath(projectDir, module, out, true);
			boolean missingTooling = !outcome.ok() && isToolingFailure(outcome.tail());
			if (!outcome.ok() && retryOnline(allowNetwork, outcome.tail())) {
				Files.deleteIfExists(out);
				outcome = runBuildClasspath(projectDir, module, out, false);
			}
			if (!outcome.ok()) {
				failures.add(moduleFailure(module.getFileName().toString(), outcome.launcher(), outcome.tail()));
				tooling |= missingTooling;
				projectMissing |= isProjectDependencyMissing(outcome.tail());
				if (firstTail == null) {
					firstTail = outcome.tail();
				}
				Files.deleteIfExists(out);
				continue;
			}
			union.addAll(readClasspath(out));
			Files.deleteIfExists(out);
		}
		List<Path> jars = List.copyOf(union);
		if (!failures.isEmpty()) {
			throw new IOException("No dependencies resolved for " + projectDir + ": "
					+ String.join(" | ", failures) + failureHint(tooling, projectMissing, firstTail));
		}
		Path staged = stateDir.resolve("resolve").resolve(hash + "." + Thread.currentThread().threadId()
				+ "." + System.nanoTime() + ".tmp");
		try {
			Files.writeString(staged, joinClasspath(jars), StandardCharsets.UTF_8);
			Files.move(staged, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(staged);
		}
		remember(key, projectDir, hash, jars);
		return jars;
	}

	/**
	 * Picks where the classpath scratch file lives. The state
	 * directory is preferred, so the project tree stays untouched.
	 * A path with a space would split the command line on Windows,
	 * so such a machine keeps the scratch beside the module.
	 *
	 * @param module module being resolved, never null
	 * @param hash   resolution hash, never null
	 * @return the scratch path, never null
	 * @throws IOException when the state directory cannot be created
	 */
	private Path scratchFile(Path module, String hash) throws IOException {
		Path dir = stateDir.resolve("resolve").resolve("tmp");
		Files.createDirectories(dir);
		String name = ".importtruth-" + hash + "." + Thread.currentThread().threadId()
				+ "." + System.nanoTime() + ".cp.tmp";
		Path candidate = dir.resolve(name);
		if (!candidate.toString().contains(" ")) {
			return candidate;
		}
		return module.resolve(name);
	}

	/**
	 * Renders the output property for the scratch file. A file in the
	 * state directory needs its absolute path; one beside the module
	 * is resolved by Maven against the module directory.
	 *
	 * @param out    scratch file, never null
	 * @param module module being resolved, never null
	 * @return the property value, never null
	 */
	private static String outputArgument(Path out, Path module) {
		if (out.getParent().equals(module)) {
			return out.getFileName().toString();
		}
		return out.toAbsolutePath().toString();
	}

	/**
	 * Remembers a resolution. Entries hold paths plus numbers, one per
	 * distinct project directory, so long sessions stay flat.
	 */
	private void remember(Path key, Path projectDir, String hash, List<Path> jars) throws IOException {
		if (memory.size() >= 128) {
			memory.clear();
		}
		memory.put(key, new MemEntry(pomStats(projectDir), hash, jars));
	}

	private static List<PomStat> pomStats(Path projectDir) throws IOException {
		List<PomStat> stats = new ArrayList<>();
		for (Path module : modulesOf(projectDir)) {
			Path pom = module.resolve("pom.xml");
			stats.add(new PomStat(pom, Files.getLastModifiedTime(pom).toMillis(), Files.size(pom)));
		}
		return stats;
	}

	private record MemEntry(List<PomStat> poms, String hash, List<Path> jars) {
	}

	private record PomStat(Path path, long modified, long size) {
	}

	/**
	 * Lists the modules of a project. The aggregator POM is the
	 * authority: a nested module lives deeper than a directory scan
	 * reaches, and its dependencies would otherwise be missing.
	 *
	 * @param projectDir project root, never null
	 * @return module directories, never null
	 * @throws IOException when the directory cannot be listed
	 */
	static List<Path> modulesOf(Path projectDir) throws IOException {
		List<Path> modules = new ArrayList<>();
		if (Files.exists(projectDir.resolve("pom.xml"))) {
			modules.add(projectDir);
		}
		List<Path> declared = declaredModules(projectDir);
		if (!declared.isEmpty()) {
			for (Path module : declared) {
				if (!modules.contains(module)) {
					modules.add(module);
				}
			}
			return modules;
		}
		try (Stream<Path> children = Files.list(projectDir)) {
			children
					.filter(Files::isDirectory)
					.map(child -> child.resolve("pom.xml"))
					.filter(Files::exists)
					.map(Path::getParent)
					.sorted()
					.forEach(modules::add);
		}
		return modules;
	}

	/**
	 * Reads the module list from the aggregator POM. A project
	 * without one falls back to the directory scan.
	 *
	 * @param projectDir project root, never null
	 * @return declared module directories, never null
	 */
	private static List<Path> declaredModules(Path projectDir) {
		Path pom = projectDir.resolve("pom.xml");
		if (!Files.isRegularFile(pom)) {
			return List.of();
		}
		try (InputStream in = Files.newInputStream(pom)) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			Document document = factory.newDocumentBuilder().parse(in);
			NodeList holders = document.getElementsByTagNameNS("*", "modules");
			List<Path> modules = new ArrayList<>();
			for (int i = 0; i < holders.getLength(); i++) {
				NodeList children = holders.item(i).getChildNodes();
				for (int j = 0; j < children.getLength(); j++) {
					Node child = children.item(j);
					if (child.getNodeType() != Node.ELEMENT_NODE) {
						continue;
					}
					String local = child.getLocalName() != null
							? child.getLocalName()
							: child.getNodeName();
					if (!"module".equals(local)) {
						continue;
					}
					Path module = projectDir.resolve(child.getTextContent().trim()).normalize();
					if (Files.isRegularFile(module)) {
						module = module.getParent();
					}
					if (Files.isRegularFile(module.resolve("pom.xml")) && !modules.contains(module)) {
						modules.add(module);
					}
				}
			}
			return modules;
		} catch (Exception unreadable) {
			return List.of();
		}
	}

	private String hashPoms(Path projectDir) throws IOException {
		List<Path> poms = new ArrayList<>();
		// Bounded crawl: deep or restricted trees (system directories,
		// runaway mounts) must never trap resolution. Denied subtrees
		// are skipped, depth is capped, links are not followed.
		Files.walkFileTree(projectDir, Set.of(), MAX_POM_DEPTH, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (file.getFileName().toString().equals("pom.xml")) {
					poms.add(file);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.SKIP_SUBTREE;
			}
		});
		poms.sort(null);
		StringBuilder seed = new StringBuilder();
		for (Path pom : poms) {
			seed.append(projectDir.relativize(pom)).append('\n');
			seed.append(Files.readString(pom, StandardCharsets.UTF_8)).append('\n');
		}
		return Sha256.ofBytes(seed.toString().getBytes(StandardCharsets.UTF_8));
	}

	private ResolveOutcome runBuildClasspath(Path projectDir, Path module, Path out, boolean offline)
			throws IOException {
		Launcher chosen = chooseLauncher(projectDir, module, isWindows(), systemLauncherAvailable());
		List<String> command = new ArrayList<>(chosen.command());
		// The output property is a bare filename: Maven resolves it
		// against the module directory, so it stays spaceless.
		command.addAll(List.of(
				"-f", argFor(projectDir, module.resolve("pom.xml")),
				"-B", "-ntp", "-q",
				"-Dmdep.outputFile=" + outputArgument(out, module),
				"-Dmdep.includeScope=test",
				BUILD_CLASSPATH));
		if (offline) {
			command.add("-o");
		}
		Process process;
		StringBuilder log = new StringBuilder();
		try {
			ProcessBuilder builder = new ProcessBuilder(command)
					.directory(projectDir.toFile())
					.redirectErrorStream(true);
			defaultChildHeap(builder.environment());
			process = builder.start();
		} catch (IOException failed) {
			return new ResolveOutcome(false, "cannot start " + command.get(0) + ": " + failed.getMessage(),
					chosen.description());
		}
		// Drain on a daemon thread: the blocking stream read must never
		// trap the worker before the interruptible wait below.
		Thread drain = new Thread(() -> drainTo(process.getInputStream(), log));
		drain.setDaemon(true);
		drain.start();
		boolean finished;
		try {
			finished = process.waitFor(120, TimeUnit.SECONDS);
			drain.join(10_000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted resolving " + module, interrupted);
		}
		if (!finished) {
			process.destroyForcibly();
			throw new IOException("Timed out resolving " + module);
		}
		String tail = log.length() > 500 ? log.substring(log.length() - 500) : log.toString();
		boolean ok = process.exitValue() == 0 && Files.exists(out);
		return new ResolveOutcome(ok, ok ? "" : ("exit=" + process.exitValue() + " " + tail.trim()),
				chosen.description());
	}

	/**
	 * Caps nested Maven heap plus collector unless the user already
	 * tunes the child JVM. Resolving classpaths never needs a gigabyte
	 * heap, and one collection thread keeps build bursts small.
	 *
	 * @param environment child environment, never null
	 */
	static void defaultChildHeap(Map<String, String> environment) {
		if (!environment.containsKey("MAVEN_OPTS")
				&& !environment.containsKey("JAVA_TOOL_OPTIONS")
				&& !environment.containsKey("JDK_JAVA_OPTIONS")) {
			environment.put("MAVEN_OPTS", "-Xms64m -Xmx512m -XX:+UseSerialGC");
		}
	}

	/**
	 * Tests whether a failed child tail means our own pinned tooling is
	 * missing from the local repository rather than a project problem.
	 * Only this shape earns an online retry: project dependency failures
	 * stay offline and loud.
	 *
	 * @param tail child output tail, possibly null
	 * @return true when the tail names the missing dependency plugin
	 */
	static boolean isToolingFailure(String tail) {
		if (tail == null || tail.isBlank()) {
			return false;
		}
		String compact = tail.toLowerCase(Locale.ROOT);
		if (isProjectDependencyMissing(tail)) {
			return false;
		}
		// The exception name in the help link is the stable signal.
		// The artifact line alone cannot tell our plugin from the
		// project's own dependencies, which read the same way.
		return compact.contains("pluginresolutionexception")
				|| (compact.contains("maven-dependency-plugin")
						&& (compact.contains("has not been downloaded")
								|| compact.contains("in offline mode")));
	}

	/**
	 * Tests whether a failed child tail means the project's own
	 * dependencies are absent from the local repository. That is the
	 * normal state of a project nobody has built here yet, including
	 * every fresh machine, so it earns its own advice.
	 *
	 * @param tail child output tail, possibly null
	 * @return true when the tail names an unresolved project artifact
	 */
	static boolean isProjectDependencyMissing(String tail) {
		if (tail == null || tail.isBlank()) {
			return false;
		}
		String compact = tail.toLowerCase(Locale.ROOT);
		return compact.contains("could not resolve dependencies for project")
				|| compact.contains("dependencyresolutionexception")
				|| compact.contains("the following artifacts could not be resolved");
	}

	/**
	 * Decides whether a failed offline attempt earns one online retry.
	 * Callers opting into network always retry; otherwise only a
	 * missing tooling signature retries, so project resolution stays
	 * hermetic. Extracted so the policy is unit testable without
	 * spawning Maven.
	 *
	 * @param allowNetwork caller opt-in to network use
	 * @param offlineTail failed offline child tail, possibly null
	 * @return true when the online retry should run
	 */
	static boolean retryOnline(boolean allowNetwork, String offlineTail) {
		return allowNetwork || isToolingFailure(offlineTail);
	}

	/**
	 * Picks the failure hint for an unresolvable project. A cold
	 * tooling cache names the warmup command; a project whose
	 * dependencies were never fetched names the build that fetches
	 * them; a missing project or an unwritable repository names
	 * itself. Anything else keeps the sibling snapshot hint.
	 *
	 * @param toolingMissing true when a module failed on missing tooling
	 * @param projectMissing true when a module failed on absent project dependencies
	 * @param tail child output tail, possibly null
	 * @return hint suffix starting with a space, never null
	 */
	static String failureHint(boolean toolingMissing, boolean projectMissing, String tail) {
		if (toolingMissing) {
			return " (hint: the Maven plugin cache looks cold; run one online build"
					+ " such as 'mvn -B -ntp compile' in the project, then retry)";
		}
		if (projectMissing) {
			return " (hint: the project's dependencies are not in the local Maven"
					+ " repository yet; run 'mvn -B -ntp install' once in the project,"
					+ " then retry)";
		}
		String compact = tail == null ? "" : tail.toLowerCase(Locale.ROOT);
		if (compact.contains("no pom in this directory")) {
			return " (hint: the project has no pom.xml; importtruth resolves Maven projects)";
		}
		if (compact.contains("could not create local repository")) {
			return " (hint: the Maven local repository could not be created;"
					+ " check the permissions of the .m2 folder)";
		}
		return " (hint: sibling snapshot modules may need 'mvn install' first)";
	}

	/**
	 * Renders the failure of one module. The launcher that ran is
	 * named, so a reader knows which Maven produced the output.
	 *
	 * @param module   the module name, never null
	 * @param launcher the launcher that ran, never null
	 * @param tail     the child output tail, never null
	 * @return the failure line, never null
	 */
	static String moduleFailure(String module, String launcher, String tail) {
		return module + ": " + launcher + ": " + tail;
	}

	/**
	 * The outcome of one child Maven run.
	 *
	 * @param ok       true when the classpath file was written
	 * @param tail     the child output tail, never null
	 * @param launcher the launcher that ran, never null
	 */
	private record ResolveOutcome(boolean ok, String tail, String launcher) {
	}

	/**
	 * Copies a process stream into the tail buffer, capping its size.
	 * Best effort: a torn-down stream ends the copy silently.
	 *
	 * @param in  stream to drain, never null
	 * @param log tail buffer, never null
	 */
	static void drainTo(InputStream in, StringBuilder log) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (log.length() < 2000) {
					log.append(line).append('\n');
				}
			}
		} catch (IOException ignored) {
			// Stream torn down with the process; the tail is best effort.
		}
	}

	/**
	 * Finds the wrapper of a project. The search never leaves the
	 * project root: a wrapper above it belongs to another project, and
	 * its properties file can point a download at an arbitrary URL.
	 *
	 * <p>The wrapper that is used is judged on the resolved paths, so a
	 * junction inside the project cannot surface a wrapper that really
	 * lives outside it. The path that is returned is the one that was
	 * asked for, not the resolved one.</p>
	 *
	 * @param module      module being resolved, never null
	 * @param projectRoot project root, never null
	 * @param windows     true on Windows
	 * @return the wrapper path, or null when the project has none
	 */
	static Path findWrapper(Path module, Path projectRoot, boolean windows) {
		Path root = projectRoot.toAbsolutePath().normalize();
		Path current = module.toAbsolutePath().normalize();
		while (current != null && current.startsWith(root)) {
			Path wrapper = current.resolve(windows ? "mvnw.cmd" : "mvnw");
			if (Files.exists(wrapper) && PathConfinement.contains(projectRoot, wrapper)) {
				return wrapper;
			}
			if (current.equals(root)) {
				break;
			}
			current = current.getParent();
		}
		return null;
	}

	/**
	 * Finds a wrapper in the module directory only. The project root
	 * bounds the search; without one, the module directory is the
	 * boundary.
	 *
	 * @param module  module being resolved, never null
	 * @param windows true on Windows
	 * @return the wrapper path, or null when there is none
	 */
	static Path findWrapper(Path module, boolean windows) {
		return findWrapper(module, module, windows);
	}

	/**
	 * Chooses the launcher for a project. The system Maven is the
	 * default: a project's wrapper properties file can point a
	 * download at an arbitrary URL, so a wrapper is used only when the
	 * system launcher is missing, and then only when the wrapper sits
	 * inside the project root the caller passed. A wrapper above that
	 * root belongs to another project and is never used.
	 *
	 * @param projectDir      project root, never null
	 * @param module          module being resolved, never null
	 * @param windows         true on Windows
	 * @param systemAvailable true when the system launcher was found
	 * @return the command and the reason it was chosen, never null
	 */
	static Launcher chooseLauncher(Path projectDir, Path module, boolean windows, boolean systemAvailable) {
		String system = windows ? "mvn.cmd" : "mvn";
		if (systemAvailable) {
			return new Launcher(List.of(system), "system " + system + " (available on PATH)");
		}
		Path wrapper = findWrapper(module, projectDir, windows);
		if (wrapper != null) {
			return new Launcher(launcher(projectDir, wrapper, windows),
					"project wrapper " + wrapper + " (no system " + system + " on PATH)");
		}
		return new Launcher(List.of(system),
				"system " + system + " (unavailable on PATH and no wrapper inside the project root)");
	}

	/**
	 * Looks for the system Maven launcher in the given directories.
	 *
	 * @param windows   true on Windows
	 * @param searchDirs directories to search, never null
	 * @return true when the launcher exists
	 */
	static boolean systemLauncherAvailable(boolean windows, List<Path> searchDirs) {
		String name = windows ? "mvn.cmd" : "mvn";
		for (Path dir : searchDirs) {
			if (dir == null) {
				continue;
			}
			try {
				if (Files.isRegularFile(dir.resolve(name))) {
					return true;
				}
			} catch (Exception invalid) {
				// An unreadable search entry is not a launcher.
			}
		}
		return false;
	}

	/**
	 * Looks for the system Maven launcher where this machine keeps it.
	 *
	 * @return true when the launcher exists
	 */
	private static boolean systemLauncherAvailable() {
		return systemLauncherAvailable(isWindows(), launcherSearchPath());
	}

	/**
	 * Lists the directories the system launcher may live in: the
	 * search path plus the Maven homes the user configured.
	 *
	 * @return candidate directories, never null
	 */
	private static List<Path> launcherSearchPath() {
		return launcherSearchPath(System.getenv("PATH"), System.getenv("MAVEN_HOME"), System.getenv("M2_HOME"));
	}

	/**
	 * Lists the directories the system launcher may live in. A
	 * malformed entry is skipped: one broken element of the search
	 * path must not hide the launcher.
	 *
	 * @param path      the raw search path, possibly null
	 * @param mavenHome the raw MAVEN_HOME, possibly null
	 * @param m2Home    the raw M2_HOME, possibly null
	 * @return candidate directories, never null
	 */
	static List<Path> launcherSearchPath(String path, String mavenHome, String m2Home) {
		List<Path> dirs = new ArrayList<>();
		addSearchEntries(dirs, path);
		for (String home : new String[] { mavenHome, m2Home }) {
			if (home == null || home.isBlank()) {
				continue;
			}
			try {
				dirs.add(Path.of(home.trim()).resolve("bin"));
			} catch (Exception invalid) {
				// A malformed home is not a launcher directory.
			}
		}
		return dirs;
	}

	/**
	 * Adds the entries of a search path variable to a list.
	 *
	 * @param dirs the list to add to, never null
	 * @param path the raw variable value, possibly null
	 */
	private static void addSearchEntries(List<Path> dirs, String path) {
		if (path == null || path.isBlank()) {
			return;
		}
		for (String entry : path.split(File.pathSeparator)) {
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			try {
				dirs.add(Path.of(trimmed));
			} catch (Exception invalid) {
				// A malformed entry is not a launcher directory.
			}
		}
	}
	/**
	 * The launcher command and the reason it was chosen. The reason
	 * names the launcher, so a failure says which Maven ran.
	 *
	 * @param command     the process command, never null
	 * @param description why this launcher was chosen, never null
	 */
	record Launcher(List<String> command, String description) {
	}

	/**
	 * Builds the process command. Paths go in relative to the
	 * project directory (which is also the child working directory)
	 * so {@code cmd /d /c} never sees a spaced token it would split.
	 * Absolute paths survive only above the project root.
	 */
	static List<String> launcher(Path projectDir, Path wrapper, boolean windows) {
		if (wrapper == null) {
			return List.of(windows ? "mvn.cmd" : "mvn");
		}
		String target = argFor(projectDir, wrapper.toAbsolutePath());
		if (windows) {
			return List.of("cmd", "/d", "/c", target);
		}
		return List.of("sh", target);
	}

	/**
	 * Renders {@code path} relative to {@code cwd} when contained,
	 * absolute otherwise. Relative tokens need no shell quoting.
	 */
	static String argFor(Path cwd, Path path) {
		Path base = cwd.toAbsolutePath().normalize();
		Path absolute = path.toAbsolutePath().normalize();
		if (absolute.startsWith(base)) {
			return base.relativize(absolute).toString();
		}
		return absolute.toString();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").startsWith("Windows");
	}

	private static String joinClasspath(List<Path> jars) {
		StringBuilder joined = new StringBuilder();
		for (Path jar : jars) {
			if (!joined.isEmpty()) {
				joined.append(File.pathSeparator);
			}
			joined.append(jar.toAbsolutePath());
		}
		return joined.toString();
	}

	/**
	 * Reads a classpath file. Every token must name an existing jar;
	 * a single stale entry fails the whole file so callers re-resolve
	 * instead of serving partial classpaths.
	 *
	 * @param file cache file, never null
	 * @return existing jars, empty when the file is missing or blank
	 * @throws IOException when a listed jar is missing or the file is unreadable
	 */
	static List<Path> readClasspath(Path file) throws IOException {
		Objects.requireNonNull(file, "file");
		if (!Files.exists(file)) {
			return List.of();
		}
		String content = Files.readString(file, StandardCharsets.UTF_8).trim();
		if (content.isEmpty()) {
			return List.of();
		}
		List<Path> jars = new ArrayList<>();
		for (String part : content.split(File.pathSeparator)) {
			Path jar = Path.of(part.trim());
			if (!jar.toString().endsWith(".jar") || !Files.exists(jar)) {
				throw new IOException("Stale cache entry: " + part.trim());
			}
			jars.add(jar);
		}
		return List.copyOf(jars);
	}
}

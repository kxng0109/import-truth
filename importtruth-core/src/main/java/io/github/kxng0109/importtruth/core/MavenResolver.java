package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.Sha256;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Resolves a Maven project's exact dependency jars through the wrapper,
 * offline first, with a file cache keyed by build-file contents. Each module
 * resolves separately and the union is returned, so multi-module projects
 * never collapse to one arbitrary module.
 */
public final class MavenResolver implements DependencyResolver {

	/**
	 * Fully qualified goal: short prefixes fail on machines without a warm
	 * plugin cache. Pinned version verified 2026-10-05.
	 */
	static final String BUILD_CLASSPATH =
			"org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath";

	private final Path stateDir;
	private final Map<Path, MemEntry> memory = new ConcurrentHashMap<>();

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
				List<Path> jars = readClasspath(cached);
				if (!jars.isEmpty()) {
					remember(key, projectDir, hash, jars);
					return jars;
				}
			} catch (IOException stale) {
				Files.deleteIfExists(cached);
			}
		}
		Set<Path> union = new LinkedHashSet<>();
		List<String> failures = new ArrayList<>();
		for (Path module : modulesOf(projectDir)) {
			// Maven resolves the output property against the module
			// directory, so the scratch file is a bare filename there:
			// spaceless, quoteless, and unique per attempt. A stale
			// sibling from a killed run is reclaimed first.
			sweepScratch(module);
			String scratch = ".importtruth-" + hash + "." + Thread.currentThread().threadId()
					+ "." + System.nanoTime() + ".cp.tmp";
			Path out = module.resolve(scratch);
			Files.deleteIfExists(out);
			ResolveOutcome outcome = runBuildClasspath(projectDir, module, out, true);
			if (!outcome.ok() && allowNetwork) {
				Files.deleteIfExists(out);
				outcome = runBuildClasspath(projectDir, module, out, false);
			}
			if (!outcome.ok()) {
				failures.add(module.getFileName() + ": " + outcome.tail());
				Files.deleteIfExists(out);
				continue;
			}
			union.addAll(readClasspath(out));
			Files.deleteIfExists(out);
		}
		List<Path> jars = List.copyOf(union);
		if (!failures.isEmpty()) {
			throw new IOException("No dependencies resolved for " + projectDir + ": "
					+ String.join(" | ", failures)
					+ " (hint: sibling snapshot modules may need 'mvn install' first)");
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
	 * Remembers a resolution. Entries hold paths plus numbers, one per
	 * distinct project directory, so long sessions stay flat.
	 */
	private void remember(Path key, Path projectDir, String hash, List<Path> jars) throws IOException {
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

	private static void sweepScratch(Path module) {
		try (Stream<Path> siblings = Files.list(module)) {
			for (Path sibling : siblings.filter(p -> {
				String name = p.getFileName().toString();
				return name.startsWith(".importtruth-") && name.endsWith(".cp.tmp");
			}).toList()) {
				Files.deleteIfExists(sibling);
			}
		} catch (IOException | RuntimeException ignored) {
			// Best effort: a leftover is overwritten or ignored below.
		}
	}
	private static List<Path> modulesOf(Path projectDir) throws IOException {
		List<Path> modules = new ArrayList<>();
		if (Files.exists(projectDir.resolve("pom.xml"))) {
			modules.add(projectDir);
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

	private String hashPoms(Path projectDir) throws IOException {
		List<Path> poms = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(projectDir)) {
			walk.filter(p -> p.getFileName().toString().equals("pom.xml")).sorted().forEach(poms::add);
		}
		StringBuilder seed = new StringBuilder();
		for (Path pom : poms) {
			seed.append(projectDir.relativize(pom)).append('\n');
			seed.append(Files.readString(pom, StandardCharsets.UTF_8)).append('\n');
		}
		return Sha256.ofBytes(seed.toString().getBytes(StandardCharsets.UTF_8));
	}

	private ResolveOutcome runBuildClasspath(Path projectDir, Path module, Path out, boolean offline)
			throws IOException {
		List<String> command = new ArrayList<>(launcher(projectDir, findWrapper(module)));
		// The output property is a bare filename: Maven resolves it
		// against the module directory, so it stays spaceless.
		command.addAll(List.of(
				"-f", argFor(projectDir, module.resolve("pom.xml")),
				"-B", "-ntp", "-q",
				"-Dmdep.outputFile=" + out.getFileName().toString(),
				"-Dmdep.includeScope=test",
				BUILD_CLASSPATH));
		if (offline) {
			command.add("-o");
		}
		Process process;
		StringBuilder log = new StringBuilder();
		try {
			process = new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true).start();
		} catch (IOException failed) {
			return new ResolveOutcome(false, "cannot start " + command.get(0) + ": " + failed.getMessage());
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
		return new ResolveOutcome(ok, ok ? "" : ("exit=" + process.exitValue() + " " + tail.trim()));
	}

	private record ResolveOutcome(boolean ok, String tail) {
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

	private static Path findWrapper(Path module) {
		return findWrapper(module, isWindows());
	}

	static Path findWrapper(Path module, boolean windows) {
		Path current = module.toAbsolutePath();
		while (current != null) {
			Path wrapper = current.resolve(windows ? "mvnw.cmd" : "mvnw");
			if (Files.exists(wrapper)) {
				return wrapper;
			}
			current = current.getParent();
		}
		return null;
	}

	private static List<String> launcher(Path projectDir, Path wrapper) {
		return launcher(projectDir, wrapper, isWindows());
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

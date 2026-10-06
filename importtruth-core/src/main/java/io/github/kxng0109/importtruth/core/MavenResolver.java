package io.github.kxng0109.importtruth.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
	 * @throws IOException when every module fails or the union is empty
	 */
	@Override
	public List<Path> resolve(Path projectDir, boolean allowNetwork) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		String hash = hashPoms(projectDir);
		Path cached = stateDir.resolve("resolve").resolve(hash + ".cp");
		if (Files.exists(cached)) {
			List<Path> jars = readClasspath(cached);
			if (!jars.isEmpty()) {
				return jars;
			}
		}
		Set<Path> union = new LinkedHashSet<>();
		List<String> failures = new ArrayList<>();
		int attempted = 0;
		for (Path module : modulesOf(projectDir)) {
			attempted++;
			Path out = stateDir.resolve("resolve").resolve(hash + "." + module.getFileName() + ".new");
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
		if (jars.isEmpty() && !failures.isEmpty() && failures.size() >= attempted) {
			throw new IOException(
					"No dependencies resolved for " + projectDir + ": " + String.join(" | ", failures));
		}
		Files.writeString(cached, joinClasspath(jars), StandardCharsets.UTF_8);
		return jars;
	}

	private List<Path> modulesOf(Path projectDir) throws IOException {
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
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(seed.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
	}

	private ResolveOutcome runBuildClasspath(Path projectDir, Path module, Path out, boolean offline)
			throws IOException {
		List<String> command = new ArrayList<>(launcher(findWrapper(module)));
		command.addAll(List.of(
				"-f", module.resolve("pom.xml").toAbsolutePath().toString(),
				"-B", "-ntp", "-q",
				"-Dmdep.outputFile=" + out.toAbsolutePath(),
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
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (log.length() < 2000) {
					log.append(line).append('\n');
				}
			}
		}
		boolean finished;
		try {
			finished = process.waitFor(120, TimeUnit.SECONDS);
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

	private static List<String> launcher(Path wrapper) {
		return launcher(wrapper, isWindows());
	}

	static List<String> launcher(Path wrapper, boolean windows) {
		if (wrapper == null) {
			return List.of(windows ? "mvn.cmd" : "mvn");
		}
		if (windows) {
			return List.of("cmd", "/d", "/c", wrapper.toAbsolutePath().toString());
		}
		return List.of("sh", wrapper.toAbsolutePath().toString());
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

	private static List<Path> readClasspath(Path file) throws IOException {
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
			if (jar.toString().endsWith(".jar") && Files.exists(jar)) {
				jars.add(jar);
			}
		}
		return List.copyOf(jars);
	}
}

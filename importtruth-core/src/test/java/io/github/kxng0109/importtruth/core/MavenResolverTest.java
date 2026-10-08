package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolves this very repository offline: no fixtures, real Maven, real cache.
 */
@DisplayName("MavenResolver")
final class MavenResolverTest {

	@TempDir
	private Path state;

	@Test
	@Tag("slow")
	@DisplayName("resolves fixture jars and reuses the cache")
	void resolvesFixtureJarsTwice() throws Exception {
		Path root = Files.createTempDirectory("fixture");
		try {
			copyWrapper(root);
			Files.write(root.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
							+ "<artifactId>root</artifactId><version>1</version><packaging>pom</packaging>"
							+ "<modules><module>lib</module></modules></project>")
							.getBytes(StandardCharsets.UTF_8));
			Path lib = root.resolve("lib");
			Files.createDirectories(lib);
			Files.write(lib.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
							+ "<artifactId>root</artifactId><version>1</version></parent>"
							+ "<artifactId>lib</artifactId><dependencies><dependency><groupId>org.junit.jupiter</groupId>"
							+ "<artifactId>junit-jupiter-api</artifactId><version>6.1.3</version>"
							+ "<scope>test</scope></dependency></dependencies></project>")
							.getBytes(StandardCharsets.UTF_8));
			MavenResolver resolver = new MavenResolver(state);

			List<Path> first = resolver.resolve(root, true);
			List<Path> second = resolver.resolve(root, true);

			assertThat(first).as("resolved jars").isNotEmpty();
			assertThat(first.stream().allMatch(p -> p.toString().endsWith(".jar") && Files.exists(p)))
					.as("existing jars only")
					.isTrue();
			assertThat(first.stream().anyMatch(p -> p.getFileName().toString().contains("junit-jupiter-api")))
					.as("contains the assertion library")
					.isTrue();
			assertThat(second).as("cached second run").isEqualTo(first);
			assertThat(second).as("same memory instance").isSameAs(first);
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@DisplayName("picks launchers per operating system")
	void picksLaunchers() throws Exception {
		Path dir = Files.createTempDirectory("launcher");
		try {
			Path script = dir.resolve("mvnw.cmd");
			Files.write(script, "x".getBytes(StandardCharsets.UTF_8));

			assertThat(MavenResolver.launcher(dir, null, true)).as("windows fallback")
					.containsExactly("mvn.cmd");
			assertThat(MavenResolver.launcher(dir, null, false)).as("unix fallback").containsExactly("mvn");
			assertThat(MavenResolver.launcher(dir, script, true))
					.as("windows wrapper relative")
					.containsExactly("cmd", "/d", "/c", "mvnw.cmd");
			assertThat(MavenResolver.launcher(dir, script, false))
					.as("unix wrapper relative")
					.containsExactly("sh", "mvnw.cmd");
			assertThat(MavenResolver.launcher(dir.resolve("elsewhere"), script, true).get(3))
					.as("outside wrapper stays absolute")
					.isEqualTo(script.toAbsolutePath().normalize().toString());
			assertThat(MavenResolver.findWrapper(dir, true)).as("windows wrapper").isEqualTo(script);
			assertThat(MavenResolver.findWrapper(dir.resolve("deep").resolve("nested"), false))
					.as("missing wrapper")
					.isNull();
		} finally {
			deleteTree(dir);
		}
	}

	@Test
	@DisplayName("renders paths relative to the project when contained")
	void relativizesArguments() throws Exception {
		Path root = Files.createTempDirectory("relargs");
		try {
			Path inside = root.resolve("m.cmd");
			Files.write(inside, "x".getBytes(StandardCharsets.UTF_8));

			assertThat(MavenResolver.argFor(root, inside)).as("contained relativized")
					.isEqualTo("m.cmd");
			assertThat(MavenResolver.argFor(root.resolve("sub"), inside)).as("sibling unrelativized")
					.isEqualTo(inside.toAbsolutePath().normalize().toString());
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@Tag("slow")
	@DisplayName("resolves projects whose paths contain spaces")
	void resolvesSpacedPaths() throws Exception {
		Path root = Files.createTempDirectory("spaced root");
		try {
			copyWrapper(root);
			Files.write(root.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
							+ "<artifactId>spaced</artifactId><version>1</version><packaging>pom</packaging>"
							+ "<modules><module>my lib</module></modules></project>")
							.getBytes(StandardCharsets.UTF_8));
			Path lib = root.resolve("my lib");
			Files.createDirectories(lib);
			Files.write(lib.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
							+ "<artifactId>spaced</artifactId><version>1</version></parent>"
							+ "<artifactId>lib</artifactId><dependencies><dependency><groupId>org.junit.jupiter</groupId>"
							+ "<artifactId>junit-jupiter-api</artifactId><version>6.1.3</version>"
							+ "<scope>test</scope></dependency></dependencies></project>")
							.getBytes(StandardCharsets.UTF_8));
			MavenResolver resolver = new MavenResolver(state);

			List<Path> jars = resolver.resolve(root, true);

			assertThat(jars.stream().anyMatch(p -> p.getFileName().toString().contains("junit-jupiter-api")))
					.as("spaced project resolved")
					.isTrue();
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@Tag("slow")
	@DisplayName("fails loudly on unresolvable projects")
	void failsLoudly() throws Exception {
		Path bare = Files.createTempDirectory("bare");
		copyWrapper(bare);
		Files.write(bare.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>bare</artifactId><version>1</version>"
						+ "<dependencies><dependency><groupId>com.example</groupId>"
						+ "<artifactId>does-not-exist</artifactId><version>1</version>"
						+ "</dependency></dependencies></project>").getBytes(StandardCharsets.UTF_8));
		MavenResolver resolver = new MavenResolver(state);

		try {
			assertThatThrownBy(() -> resolver.resolve(bare, true))
					.as("unresolvable fails")
					.isInstanceOf(IOException.class);
		} finally {
			deleteTree(bare);
		}
	}

	@Test
	@Tag("slow")
	@DisplayName("returns empty for dependency-free projects")
	void returnsEmptyForBareProjects() throws Exception {
		Path bare = Paths.get("target", "test-fixtures", "empty-deps");
		Files.createDirectories(bare);
		Files.write(bare.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>bare</artifactId><version>1</version></project>")
						.getBytes(StandardCharsets.UTF_8));
		MavenResolver resolver = new MavenResolver(state);

		assertThat(resolver.resolve(bare, true)).as("empty union").isEmpty();
	}

	@Test
	@DisplayName("reads cache files strictly")
	void readsCacheStrictly() throws Exception {
		Path missing = state.resolve("absent.cp");
		Path empty = state.resolve("empty.cp");
		Files.write(empty, new byte[0]);
		Path jar = state.resolve("x.jar");
		Files.write(jar, "bytes".getBytes(StandardCharsets.UTF_8));
		Path ok = state.resolve("ok.cp");
		Files.write(ok, jar.toString().getBytes(StandardCharsets.UTF_8));
		Path partial = state.resolve("partial.cp");
		Files.write(partial, (jar + File.pathSeparator + "nope.txt").getBytes(StandardCharsets.UTF_8));
		Path gone = state.resolve("gone.cp");
		Files.write(gone,
				(jar + File.pathSeparator + state.resolve("missing.jar")).getBytes(StandardCharsets.UTF_8));

		assertThat(MavenResolver.readClasspath(missing)).as("missing file").isEmpty();
		assertThat(MavenResolver.readClasspath(empty)).as("blank file").isEmpty();
		assertThat(MavenResolver.readClasspath(ok)).as("single jar").containsExactly(jar);
		assertThatThrownBy(() -> MavenResolver.readClasspath(partial)).as("non-jar token")
				.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> MavenResolver.readClasspath(gone)).as("missing jar")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("caps nested Maven heap without overriding user tuning")
	void capsChildHeap() {
		Map<String, String> empty = new HashMap<>();
		MavenResolver.defaultChildHeap(empty);

		assertThat(empty).as("default cap applied").containsEntry("MAVEN_OPTS", "-Xmx512m");

		Map<String, String> tuned = new HashMap<>(Map.of("MAVEN_OPTS", "-Xmx2g"));
		MavenResolver.defaultChildHeap(tuned);

		assertThat(tuned).as("user tuning kept").containsEntry("MAVEN_OPTS", "-Xmx2g");

		Map<String, String> picked = new HashMap<>(Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g"));
		MavenResolver.defaultChildHeap(picked);

		assertThat(picked).as("alternate tuning kept").doesNotContainKey("MAVEN_OPTS");
	}

	@Test
	@DisplayName("drains streams, caps tails, and survives torn streams")
	void drainsStreams() {
		StringBuilder shortLog = new StringBuilder();
		MavenResolver.drainTo(
				new ByteArrayInputStream("alpha\nbeta\n".getBytes(StandardCharsets.UTF_8)), shortLog);

		assertThat(shortLog.toString()).as("lines kept").isEqualTo("alpha\nbeta\n");

		StringBuilder capped = new StringBuilder();
		MavenResolver.drainTo(
				new ByteArrayInputStream("y\n".repeat(1500).getBytes(StandardCharsets.UTF_8)), capped);

		assertThat(capped.length()).as("tail capped near two thousand")
				.isGreaterThanOrEqualTo(2000)
				.isLessThan(3000);

		StringBuilder broken = new StringBuilder();
		MavenResolver.drainTo(new FailingStream(), broken);

		assertThat(broken).as("torn stream tolerated").isEmpty();
	}

	@Test
	@Tag("slow")
	@DisplayName("refuses uninstalled sibling snapshots loudly")
	void refusesUninstalledSiblings() throws Exception {
		Path root = Files.createTempDirectory("siblings");
		copyWrapper(root);
		Files.write(root.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version><packaging>pom</packaging>"
						+ "<modules><module>a</module><module>b</module></modules></project>")
						.getBytes(StandardCharsets.UTF_8));
		Path a = root.resolve("a");
		Files.createDirectories(a);
		Files.write(a.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version></parent>"
						+ "<artifactId>a</artifactId><dependencies><dependency><groupId>org.junit.jupiter</groupId>"
						+ "<artifactId>junit-jupiter-api</artifactId><version>6.1.3</version>"
						+ "<scope>test</scope></dependency></dependencies></project>")
						.getBytes(StandardCharsets.UTF_8));
		Path b = root.resolve("b");
		Files.createDirectories(b);
		Files.write(b.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version></parent>"
						+ "<artifactId>b</artifactId><dependencies><dependency><groupId>t</groupId>"
						+ "<artifactId>a</artifactId><version>1</version></dependency></dependencies></project>")
						.getBytes(StandardCharsets.UTF_8));
		MavenResolver resolver = new MavenResolver(state);

		try {
			assertThatThrownBy(() -> resolver.resolve(root, false))
					.as("sibling snapshots required")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("install");
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@Tag("slow")
	@DisplayName("fails loudly when any module goes missing")
	void failsLoudlyOnPartialModules() throws Exception {
		Path root = Files.createTempDirectory("partial");
		copyWrapper(root);
		Files.write(root.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version><packaging>pom</packaging>"
						+ "<modules><module>good</module><module>bad</module></modules></project>")
						.getBytes(StandardCharsets.UTF_8));
		Path good = root.resolve("good");
		Files.createDirectories(good);
		Files.write(good.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version></parent>"
						+ "<artifactId>good</artifactId></project>").getBytes(StandardCharsets.UTF_8));
		Path bad = root.resolve("bad");
		Files.createDirectories(bad);
		Files.write(bad.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version></parent>"
						+ "<artifactId>bad</artifactId><dependencies><dependency><groupId>com.example</groupId>"
						+ "<artifactId>does-not-exist</artifactId><version>1</version>"
						+ "</dependency></dependencies></project>").getBytes(StandardCharsets.UTF_8));
		MavenResolver resolver = new MavenResolver(state);

		try {
			assertThatThrownBy(() -> resolver.resolve(root, false))
					.as("partial union refused")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("bad");
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@Tag("slow")
	@DisplayName("recomputes when pom stats change and heals unreadable projects")
	void recomputesOnChange() throws Exception {
		Path root = Files.createTempDirectory("changing");
		try {
			copyWrapper(root);
			Files.write(root.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
							+ "<artifactId>changing</artifactId><version>1</version><packaging>pom</packaging>"
							+ "<modules><module>lib</module></modules></project>")
							.getBytes(StandardCharsets.UTF_8));
			Path lib = root.resolve("lib");
			Files.createDirectories(lib);
			Files.write(lib.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
							+ "<artifactId>changing</artifactId><version>1</version></parent>"
							+ "<artifactId>lib</artifactId><dependencies><dependency><groupId>org.junit.jupiter</groupId>"
							+ "<artifactId>junit-jupiter-api</artifactId><version>6.1.3</version>"
							+ "<scope>test</scope></dependency></dependencies></project>")
							.getBytes(StandardCharsets.UTF_8));
			MavenResolver resolver = new MavenResolver(state);

			List<Path> first = resolver.resolve(root, true);
			Files.write(lib.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
							+ "<artifactId>changing</artifactId><version>1</version></parent>"
							+ "<artifactId>lib</artifactId><!-- touched -->"
							+ "<dependencies><dependency><groupId>org.junit.jupiter</groupId>"
							+ "<artifactId>junit-jupiter-api</artifactId><version>6.1.3</version>"
							+ "<scope>test</scope></dependency></dependencies></project>")
							.getBytes(StandardCharsets.UTF_8));
			List<Path> second = resolver.resolve(root, true);

			assertThat(second).as("recomputed union matches").isEqualTo(first);
			assertThat(second).as("recomputed instance differs").isNotSameAs(first);

			deleteTree(root);
			assertThatThrownBy(() -> resolver.resolve(root, false))
					.as("vanished project fails loudly")
					.isInstanceOf(IOException.class);
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@Tag("slow")
	@DisplayName("serves disk hits to fresh resolvers")
	void servesDiskHits() throws Exception {
		Path root = Files.createTempDirectory("diskhit");
		try {
			copyWrapper(root);
			Files.write(root.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
							+ "<artifactId>diskhit</artifactId><version>1</version><packaging>pom</packaging>"
							+ "<modules><module>lib</module></modules></project>")
							.getBytes(StandardCharsets.UTF_8));
			Path lib = root.resolve("lib");
			Files.createDirectories(lib);
			Files.write(lib.resolve("pom.xml"),
					("<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId>"
							+ "<artifactId>diskhit</artifactId><version>1</version></parent>"
							+ "<artifactId>lib</artifactId><dependencies><dependency><groupId>org.junit.jupiter</groupId>"
							+ "<artifactId>junit-jupiter-api</artifactId><version>6.1.3</version>"
							+ "<scope>test</scope></dependency></dependencies></project>")
							.getBytes(StandardCharsets.UTF_8));

			List<Path> first = new MavenResolver(state).resolve(root, true);
			List<Path> second = new MavenResolver(state).resolve(root, true);

			assertThat(first).as("resolved jars").isNotEmpty();
			assertThat(second).as("disk hit matches").isEqualTo(first);
			assertThat(second).as("disk hit is a fresh instance").isNotSameAs(first);
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@Tag("slow")
	@DisplayName("stops on interruption")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void stopsOnInterruption() throws Exception {
		Path projectDir = Paths.get(System.getProperty("user.dir")).getParent();
		MavenResolver resolver = new MavenResolver(state);
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean(false);
		try (var pool = Executors.newSingleThreadExecutor()) {
			Future<?> run = pool.submit(() -> {
				started.countDown();
				try {
					resolver.resolve(projectDir, false);
				} catch (IOException expected) {
					interrupted.set(true);
				}
				return null;
			});
			assertThat(started.await(10, TimeUnit.SECONDS)).as("run started").isTrue();
			Thread.sleep(500);
			run.cancel(true);
			assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS))
					.as("cancelled run")
					.isInstanceOf(CancellationException.class);
			long deadline = System.currentTimeMillis() + 10_000;
			while (!interrupted.get() && System.currentTimeMillis() < deadline) {
				Thread.sleep(100);
			}
			assertThat(interrupted.get()).as("worker saw the interrupt").isTrue();
		}
	}

	/** Stream that fails on every read. */
	private static final class FailingStream extends InputStream {

		@Override
		public int read() throws IOException {
			throw new IOException("torn down");
		}
	}

	/**
	 * Deletes a fixture tree, best effort. Temp projects must not
	 * outlive the test that made them.
	 *
	 * @param root fixture root, never null
	 */
	private static void deleteTree(Path root) {
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// Best effort: the OS reclaims temp on reboot regardless.
		}
	}

	/**
	 * Copies the repository wrapper into a fixture project so nested
	 * Maven runs work far from the checkout, on every OS.
	 *
	 * @param root fixture project root, never null
	 * @throws IOException when the wrapper cannot be copied
	 */
	private static void copyWrapper(Path root) throws IOException {
		Path repo = Paths.get(System.getProperty("user.dir")).getParent();
		Files.copy(repo.resolve("mvnw"), root.resolve("mvnw"));
		Files.copy(repo.resolve("mvnw.cmd"), root.resolve("mvnw.cmd"));
		Path template = repo.resolve(".mvn");
		try (Stream<Path> walk = Files.walk(template)) {
			for (Path source : walk.toList()) {
				Path target = root.resolve(".mvn").resolve(template.relativize(source));
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(source, target);
				}
			}
		}
		root.resolve("mvnw").toFile().setExecutable(true);
	}
}

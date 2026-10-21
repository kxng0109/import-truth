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
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Resolves this very repository offline: no fixtures, real Maven, real cache.
 */
@DisplayName("MavenResolver")
@Execution(ExecutionMode.CONCURRENT)
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

		assertThat(empty).as("default cap applied")
				.containsEntry("MAVEN_OPTS", "-Xms64m -Xmx512m -XX:+UseSerialGC");

		Map<String, String> tuned = new HashMap<>(Map.of("MAVEN_OPTS", "-Xmx2g"));
		MavenResolver.defaultChildHeap(tuned);

		assertThat(tuned).as("user tuning kept").containsEntry("MAVEN_OPTS", "-Xmx2g");

		Map<String, String> picked = new HashMap<>(Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g"));
		MavenResolver.defaultChildHeap(picked);

		assertThat(picked).as("alternate tuning kept").doesNotContainKey("MAVEN_OPTS");
	}

	@Test
	@DisplayName("finds nested modules declared in the aggregator pom")
	void findsNestedModules() throws Exception {
		Path root = state.resolve("nested");
		Path api = root.resolve("services").resolve("api");
		Files.createDirectories(api);
		Files.writeString(root.resolve("pom.xml"),
				"<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version>"
						+ "<modules><module>services/api</module></modules></project>");
		Files.writeString(api.resolve("pom.xml"),
				"<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>api</artifactId><version>1</version></project>");

		List<Path> modules = MavenResolver.modulesOf(root);

		assertThat(modules).as("nested module found").contains(api);
		assertThat(modules).as("aggregator included").contains(root);
	}

	@Test
	@Tag("slow")
	@DisplayName("reuses the cached classpath of a project with no dependencies")
	void reusesEmptyClasspathCache() throws Exception {
		Path root = state.resolve("empty");
		Files.createDirectories(root);
		Files.writeString(root.resolve("pom.xml"),
				"<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>empty</artifactId><version>1</version></project>");
		MavenResolver first = new MavenResolver(state);

		assertThat(first.resolve(root, false)).as("no dependencies").isEmpty();

		MavenResolver second = new MavenResolver(state);
		long start = System.nanoTime();
		List<Path> cached = second.resolve(root, false);
		long millis = (System.nanoTime() - start) / 1_000_000;

		assertThat(cached).as("cached empty classpath").isEmpty();
		assertThat(millis).as("served from the cache, not a fresh Maven run")
				.isLessThan(1_500L);
		try (Stream<Path> leftovers = Files.list(root)) {
			assertThat(leftovers.map(p -> p.getFileName().toString()))
					.as("no scratch file left in the project")
					.noneMatch(name -> name.startsWith(".importtruth-"));
		}
	}

	@Test
	@DisplayName("classifies missing tooling tails for online retry")
	@SuppressWarnings("DataFlowIssue")
	void classifiesToolingFailures() {
		String downloaded = "exit=1 [ERROR] Failed to execute goal"
				+ " org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath:"
				+ " Cannot access central (https://repo.maven.apache.org/maven2) in offline mode"
				+ " and the artifact has not been downloaded from it before.";

		assertThat(MavenResolver.isToolingFailure(downloaded)).as("missing plugin").isTrue();
		assertThat(MavenResolver.isToolingFailure(downloaded.toUpperCase(Locale.ROOT)))
				.as("case insensitive")
				.isTrue();
		assertThat(MavenResolver.isToolingFailure(
				"exit=1 [ERROR] maven-dependency-plugin:3.11.0 could not be resolved in offline mode"))
				.as("offline mode wording")
				.isTrue();
		assertThat(MavenResolver.isToolingFailure(
				"exit=1 [ERROR] Plugin org.apache.maven.plugins:maven-dependency-plugin:3.11.0"
						+ " or one of its dependencies could not be resolved: [Help 1]"
						+ " http://cwiki.apache.org/confluence/display/maven/PluginResolutionException"))
				.as("plugin exception name").isTrue();
		assertThat(MavenResolver.isToolingFailure(
				"exit=1 [ERROR] Failed to resolve org.example:missing:jar:1"))
				.as("project dependency")
				.isFalse();
		assertThat(MavenResolver.isToolingFailure(
				"exit=1 [ERROR] Non-resolvable parent POM for t:root:1"))
				.as("unrelated failure")
				.isFalse();
		assertThat(MavenResolver.isToolingFailure("")).as("blank tail").isFalse();
		assertThat(MavenResolver.isToolingFailure(null)).as("null tail").isFalse();
	}

	@Test
	@DisplayName("retries online for opt-in callers or missing tooling")
	@SuppressWarnings("DataFlowIssue")
	void decidesOnlineRetry() {
		String tooling = "exit=1 maven-dependency-plugin:3.11.0 has not been downloaded";
		String project = "exit=1 Failed to resolve org.example:missing:jar:1";

		assertThat(MavenResolver.retryOnline(true, project)).as("opt-in retries").isTrue();
		assertThat(MavenResolver.retryOnline(false, tooling)).as("tooling self heals").isTrue();
		assertThat(MavenResolver.retryOnline(false, project)).as("project stays offline").isFalse();
		assertThat(MavenResolver.retryOnline(false, null)).as("null stays offline").isFalse();
	}

	@Test
	@DisplayName("names the warmup when the tooling cache is cold")
	void namesWarmupHint() {
		assertThat(MavenResolver.failureHint(true, false, null)).as("tooling hint")
				.contains("mvn -B -ntp compile");
		assertThat(MavenResolver.failureHint(false, true, null)).as("project hint")
				.contains("not in the local Maven repository");
		assertThat(MavenResolver.failureHint(false, false, null)).as("sibling hint").contains("install");
		assertThat(MavenResolver.failureHint(false, false,
				"Goal requires a project to execute but there is no POM in this directory"))
				.as("missing pom hint").contains("no pom.xml");
		assertThat(MavenResolver.failureHint(false, false, "Could not create local repository at C:\\x"))
				.as("repository hint").contains("local repository");
	}

	@Test
	@DisplayName("tells a missing project dependency from a missing tool")
	void tellsMissingProjectFromMissingTool() {
		String project = "The following artifacts could not be resolved: com.example:lib:jar:1.0"
				+ " (absent): Could not find artifact com.example:lib:jar:1.0 and the artifact"
				+ " com.example:lib:jar:1.0 has not been downloaded from it before.";
		String tooling = "Plugin org.apache.maven.plugins:maven-dependency-plugin:3.11.0 or one of its"
				+ " dependencies could not be resolved: The artifact has not been downloaded from it before.";

		assertThat(MavenResolver.isProjectDependencyMissing(project)).as("project dependency").isTrue();
		assertThat(MavenResolver.isProjectDependencyMissing(tooling)).as("tooling is not a project")
				.isFalse();
		assertThat(MavenResolver.isProjectDependencyMissing(null)).as("null tail").isFalse();
		assertThat(MavenResolver.isProjectDependencyMissing("")).as("blank tail").isFalse();
	}

	@Test
	@DisplayName("evicts remembered resolutions when full")
	void evictsMemoryWhenFull() throws Exception {
		MavenResolver resolver = new MavenResolver(state);
		for (int i = 0; i < 130; i++) {
			Path bare = state.resolve("bare-" + i);
			Files.createDirectories(bare);
			assertThat(resolver.resolve(bare, false)).as("bare union stays empty").isEmpty();
		}

		assertThat(resolver.resolve(state.resolve("bare-0"), false))
				.as("evicted project still resolves")
				.isEmpty();
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

	@Test
	@DisplayName("fails the followers when the leader dies")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void failsFollowersWhenTheLeaderDies() throws Exception {
		MavenResolver resolver = new MavenResolver(state);
		CountDownLatch leaderInside = new CountDownLatch(1);
		CountDownLatch followerWaiting = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		resolver.beforeResolve = () -> {
			leaderInside.countDown();
			await(release);
			throw new OutOfMemoryError("simulated leader death");
		};
		resolver.followerJoined = followerWaiting::countDown;
		Path project = state.resolve("leader");
		Files.createDirectories(project);
		AtomicReference<Throwable> leaderFailure = new AtomicReference<>();
		long start = System.nanoTime();
		ExecutorService pool = daemonPool(2);
		try {
			Future<?> leader = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
				} catch (Throwable failure) {
					leaderFailure.set(failure);
				}
			});
			assertThat(leaderInside.await(20, TimeUnit.SECONDS)).as("the leader started").isTrue();
			Future<Throwable> follower = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
					return null;
				} catch (Throwable failure) {
					return failure;
				}
			});
			assertThat(followerWaiting.await(20, TimeUnit.SECONDS)).as("the follower joined").isTrue();
			release.countDown();
			leader.get(30, TimeUnit.SECONDS);
			Throwable seen = follower.get(30, TimeUnit.SECONDS);
			long millis = (System.nanoTime() - start) / 1_000_000;

			assertThat(leaderFailure.get()).as("the leader saw the error")
					.isInstanceOf(OutOfMemoryError.class);
			assertThat(seen).as("the follower failed clearly")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("Resolution failed")
					.hasCauseInstanceOf(OutOfMemoryError.class);
			assertThat(millis).as("the follower failed fast").isLessThan(20_000L);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("gives up on a leader that never finishes")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void givesUpOnALeaderThatNeverFinishes() throws Exception {
		MavenResolver resolver = new MavenResolver(state);
		resolver.followerWait = Duration.ofMillis(200);
		CountDownLatch leaderInside = new CountDownLatch(1);
		CountDownLatch followerWaiting = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		resolver.beforeResolve = () -> {
			leaderInside.countDown();
			await(release);
		};
		resolver.followerJoined = followerWaiting::countDown;
		Path project = state.resolve("stuck");
		Files.createDirectories(project);
		ExecutorService pool = daemonPool(2);
		try {
			Future<?> leader = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
				} catch (Exception ignored) {
					// The leader is released at the end of the test.
				}
			});
			assertThat(leaderInside.await(20, TimeUnit.SECONDS)).as("the leader started").isTrue();
			Future<Throwable> follower = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
					return null;
				} catch (Throwable failure) {
					return failure;
				}
			});
			assertThat(followerWaiting.await(20, TimeUnit.SECONDS)).as("the follower joined").isTrue();
			Throwable seen = follower.get(30, TimeUnit.SECONDS);

			assertThat(seen).as("the follower gave up")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("Timed out");
		} finally {
			release.countDown();
			pool.shutdown();
			pool.awaitTermination(30, TimeUnit.SECONDS);
		}
	}

	@Test
	@DisplayName("prefers the system launcher over the project wrapper")
	void prefersSystemLauncher() throws Exception {
		Path root = Files.createTempDirectory("launcher");
		try {
			Files.write(root.resolve("mvnw.cmd"), "x".getBytes(StandardCharsets.UTF_8));

			MavenResolver.Launcher chosen = MavenResolver.chooseLauncher(root, root, true, true);

			assertThat(chosen.command()).as("the system launcher runs").containsExactly("mvn.cmd");
			assertThat(chosen.description()).as("the reason names the launcher").contains("system mvn.cmd");
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@DisplayName("uses the project wrapper only when the system launcher is missing")
	void usesProjectWrapperWithoutSystemLauncher() throws Exception {
		Path root = Files.createTempDirectory("launcher");
		try {
			Files.write(root.resolve("mvnw.cmd"), "x".getBytes(StandardCharsets.UTF_8));

			MavenResolver.Launcher chosen = MavenResolver.chooseLauncher(root, root, true, false);

			assertThat(chosen.command()).as("the wrapper runs").containsExactly("cmd", "/d", "/c", "mvnw.cmd");
			assertThat(chosen.description()).as("the reason names the launcher")
					.contains("mvnw.cmd")
					.contains("no system mvn.cmd on PATH");
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@DisplayName("never uses a wrapper above the project root")
	void ignoresWrapperAboveProjectRoot() throws Exception {
		Path outer = Files.createTempDirectory("outer");
		try {
			Files.write(outer.resolve("mvnw.cmd"), "x".getBytes(StandardCharsets.UTF_8));
			Path project = outer.resolve("project");
			Files.createDirectories(project);

			assertThat(MavenResolver.findWrapper(project, project, true))
					.as("the ancestor wrapper is not used")
					.isNull();
			MavenResolver.Launcher chosen = MavenResolver.chooseLauncher(project, project, true, false);

			assertThat(chosen.command()).as("the system launcher runs instead").containsExactly("mvn.cmd");
			assertThat(chosen.description()).as("the reason names the launcher")
					.contains("no wrapper inside the project root");
		} finally {
			deleteTree(outer);
		}
	}

	@Test
	@DisplayName("finds the system launcher in a search path")
	void findsSystemLauncher() throws Exception {
		Path dir = Files.createTempDirectory("search");
		try {
			Files.write(dir.resolve("mvn.cmd"), "x".getBytes(StandardCharsets.UTF_8));

			assertThat(MavenResolver.systemLauncherAvailable(true, List.of(dir)))
					.as("windows launcher found").isTrue();
			assertThat(MavenResolver.systemLauncherAvailable(true, List.of(dir.resolve("empty"))))
					.as("missing launcher").isFalse();
			assertThat(MavenResolver.systemLauncherAvailable(false, List.of(dir)))
					.as("unix launcher not found").isFalse();
			assertThat(MavenResolver.systemLauncherAvailable(true, List.of()))
					.as("empty search path").isFalse();
		} finally {
			deleteTree(dir);
		}
	}

	@Test
	@DisplayName("names the launcher in a failure message")
	void namesTheLauncherInFailures() {
		String line = MavenResolver.moduleFailure("lib", "system mvn.cmd (available on PATH)", "exit=1 boom");

		assertThat(line).as("the launcher is named").contains("system mvn.cmd");
		assertThat(line).as("the module is named").contains("lib");
		assertThat(line).as("the child output is kept").contains("exit=1 boom");
	}

	@Test
	@DisplayName("gives the followers the result of the leader")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void sharesTheLeaderResult() throws Exception {
		MavenResolver resolver = new MavenResolver(state);
		CountDownLatch leaderInside = new CountDownLatch(1);
		CountDownLatch followerWaiting = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		resolver.beforeResolve = () -> {
			leaderInside.countDown();
			await(release);
		};
		resolver.followerJoined = followerWaiting::countDown;
		Path project = state.resolve("shared");
		Files.createDirectories(project);
		ExecutorService pool = daemonPool(2);
		try {
			Future<List<Path>> leader = pool.submit(() -> resolver.resolve(project, false));
			assertThat(leaderInside.await(20, TimeUnit.SECONDS)).as("the leader started").isTrue();
			Future<List<Path>> follower = pool.submit(() -> resolver.resolve(project, false));
			assertThat(followerWaiting.await(20, TimeUnit.SECONDS)).as("the follower joined").isTrue();
			release.countDown();

			assertThat(follower.get(30, TimeUnit.SECONDS)).as("the follower result").isEmpty();
			assertThat(leader.get(30, TimeUnit.SECONDS)).as("the leader result").isEmpty();
		} finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("fails the followers when the leader throws a runtime failure")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void failsFollowersOnRuntimeFailure() throws Exception {
		MavenResolver resolver = new MavenResolver(state);
		CountDownLatch leaderInside = new CountDownLatch(1);
		CountDownLatch followerWaiting = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		resolver.beforeResolve = () -> {
			leaderInside.countDown();
			await(release);
			throw new IllegalStateException("simulated leader failure");
		};
		resolver.followerJoined = followerWaiting::countDown;
		Path project = state.resolve("runtime");
		Files.createDirectories(project);
		ExecutorService pool = daemonPool(2);
		try {
			Future<?> leader = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
				} catch (Throwable ignored) {
					// The leader saw the failure, as designed.
				}
			});
			assertThat(leaderInside.await(20, TimeUnit.SECONDS)).as("the leader started").isTrue();
			Future<Throwable> follower = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
					return null;
				} catch (Throwable failure) {
					return failure;
				}
			});
			assertThat(followerWaiting.await(20, TimeUnit.SECONDS)).as("the follower joined").isTrue();
			release.countDown();
			leader.get(30, TimeUnit.SECONDS);
			Throwable seen = follower.get(30, TimeUnit.SECONDS);

			assertThat(seen).as("the follower failed clearly")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("Resolution failed")
					.hasCauseInstanceOf(IllegalStateException.class);
		} finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("fails the followers when the leader throws a checked failure")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void failsFollowersOnCheckedFailure() throws Exception {
		MavenResolver resolver = new MavenResolver(state);
		CountDownLatch leaderInside = new CountDownLatch(1);
		CountDownLatch followerWaiting = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		resolver.beforeResolve = () -> {
			leaderInside.countDown();
			await(release);
			sneakyThrow(new Throwable("simulated checked failure"));
		};
		resolver.followerJoined = followerWaiting::countDown;
		Path project = state.resolve("checked");
		Files.createDirectories(project);
		ExecutorService pool = daemonPool(2);
		try {
			Future<?> leader = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
				} catch (Throwable ignored) {
					// The leader saw the failure, as designed.
				}
			});
			assertThat(leaderInside.await(20, TimeUnit.SECONDS)).as("the leader started").isTrue();
			Future<Throwable> follower = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
					return null;
				} catch (Throwable failure) {
					return failure;
				}
			});
			assertThat(followerWaiting.await(20, TimeUnit.SECONDS)).as("the follower joined").isTrue();
			release.countDown();
			leader.get(30, TimeUnit.SECONDS);
			Throwable seen = follower.get(30, TimeUnit.SECONDS);

			assertThat(seen).as("the follower failed clearly")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("Resolution failed")
					.hasCauseInstanceOf(Throwable.class);
		} finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("gives up when a follower is interrupted")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void interruptsFollower() throws Exception {
		MavenResolver resolver = new MavenResolver(state);
		CountDownLatch leaderInside = new CountDownLatch(1);
		CountDownLatch followerWaiting = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		resolver.beforeResolve = () -> {
			leaderInside.countDown();
			await(release);
		};
		resolver.followerJoined = followerWaiting::countDown;
		Path project = state.resolve("interrupted");
		Files.createDirectories(project);
		AtomicReference<Throwable> seen = new AtomicReference<>();
		ExecutorService pool = daemonPool(1);
		try {
			Future<?> leader = pool.submit(() -> {
				try {
					resolver.resolve(project, false);
				} catch (Exception ignored) {
					// The leader is released at the end of the test.
				}
			});
			assertThat(leaderInside.await(20, TimeUnit.SECONDS)).as("the leader started").isTrue();
			Thread follower = new Thread(() -> {
				try {
					resolver.resolve(project, false);
				} catch (Throwable failure) {
					seen.set(failure);
				}
			});
			follower.setDaemon(true);
			follower.start();
			assertThat(followerWaiting.await(20, TimeUnit.SECONDS)).as("the follower joined").isTrue();
			follower.interrupt();
			follower.join(30_000);

			assertThat(seen.get()).as("the follower gave up")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("Interrupted");
			assertThat(follower.isInterrupted()).as("the interrupt flag was restored").isTrue();
		} finally {
			release.countDown();
			pool.shutdown();
			pool.awaitTermination(30, TimeUnit.SECONDS);
		}
	}

	@Test
	@DisplayName("runs the default seams without effect")
	void defaultSeamsDoNothing() throws Exception {
		MavenResolver resolver = new MavenResolver(state);
		Path project = state.resolve("plain");
		Files.createDirectories(project);

		resolver.beforeResolve.run();
		resolver.followerJoined.run();

		assertThat(resolver.resolve(project, false)).as("the default seams change nothing").isEmpty();
	}

	@Test
	@DisplayName("names the unix launcher")
	void namesTheUnixLauncher() {
		MavenResolver.Launcher chosen = MavenResolver.chooseLauncher(state, state, false, true);

		assertThat(chosen.command()).as("the unix system launcher").containsExactly("mvn");
		assertThat(chosen.description()).as("the reason names the launcher").contains("system mvn");
	}

	@Test
	@DisplayName("finds a wrapper in a nested module of the project")
	void findsNestedModuleWrapper() throws Exception {
		Path root = Files.createTempDirectory("nested");
		try {
			Path module = root.resolve("services").resolve("api");
			Files.createDirectories(module);
			Path wrapper = root.resolve("mvnw.cmd");
			Files.write(wrapper, "x".getBytes(StandardCharsets.UTF_8));

			assertThat(MavenResolver.findWrapper(module, root, true))
					.as("the project wrapper is found")
					.isEqualTo(wrapper.toAbsolutePath().normalize());
		} finally {
			deleteTree(root);
		}
	}

	@Test
	@DisplayName("skips malformed search path entries")
	void skipsMalformedSearchPathEntries() {
		String broken = "C:\\bad\u0000name";
		List<Path> dirs = MavenResolver.launcherSearchPath(
				File.pathSeparator + "C:\\good" + File.pathSeparator + broken + File.pathSeparator + "   ",
				broken, "   ");

		assertThat(dirs).as("only the good entries").containsExactly(Path.of("C:\\good"));
		assertThat(MavenResolver.launcherSearchPath("  ", null, null)).as("an empty search path").isEmpty();
		assertThat(MavenResolver.launcherSearchPath(null, null, null)).as("a missing search path").isEmpty();
		assertThat(MavenResolver.systemLauncherAvailable(true, Collections.singletonList(null)))
				.as("a missing directory").isFalse();
	}

	/**
	 * Waits for a latch without throwing. A test seam must not fail
	 * the run it steers.
	 *
	 * @param latch the latch to wait for, never null
	 */
	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Throws a checked failure the compiler cannot see. A leader that
	 * fails this way must still release its followers.
	 *
	 * @param <T>     the thrown type
	 * @param failure the failure to throw, never null
	 * @throws T always
	 */
	@SuppressWarnings("unchecked")
	private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
		throw (T) failure;
	}

	/**
	 * Creates a pool whose threads never hold the run open. A leader
	 * that never finishes must not keep the suite from ending.
	 *
	 * @param size the number of threads, positive
	 * @return the pool, never null
	 */
	private static ExecutorService daemonPool(int size) {
		return Executors.newFixedThreadPool(size, runnable -> {
			Thread thread = new Thread(runnable);
			thread.setDaemon(true);
			return thread;
		});
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

package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
	@DisplayName("resolves the repository jars and reuses the cache")
	void resolvesOwnJarsTwice() throws Exception {
		Path projectDir = Paths.get(System.getProperty("user.dir")).getParent();
		MavenResolver resolver = new MavenResolver(state);

		List<Path> first = resolver.resolve(projectDir, true);
		List<Path> second = resolver.resolve(projectDir, true);

		assertThat(first).as("resolved jars").isNotEmpty();
		assertThat(first.stream().allMatch(p -> p.toString().endsWith(".jar") && Files.exists(p)))
				.as("existing jars only")
				.isTrue();
		assertThat(first.stream().anyMatch(p -> p.getFileName().toString().contains("assertj")))
				.as("contains the assertion library")
				.isTrue();
		assertThat(second).as("cached second run").isEqualTo(first);
	}

	@Test
	@DisplayName("picks launchers per operating system")
	void picksLaunchers() throws Exception {
		Path dir = Files.createTempDirectory("launcher");
		try {
			Path script = dir.resolve("mvnw.cmd");
			Files.write(script, "x".getBytes(StandardCharsets.UTF_8));

			assertThat(MavenResolver.launcher(null, true)).as("windows fallback").containsExactly("mvn.cmd");
			assertThat(MavenResolver.launcher(null, false)).as("unix fallback").containsExactly("mvn");
			assertThat(MavenResolver.launcher(script, true).get(0)).as("windows shell").isEqualTo("cmd");
			assertThat(MavenResolver.launcher(script, false).get(0)).as("unix shell").isEqualTo("sh");
			assertThat(MavenResolver.findWrapper(dir, true)).as("windows wrapper").isEqualTo(script);
			assertThat(MavenResolver.findWrapper(dir.resolve("deep").resolve("nested"), false))
					.as("missing wrapper")
					.isNull();
		} finally {
			Files.deleteIfExists(dir.resolve("mvnw.cmd"));
			Files.deleteIfExists(dir);
		}
	}

	@Test
	@Tag("slow")
	@DisplayName("fails loudly on unresolvable projects")
	void failsLoudly() throws Exception {
		Path bare = Files.createTempDirectory("bare");
		Files.write(bare.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>bare</artifactId><version>1</version>"
						+ "<dependencies><dependency><groupId>com.example</groupId>"
						+ "<artifactId>does-not-exist</artifactId><version>1</version>"
						+ "</dependency></dependencies></project>").getBytes(StandardCharsets.UTF_8));
		MavenResolver resolver = new MavenResolver(state);

		assertThatThrownBy(() -> resolver.resolve(bare, true))
				.as("unresolvable fails")
				.isInstanceOf(IOException.class);
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

		assertThat(resolver.resolve(bare, false)).as("empty union").isEmpty();
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
}

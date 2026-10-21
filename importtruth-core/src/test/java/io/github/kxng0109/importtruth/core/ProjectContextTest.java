package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the per project context: the memo, the rule that drops it,
 * the bound on its size, and its safety under concurrent calls.
 */
@DisplayName("Per project context")
final class ProjectContextTest {

	@TempDir
	private Path project;

	@Test
	@DisplayName("walks the source tree once per project")
	void walksOncePerProject() throws Exception {
		source("com/example/Widget.java");

		int before = ProjectPackages.WALKS.get();
		Set<String> first = ProjectPackages.of(project);
		int afterFirst = ProjectPackages.WALKS.get();
		Set<String> second = ProjectPackages.of(project);
		int afterSecond = ProjectPackages.WALKS.get();

		assertThat(first).as("own packages").contains("com.example");
		assertThat(second).as("same answer").isEqualTo(first);
		assertThat(afterFirst - before).as("the first call walks").isPositive();
		assertThat(afterSecond - afterFirst).as("the second call walks no more").isZero();
	}

	@Test
	@DisplayName("rebuilds when a build file changes")
	void rebuildsWhenBuildFileChanges() throws Exception {
		Path pom = project.resolve("pom.xml");
		Files.write(pom, pomText().getBytes(StandardCharsets.UTF_8));
		source("com/example/Widget.java");
		ProjectPackages.of(project);
		int before = ProjectPackages.WALKS.get();

		Files.write(pom, pomText().getBytes(StandardCharsets.UTF_8));

		ProjectPackages.of(project);

		assertThat(ProjectPackages.WALKS.get() - before).as("a changed build file walks again").isPositive();
	}

	@Test
	@DisplayName("sees a nested module build file change")
	void seesNestedModuleChange() throws Exception {
		Path inner = project.resolve("mod").resolve("inner");
		Files.createDirectories(inner);
		Files.write(project.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version><modules><module>mod</module>"
						+ "</modules></project>").getBytes(StandardCharsets.UTF_8));
		Files.write(project.resolve("mod").resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>mod</artifactId><version>1</version><modules><module>inner</module>"
						+ "</modules></project>").getBytes(StandardCharsets.UTF_8));
		Path innerPom = inner.resolve("pom.xml");
		Files.write(innerPom, pomText().getBytes(StandardCharsets.UTF_8));
		source("com/example/Widget.java");

		Set<String> own = ProjectPackages.of(project);
		String key = CheckOrchestrator.scopeKey(List.of(), List.of(), own, 21);

		Files.write(innerPom, pomText().getBytes(StandardCharsets.UTF_8));
		Set<String> after = ProjectPackages.of(project);
		String changed = CheckOrchestrator.scopeKey(List.of(), List.of(), after, 21);

		assertThat(after).as("own packages unchanged").isEqualTo(own);
		assertThat(changed).as("the scope key follows the build definition")
				.isNotEqualTo(key)
				.contains("inner");
	}

	@Test
	@DisplayName("keeps the cache bounded")
	void keepsCacheBounded() throws Exception {
		int before = ProjectPackages.cachedContexts();
		for (int i = 0; i < ProjectPackages.MAX_CONTEXTS + 8; i++) {
			Path other = project.resolve("p" + i);
			Files.createDirectories(other.resolve("src/main/java/com/example"));
			Files.write(other.resolve("src/main/java/com/example/C" + i + ".java"),
					("package com.example; public class C" + i + " { }").getBytes(StandardCharsets.UTF_8));
			ProjectPackages.of(other);
		}

		assertThat(ProjectPackages.cachedContexts()).as("cache size")
				.isLessThanOrEqualTo(ProjectPackages.MAX_CONTEXTS)
				.isGreaterThan(before);
	}

	@Test
	@DisplayName("answers the same under concurrent calls")
	void answersUnderConcurrency() throws Exception {
		source("com/example/Widget.java");
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Set<String>>> calls = new ArrayList<>();
			for (int i = 0; i < 32; i++) {
				calls.add(() -> ProjectPackages.of(project));
			}
			List<Future<Set<String>>> answers = pool.invokeAll(calls);
			Set<String> first = answers.get(0).get();
			for (Future<Set<String>> answer : answers) {
				assertThat(answer.get()).as("same answer for every caller").isEqualTo(first);
			}
		} finally {
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("pool stopped").isTrue();
		}
	}

	@Test
	@DisplayName("rejects a null project")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullProject() {
		assertThatThrownBy(() -> ProjectPackages.of(null))
				.as("null project rejected")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("reads a vanished build file as changed")
	void readsVanishedBuildFileAsChanged() throws Exception {
		Path pom = project.resolve("pom.xml");
		Files.write(pom, pomText().getBytes(StandardCharsets.UTF_8));
		source("com/example/Widget.java");
		ProjectPackages.of(project);
		int before = ProjectPackages.WALKS.get();

		Files.delete(pom);
		Set<String> after = ProjectPackages.of(project);

		assertThat(after).as("own packages survive a vanished build file").contains("com.example");
		assertThat(ProjectPackages.WALKS.get() - before).as("the change walks again").isPositive();
	}

	@Test
	@DisplayName("reads compiled classes as own packages")
	void readsCompiledClasses() throws Exception {
		Path compiled = project.resolve("target/classes/com/example");
		Files.createDirectories(compiled);
		Files.write(compiled.resolve("Widget.class"), "bytes".getBytes(StandardCharsets.UTF_8));

		assertThat(ProjectPackages.of(project)).as("compiled packages").contains("com.example");
	}

	@Test
	@DisplayName("reads a project that is a file as empty")
	void readsFileAsProject() throws Exception {
		Path file = project.resolve("plain.txt");
		Files.write(file, "text".getBytes(StandardCharsets.UTF_8));

		assertThat(ProjectPackages.of(file)).as("no packages").isEmpty();
	}

	@Test
	@DisplayName("stops at the module depth limit")
	void stopsAtModuleDepthLimit() throws Exception {
		Path deep = project;
		for (int i = 0; i < MavenResolver.MAX_POM_DEPTH + 2; i++) {
			deep = deep.resolve("m" + i);
			Files.createDirectories(deep);
			Files.write(deep.resolve("pom.xml"), pomText().getBytes(StandardCharsets.UTF_8));
		}
		Files.write(project.resolve("pom.xml"), pomText().getBytes(StandardCharsets.UTF_8));

		assertThat(ProjectPackages.of(project)).as("the deep project still answers").isNotNull();
	}

	@Test
	@DisplayName("survives a module that declares its parent")
	void survivesCyclicModule() throws Exception {
		Path mod = project.resolve("mod");
		Files.createDirectories(mod);
		Files.write(project.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version><modules><module>mod</module>"
						+ "</modules></project>").getBytes(StandardCharsets.UTF_8));
		Files.write(mod.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>mod</artifactId><version>1</version><modules><module>..</module>"
						+ "</modules></project>").getBytes(StandardCharsets.UTF_8));

		assertThat(ProjectPackages.of(project)).as("the cycle does not loop").isNotNull();
	}

	@Test
	@DisplayName("fails the builder and the followers when the project cannot be read")
	void failsBuilderAndFollowers() throws Exception {
		source("com/example/Widget.java");
		ProjectPackages.BuildHook failing = () -> {
			throw new IOException("no read");
		};
		ProjectPackages.beforeBuild = failing;
		try {
			assertThatThrownBy(() -> ProjectPackages.of(project))
					.as("the builder fails")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("no read");
		} finally {
			ProjectPackages.beforeBuild = () -> { };
		}
	}

	@Test
	@DisplayName("wraps a builder failure that is not an IO failure")
	void wrapsBuilderFailure() throws Exception {
		source("com/example/Widget.java");
		ProjectPackages.BuildHook failing = () -> {
			throw new IllegalStateException("broken");
		};
		ProjectPackages.beforeBuild = failing;
		try {
			assertThatThrownBy(() -> ProjectPackages.of(project))
					.as("the builder fails")
					.isInstanceOf(IllegalStateException.class);
		} finally {
			ProjectPackages.beforeBuild = () -> { };
		}
	}

	@Test
	@DisplayName("wraps a builder failure that is neither an IO nor a runtime failure")
	void wrapsBuilderError() throws Exception {
		source("com/example/Widget.java");
		ProjectPackages.BuildHook failing = () -> {
			throw new StackOverflowError("deep");
		};
		ProjectPackages.beforeBuild = failing;
		try {
			assertThatThrownBy(() -> ProjectPackages.of(project))
					.as("the builder fails")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("Failed to read the packages");
		} finally {
			ProjectPackages.beforeBuild = () -> { };
		}
	}

	@Test
	@DisplayName("proves a package that holds classes still covers a name")
	void provesPackageHoldingClasses() throws Exception {
		Path compiled = project.resolve("target/classes/com/example");
		Files.createDirectories(compiled);
		Files.write(compiled.resolve("Widget.class"), "bytes".getBytes(StandardCharsets.UTF_8));

		Set<String> own = ProjectPackages.of(project);

		assertThat(ProjectPackages.covers(own, "com.example")).as("the package covers the name").isTrue();
		assertThat(ProjectPackages.covers(own, "com.other")).as("another package does not").isFalse();
	}

	@Test
	@DisplayName("gives up on a follower that waits too long")
	void givesUpOnSlowFollower() throws Exception {
		source("com/example/Widget.java");
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch building = new CountDownLatch(1);
		ProjectPackages.BuildHook blocking = () -> {
			building.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", interrupted);
			}
		};
		ProjectPackages.beforeBuild = blocking;
		Duration wait = ProjectPackages.followerWait;
		ProjectPackages.followerWait = Duration.ofMillis(50);
		try {
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				Future<Set<String>> leader = pool.submit(() -> ProjectPackages.of(project));
				assertThat(building.await(30, TimeUnit.SECONDS)).as("the builder started").isTrue();
				Future<Set<String>> follower = pool.submit(() -> ProjectPackages.of(project));

				assertThatThrownBy(() -> follower.get(30, TimeUnit.SECONDS))
						.as("the follower gives up")
						.isInstanceOf(ExecutionException.class)
						.hasCauseInstanceOf(IOException.class)
						.hasStackTraceContaining("Timed out");
				release.countDown();
				assertThat(leader.get(30, TimeUnit.SECONDS)).as("the builder answers").isNotNull();
			} finally {
				release.countDown();
				pool.shutdown();
			}
		} finally {
			ProjectPackages.beforeBuild = () -> { };
			ProjectPackages.followerWait = wait;
		}
	}

	@Test
	@DisplayName("tells a follower that the builder failed")
	void tellsFollowersBuilderFailed() throws Exception {
		source("com/example/Widget.java");
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch building = new CountDownLatch(1);
		ProjectPackages.BuildHook blocking = () -> {
			building.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", interrupted);
			}
			throw new IOException("no read");
		};
		ProjectPackages.beforeBuild = blocking;
		Duration wait = ProjectPackages.followerWait;
		ProjectPackages.followerWait = Duration.ofSeconds(30);
		try {
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				Future<Set<String>> leader = pool.submit(() -> ProjectPackages.of(project));
				assertThat(building.await(30, TimeUnit.SECONDS)).as("the builder started").isTrue();
				Future<Set<String>> follower = pool.submit(() -> ProjectPackages.of(project));
				// The follower has to reach the wait before the
				// builder fails, or it would build the project itself.
				Thread.sleep(200);

				release.countDown();
				assertThatThrownBy(() -> follower.get(30, TimeUnit.SECONDS))
						.as("the follower learns of the failure")
						.isInstanceOf(ExecutionException.class)
						.hasCauseInstanceOf(IOException.class)
						.hasStackTraceContaining("Failed to read the packages");
				assertThatThrownBy(() -> leader.get(30, TimeUnit.SECONDS))
						.as("the builder fails")
						.isInstanceOf(ExecutionException.class)
						.hasCauseInstanceOf(IOException.class);
			} finally {
				release.countDown();
				pool.shutdown();
			}
		} finally {
			ProjectPackages.beforeBuild = () -> { };
			ProjectPackages.followerWait = wait;
		}
	}

	@Test
	@DisplayName("tells a follower that it was interrupted")
	void tellsFollowersInterrupted() throws Exception {
		source("com/example/Widget.java");
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch building = new CountDownLatch(1);
		ProjectPackages.BuildHook blocking = () -> {
			building.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", interrupted);
			}
		};
		ProjectPackages.beforeBuild = blocking;
		Duration wait = ProjectPackages.followerWait;
		ProjectPackages.followerWait = Duration.ofSeconds(30);
		try {
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				Future<Set<String>> leader = pool.submit(() -> ProjectPackages.of(project));
				assertThat(building.await(30, TimeUnit.SECONDS)).as("the builder started").isTrue();
				Future<Set<String>> follower = pool.submit(() -> ProjectPackages.of(project));
				Thread.sleep(100);
				follower.cancel(true);

				assertThatThrownBy(() -> follower.get(30, TimeUnit.SECONDS))
						.as("the follower is cancelled")
						.isInstanceOf(java.util.concurrent.CancellationException.class);
				release.countDown();
				leader.get(30, TimeUnit.SECONDS);
			} finally {
				release.countDown();
				pool.shutdown();
			}
		} finally {
			ProjectPackages.beforeBuild = () -> { };
			ProjectPackages.followerWait = wait;
		}
	}

	private void source(String relative) throws IOException {
		Path file = project.resolve("src/main/java").resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, ("package com.example; public class Widget { }").getBytes(StandardCharsets.UTF_8));
	}

	private static String pomText() {
		return "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
				+ "<artifactId>probe</artifactId><version>1</version></project>";
	}
}

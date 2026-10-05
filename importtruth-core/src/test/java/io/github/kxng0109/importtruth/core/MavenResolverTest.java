package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolves this very repository offline: no fixtures, real Maven, real cache.
 */
@DisplayName("MavenResolver")
final class MavenResolverTest {

	@TempDir
	private Path state;

	@Test
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
}

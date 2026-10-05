package io.github.kxng0109.importtruth.adapter.java;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden rows from pinned test libraries, located through their own classes
 * so the test works on any machine: Jackson 3 present, Jackson 2 paths absent
 * from Jackson 3 jars, annotations retained.
 */
@DisplayName("Jackson golden rows")
final class JacksonGoldenTest {

	@Test
	@DisplayName("finds the Jackson 3 mapper and not the Jackson 2 path")
	void jacksonThreeMapper() throws Exception {
		AsmLibraryIndexer indexer = new AsmLibraryIndexer();
		List<Symbol> symbols = indexer.index(jarOf(tools.jackson.databind.ObjectMapper.class));

		assertThat(symbols.stream().filter(s -> s.fqn().equals("tools.jackson.databind.ObjectMapper")).count())
				.as("Jackson 3 mapper present")
				.isEqualTo(1L);
		assertThat(symbols.stream().anyMatch(s -> s.fqn().equals("com.fasterxml.jackson.databind.ObjectMapper")))
				.as("Jackson 2 mapper absent from Jackson 3 jar")
				.isFalse();
	}

	@Test
	@DisplayName("finds the Jackson 2 mapper in its own jar")
	void jacksonTwoMapper() throws Exception {
		List<Symbol> symbols =
				new AsmLibraryIndexer().index(jarOf(com.fasterxml.jackson.databind.ObjectMapper.class));

		assertThat(symbols.stream().anyMatch(s -> s.fqn().equals("com.fasterxml.jackson.databind.ObjectMapper")))
				.as("Jackson 2 mapper present in Jackson 2 jar")
				.isTrue();
	}

	@Test
	@DisplayName("keeps annotations under the retained package")
	void retainedAnnotations() throws Exception {
		List<Symbol> symbols =
				new AsmLibraryIndexer().index(jarOf(com.fasterxml.jackson.annotation.JsonProperty.class));

		assertThat(
						symbols.stream()
								.filter(s -> s.fqn().equals("com.fasterxml.jackson.annotation.JsonProperty"))
								.count())
				.as("retained annotation present")
				.isEqualTo(1L);
		assertThat(symbols.stream().allMatch(s -> s.kind() != null))
				.as("every symbol has a kind")
				.isTrue();
		assertThat(symbols.stream().anyMatch(s -> s.kind() == SymbolKind.CLASS))
				.as("annotation types recorded")
				.isTrue();
	}

	private static Path jarOf(Class<?> type) throws URISyntaxException {
		Path jar = Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertThat(jar.getFileName().toString()).as("class comes from a jar").endsWith(".jar");
		return jar;
	}
}

package io.github.kxng0109.importtruth.adapter.java;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden rows from real local libraries: Jackson 3 present, Jackson 2 paths
 * absent from Jackson 3 jars, annotations retained.
 */
@DisplayName("Jackson golden rows")
final class JacksonGoldenTest {

	private static final String DATABIND_3 =
			"tools/jackson/core/jackson-databind/3.1.2/jackson-databind-3.1.2.jar";
	private static final String DATABIND_2 =
			"com/fasterxml/jackson/core/jackson-databind/2.19.1/jackson-databind-2.19.1.jar";
	private static final String ANNOTATIONS_2 =
			"com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar";

	@Test
	@DisplayName("finds the Jackson 3 mapper and not the Jackson 2 path")
	void jacksonThreeMapper() throws Exception {
		AsmLibraryIndexer indexer = new AsmLibraryIndexer();
		List<Symbol> symbols = indexer.index(localJar(DATABIND_3));

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
		List<Symbol> symbols = new AsmLibraryIndexer().index(localJar(DATABIND_2));

		assertThat(symbols.stream().anyMatch(s -> s.fqn().equals("com.fasterxml.jackson.databind.ObjectMapper")))
				.as("Jackson 2 mapper present in Jackson 2 jar")
				.isTrue();
	}

	@Test
	@DisplayName("keeps annotations under the retained package")
	void retainedAnnotations() throws Exception {
		List<Symbol> symbols = new AsmLibraryIndexer().index(localJar(ANNOTATIONS_2));

		assertThat(
						symbols.stream()
								.filter(s -> s.fqn().equals("com.fasterxml.jackson.annotation.JsonProperty"))
								.count())
				.as("retained annotation present")
				.isEqualTo(1L);
		assertThat(symbols.stream().allMatch(s -> s.kind() != null))
				.as("every symbol has a kind")
				.isTrue();
		assertThat(symbols.stream().filter(s -> s.kind() == SymbolKind.CLASS).count() > 0)
				.as("annotation types recorded")
				.isTrue();
	}

	private static Path localJar(String coordinates) {
		List<String> parts = new ArrayList<>(List.of(System.getProperty("user.home"), ".m2", "repository"));
		parts.addAll(List.of(coordinates.split("/")));
		return Paths.get(parts.get(0), parts.subList(1, parts.size()).toArray(new String[0]));
	}
}

package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.core.SearchService;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies lookup and search tool lines, limits, and rejections.
 */
@DisplayName("Lookup and search tools")
final class LookupSearchToolTest {

	@TempDir
	private Path project;

	@TempDir
	private Path state;

	@Test
	@DisplayName("answers found, missing, and search")
	void answersTools() throws Exception {
		Services services = services();
		LookupTool lookup = new LookupTool(services.lookup());
		SearchTool search = new SearchTool(services.search());

		String found = textOf(lookup.call(Map.of("projectPath", project.toString(), "symbol", "com.example.Widget")));
		assertThat(found).as("found line").startsWith("FOUND DEFINITE com.example.Widget");

		String missing = textOf(lookup.call(Map.of("projectPath", project.toString(), "symbol", "org.example.Widget")));
		assertThat(missing).as("missing line").startsWith("NOT_FOUND CANDIDATE org.example.Widget");

		String hits = textOf(search.call(Map.of("projectPath", project.toString(), "query", "Widget", "limit", 1)));
		assertThat(hits).as("capped hits").doesNotContain("\n");
	}

	@Test
	@DisplayName("clamps limits and rejects bad arguments")
	void clampsAndRejects() throws Exception {
		Services services = services();
		SearchTool search = new SearchTool(services.search());

		assertThat(SearchTool.limitOf(99)).as("clamped limit").isEqualTo(SearchTool.MAX_LIMIT);
		assertThat(SearchTool.limitOf(null)).as("default limit").isEqualTo(SearchTool.DEFAULT_LIMIT);
		assertThat(search.call(Map.of("projectPath", project.toString())).isError())
				.as("missing query rejected")
				.isTrue();
	}

	private Services services() throws Exception {
		Path jar = project.resolve("dep.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
		}
		JarIndexStore store = new JarIndexStore(state.resolve("index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false));
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
		JdkIndex jdk = new JdkIndex();
		return new Services(
				new LookupService(store, indexer, resolver, jdk),
				new SearchService(store, indexer, resolver));
	}

	private static String textOf(CallToolResult result) {
		assertThat(result.isError()).as("no tool error").isFalse();
		return ((TextContent) result.content().get(0)).text();
	}

	private record Services(LookupService lookup, SearchService search) {
	}
}

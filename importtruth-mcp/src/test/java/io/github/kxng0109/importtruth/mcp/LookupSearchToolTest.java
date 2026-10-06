package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

	@Test
	@DisplayName("details JDK, deprecated, and failure lines")
	@SuppressWarnings("DataFlowIssue")
	void detailsBranches() throws Exception {
		Services rich = richServices();
		LookupTool lookup = new LookupTool(rich.lookup());
		SearchTool search = new SearchTool(rich.search());

		assertThat(textOf(lookup.call(Map.of("projectPath", project.toString(), "symbol", "java.util.List"))))
				.as("jdk line")
				.contains("(jdk)");
		String deprecated = textOf(lookup
				.call(Map.of("projectPath", project.toString(), "symbol", "com.example.Old.build")));
		assertThat(deprecated).as("deprecated detail").contains("deprecated").contains("since 1.2");
		assertThat(deprecated).as("removal flag").contains("for-removal");
		String plain = textOf(lookup
				.call(Map.of("projectPath", project.toString(), "symbol", "com.example.Plain.build")));
		assertThat(plain).as("plain deprecated without since").contains("deprecated").doesNotContain("since");

		assertThat(lookup.call(Map.of("projectPath", " ", "symbol", "com.example.Widget")).isError())
				.as("blank project rejected")
				.isTrue();
		assertThat(lookup.call(Map.of("projectPath", project.toString(), "symbol", 42)).isError())
				.as("non-string symbol rejected")
				.isTrue();
		assertThat(lookup.call(Map.of("projectPath", 42, "symbol", "com.example.Widget")).isError())
				.as("non-string project rejected")
				.isTrue();
		assertThat(lookup.call(Map.of("symbol", "com.example.Widget")).isError())
				.as("missing project rejected")
				.isTrue();
		assertThat(lookup.call(Map.of("projectPath", project.toString(), "symbol", " ")).isError())
				.as("blank symbol rejected")
				.isTrue();
		assertThatThrownBy(() -> lookup.call(null)).as("null rejection")
				.isInstanceOf(NullPointerException.class);

		LookupTool failing = new LookupTool(failingLookup());
		assertThat(failing.call(Map.of("projectPath", project.toString(), "symbol", "com.example.Widget"))
				.isError()).as("resolver failure").isTrue();

		assertThat(textOf(search.call(Map.of("projectPath", project.toString(), "query", "NopeZZZ"))))
				.as("no matches line")
				.contains("no matches for NopeZZZ");
		assertThat(textOf(search.call(Map.of("projectPath", project.toString(), "query", "Old"))))
				.as("deprecated hit")
				.contains("deprecated");
		assertThat(search.call(Map.of("projectPath", project.toString(), "query", " ", "limit", -3)).isError())
				.as("blank query rejected")
				.isTrue();
		assertThat(search.call(Map.of("projectPath", 42, "query", "Old")).isError())
				.as("non-string project rejected")
				.isTrue();
		assertThat(search.call(Map.of("projectPath", " ", "query", "Old")).isError())
				.as("blank project rejected")
				.isTrue();
		assertThat(search.call(Map.of("projectPath", project.toString(), "query", 42)).isError())
				.as("non-string query rejected")
				.isTrue();
		assertThat(search.call(Map.of("projectPath", project.toString(), "query", "Old", "limit", "many"))
				.isError()).as("non-number limit tolerated").isFalse();
		SearchTool broken = new SearchTool(failingSearch());
		assertThat(broken.call(Map.of("projectPath", project.toString(), "query", "Old")).isError())
				.as("search failure")
				.isTrue();
		assertThatThrownBy(() -> search.call(null)).as("null rejection")
				.isInstanceOf(NullPointerException.class);
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

	private Services richServices() throws Exception {
		Path jar = project.resolve("rich.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("rich.marker"));
			zip.write("rich".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		JarIndexStore store = new JarIndexStore(state.resolve("rich-index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Old", SymbolKind.CLASS, null, null, false, "", false),
				new Symbol("com.example.Old.build", SymbolKind.METHOD, "()V", "com.example.Old", true, "1.2",
						true),
				new Symbol("com.example.Plain", SymbolKind.CLASS, null, null, false, "", false),
				new Symbol("com.example.Plain.build", SymbolKind.METHOD, "()V", "com.example.Plain", true, "",
						false));
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
		JdkIndex jdk = new JdkIndex();
		return new Services(
				new LookupService(store, indexer, resolver, jdk),
				new SearchService(store, indexer, resolver));
	}

	private LookupService failingLookup() throws Exception {
		JarIndexStore store = new JarIndexStore(state.resolve("failing-index"));
		LibraryIndexer indexer = jarFile -> List.of();
		DependencyResolver resolver = (projectDir, allowNetwork) -> {
			throw new IOException("no network");
		};
		return new LookupService(store, indexer, resolver, new JdkIndex());
	}

	private SearchService failingSearch() throws Exception {
		JarIndexStore store = new JarIndexStore(state.resolve("failing-search"));
		LibraryIndexer indexer = jarFile -> List.of();
		DependencyResolver resolver = (projectDir, allowNetwork) -> {
			throw new IOException("no network");
		};
		return new SearchService(store, indexer, resolver);
	}

	private static String textOf(CallToolResult result) {
		assertThat(result.isError()).as("no tool error").isFalse();
		return ((TextContent) result.content().get(0)).text();
	}

	private record Services(LookupService lookup, SearchService search) {
	}
}

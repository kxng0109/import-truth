package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.SuggestService;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies suggest batches: blocks per name, limits, rejections,
 * and resolver isolation.
 */
@DisplayName("SuggestImportsTool")
final class SuggestImportsToolTest {

	@TempDir
	private Path project;

	@TempDir
	private Path state;

	private final List<JarIndexStore> openStores = new ArrayList<>();

	@AfterEach
	void closeStores() {
		for (JarIndexStore store : openStores) {
			try {
				store.close();
			} catch (Exception ignored) {
				// Best effort: temp cleanup reclaims the rest.
			}
		}
		openStores.clear();
	}

	@Test
	@DisplayName("batches found, missing, and blank items")
	void batchesMixedItems() throws Exception {
		SuggestImportsTool tool = tool();

		String answers = textOf(tool.call(Map.of("projectPath", project.toString(),
				"names", List.of("com.example.widget", "org.example.Nope", " ", 42))));

		assertThat(answers).as("found block").contains("suggestions for com.example.widget:")
				.contains("maybe: com.example.Widget");
		assertThat(answers).as("missing block").contains("no suggestions for org.example.Nope");
		assertThat(answers).as("blank item flagged").contains("ERROR name must be a non-blank single-line string");
	}

	@Test
	@DisplayName("clamps limits and caps output")
	void clampsLimits() throws Exception {
		SuggestImportsTool tool = tool();

		assertThat(SuggestImportsTool.limitOf(null)).as("default limit").isEqualTo(5);
		assertThat(SuggestImportsTool.limitOf(99)).as("clamped limit")
				.isEqualTo(SuggestImportsTool.MAX_LIMIT);
		assertThat(SuggestImportsTool.limitOf(0)).as("floor limit").isEqualTo(1);

		String capped = textOf(tool.call(Map.of("projectPath", project.toString(),
				"names", List.of("com.example.Widget"), "limit", 1)));

		assertThat(capped.lines().filter(line -> line.startsWith("maybe: ")).count())
				.as("one suggestion")
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("rejects bad batches and isolates resolver failures")
	@SuppressWarnings("DataFlowIssue")
	void rejectsBadBatches() throws Exception {
		SuggestImportsTool tool = tool();

		assertThat(tool.call(Map.of("projectPath", project.toString())).isError())
				.as("missing names rejected")
				.isTrue();
		assertThat(tool.call(Map.of("projectPath", project.toString(), "names", List.of())).isError())
				.as("empty names rejected")
				.isTrue();
		assertThat(tool.call(Map.of("projectPath", " ", "names", List.of("a.B"))).isError())
				.as("blank project rejected")
				.isTrue();
		assertThat(tool.call(Map.of("projectPath", project.toString(), "names", "nope")).isError())
				.as("non-list names rejected")
				.isTrue();
		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i <= SuggestImportsTool.MAX_NAMES; i++) {
			tooMany.add("com.example.Widget" + i);
		}
		assertThat(tool.call(Map.of("projectPath", project.toString(), "names", tooMany)).isError())
				.as("oversize batch rejected")
				.isTrue();
		assertThat(textOf(tool.call(
				Map.of("projectPath", project.toString(), "names", List.of(" ", 42)))))
				.as("all-invalid batch stays successful")
				.contains("ERROR name must be a non-blank single-line string");
		assertThatThrownBy(() -> tool.call(null)).as("null rejection")
				.isInstanceOf(NullPointerException.class);

		SuggestImportsTool failing = failingTool();
		CallToolResult failure = failing.call(
				Map.of("projectPath", project.toString(), "names", List.of("com.example.Widget")));

		assertThat(failure.isError()).as("resolver failure fails the call").isTrue();
	}

	@Test
	@DisplayName("carries the suggestions of every name")
	void carriesSuggestions() throws Exception {
		SuggestImportsTool tool = tool();

		CallToolResult result = tool.call(Map.of("projectPath", project.toString(),
				"names", List.of("com.example.Widget", "com.example.widget", "org.other.widget",
						"com.example.Widget<Foo>", "com.example.Widget[]", "Widget")));
		List<Map<String, Object>> answers = listOf(structuredOf(result), "results");

		assertThat(answers).as("one answer per name").hasSize(6);
		assertThat(answers.get(0)).as("status").containsEntry("status", "FOUND");
		assertThat(answers.get(0)).as("name").containsEntry("name", "com.example.Widget");
		List<Map<String, Object>> exact = listOf(answers.get(0), "suggestions");
		assertThat(exact.get(0)).as("name").containsEntry("name", "com.example.Widget");
		assertThat(exact.get(0)).as("exact simple name").containsEntry("match", "EXACT");
		assertThat(exact.get(0)).as("descriptor").containsEntry("descriptor", "Widget");
		assertThat(exact.get(1)).as("same package").containsEntry("match", "PACKAGE");
		List<Map<String, Object>> packaged = listOf(answers.get(1), "suggestions");
		assertThat(packaged.get(0)).as("same package").containsEntry("match", "PACKAGE");
		List<Map<String, Object>> other = listOf(answers.get(2), "suggestions");
		assertThat(other.get(0)).as("other package").containsEntry("match", "OTHER");
		assertThat(listOf(answers.get(3), "suggestions").get(0)).as("generic wanted name")
				.containsEntry("match", "EXACT");
		assertThat(listOf(answers.get(4), "suggestions").get(0)).as("array wanted name")
				.containsEntry("match", "EXACT");
		List<Map<String, Object>> bare = listOf(answers.get(5), "suggestions");
		assertThat(bare.get(0)).as("exact simple name").containsEntry("match", "EXACT");
		assertThat(bare.get(1)).as("no package to match").containsEntry("match", "OTHER");
	}

	@Test
	@DisplayName("carries a miss, an unusable item, and a failed call")
	void carriesMissAndFailure() throws Exception {
		SuggestImportsTool tool = tool();

		CallToolResult miss = tool.call(Map.of("projectPath", project.toString(),
				"names", List.of("org.example.Nope", " ")));
		List<Map<String, Object>> answers = listOf(structuredOf(miss), "results");

		assertThat(answers).as("one answer per name").hasSize(2);
		assertThat(answers.get(0)).as("miss status").containsEntry("status", "NOT_FOUND");
		assertThat(answers.get(0)).as("miss name").containsEntry("name", "org.example.Nope");
		assertThat(answers.get(0).get("suggestions")).as("no suggestions").isEqualTo(List.of());
		assertThat(answers.get(1)).as("unusable item failed").containsEntry("status", "ERROR");
		assertThat(answers.get(1)).as("no name for an unusable item").doesNotContainKey("name");

		CallToolResult allInvalid = tool.call(Map.of("projectPath", project.toString(), "names", List.of(" ")));
		List<Map<String, Object>> invalid = listOf(structuredOf(allInvalid), "results");
		assertThat(invalid).as("one answer per name").hasSize(1);
		assertThat(invalid.get(0)).as("unusable item failed").containsEntry("status", "ERROR");

		SuggestImportsTool failing = failingTool();
		CallToolResult failure = failing.call(
				Map.of("projectPath", project.toString(), "names", List.of("com.example.Widget")));
		Map<String, Object> structured = structuredOf(failure);

		assertThat(failure.isError()).as("resolver failure fails the call").isTrue();
		assertThat(structured).as("status").containsEntry("status", "ERROR");
		assertThat((String) structured.get("message")).as("message").contains("suggest failed");
		assertThat(structured).as("a failure carries no verdict").doesNotContainKey("suggestions");
	}

	private SuggestImportsTool tool() throws Exception {
		Path jar = project.resolve("dep.jar");
		if (!Files.exists(jar)) {
			try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
				zip.putNextEntry(new ZipEntry("META-INF/"));
				zip.closeEntry();
			}
		}
		JarIndexStore store = openStore(state.resolve("suggest-index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false),
				new Symbol("com.example.WidgetHelper", SymbolKind.CLASS, null, null, false, "", false));
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
		return new SuggestImportsTool(new SuggestService(store, indexer, resolver));
	}

	private SuggestImportsTool failingTool() throws Exception {
		JarIndexStore store = openStore(state.resolve("failing-suggest"));
		LibraryIndexer indexer = jarFile -> List.of();
		DependencyResolver resolver = (projectDir, allowNetwork) -> {
			throw new IOException("no network");
		};
		return new SuggestImportsTool(new SuggestService(store, indexer, resolver));
	}

	private JarIndexStore openStore(Path dir) throws IOException {
		JarIndexStore created = new JarIndexStore(dir);
		openStores.add(created);
		return created;
	}

	private static String textOf(CallToolResult result) {
		assertThat(result.isError()).as("no tool error").isFalse();
		return ((TextContent) result.content().get(0)).text();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> structuredOf(CallToolResult result) {
		assertThat(result.structuredContent()).as("structured content present").isInstanceOf(Map.class);
		return (Map<String, Object>) result.structuredContent();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> listOf(Map<String, Object> structured, String key) {
		Object value = structured.get(key);
		assertThat(value).as("list under " + key).isInstanceOf(List.class);
		return (List<Map<String, Object>>) value;
	}
}

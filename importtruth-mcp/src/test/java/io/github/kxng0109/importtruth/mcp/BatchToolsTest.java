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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies batch answers match one-off answers line for line,
 * plus limits and rejections.
 */
@DisplayName("Batch tools")
final class BatchToolsTest {

	@TempDir
	private Path project;

	@TempDir
	private Path state;

	@Test
	@DisplayName("batches found, missing, and broken items")
	void batchesMixedItems() throws Exception {
		Services services = services();
		LookupSymbolsTool lookup = new LookupSymbolsTool(services.lookup());
		String missing = project.resolve("Missing.java").toString();
		Files.write(project.resolve("Missing.java"),
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }"
						.getBytes(StandardCharsets.UTF_8));
		Path clean = file("Clean.java",
				"package com.other; import java.util.List; public class Clean { List<String> items; }");
		CheckFilesTool check = filesTool();

		String answers = textOf(lookup.call(Map.of("projectPath", project.toString(),
				"symbols", List.of("com.example.Widget", "org.example.Widget", " ", 42))));

		assertThat(answers).as("found line").contains("FOUND DEFINITE com.example.Widget");
		assertThat(answers).as("missing line").contains("NOT_FOUND CANDIDATE org.example.Widget");
		assertThat(answers).as("blank item flagged").contains("ERROR symbol must be a non-blank string");

		String files = textOf(check.call(Map.of("projectPath", project.toString(),
				"filePaths", List.of(clean.toString(), missing, " ", 42))));

		assertThat(files).as("clean prefixed").contains("clean: " + clean);
		assertThat(files).as("missing finding").contains("MISSING").contains("org.example.Nope");
		assertThat(files).as("blank item flagged").contains("ERROR file path must be a non-blank string");
	}

	@Test
	@DisplayName("matches single-shot answers exactly")
	void matchesSingleShots() throws Exception {
		Services services = services();
		LookupTool singleLookup = new LookupTool(services.lookup());
		LookupSymbolsTool batchLookup = new LookupSymbolsTool(services.lookup());
		Path clean = file("Clean.java",
				"package com.other; import java.util.List; public class Clean { List<String> items; }");
		Path jar = project.resolve("dep.jar");
		CheckFileTool singleCheck = new CheckFileTool(new JarIndexStore(state.resolve("single-check")),
				jarFile -> List.of(
						new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false)),
				(projectDir, allowNetwork) -> List.of(jar), new JdkIndex());
		CheckFilesTool batchCheck = filesTool();

		String single = textOf(singleLookup
				.call(Map.of("projectPath", project.toString(), "symbol", "org.example.Widget")));
		String batch = textOf(batchLookup
				.call(Map.of("projectPath", project.toString(), "symbols", List.of("org.example.Widget"))));

		assertThat(batch).as("lookup batch equals single").isEqualTo(single);

		String singleFile = textOf(singleCheck
				.call(Map.of("projectPath", project.toString(), "filePath", clean.toString())));
		String batchFile = textOf(batchCheck
				.call(Map.of("projectPath", project.toString(), "filePaths", List.of(clean.toString()))));

		assertThat(batchFile).as("check batch prefixes clean").isEqualTo("clean: " + clean);
		assertThat(singleFile).as("single clean unprefixed").isEqualTo("clean");
	}

	@Test
	@DisplayName("rejects bad batches and reports failing resolvers per item")
	@SuppressWarnings("DataFlowIssue")
	void rejectsBadBatches() throws Exception {
		Services services = services();
		LookupSymbolsTool lookup = new LookupSymbolsTool(services.lookup());
		CheckFilesTool check = filesTool();

		assertThat(lookup.call(Map.of("projectPath", project.toString())).isError())
				.as("missing symbols rejected")
				.isTrue();
		assertThat(lookup.call(Map.of("projectPath", project.toString(), "symbols", List.of())).isError())
				.as("empty symbols rejected")
				.isTrue();
		assertThat(lookup.call(Map.of("projectPath", " ", "symbols", List.of("a.B"))).isError())
				.as("blank project rejected")
				.isTrue();
		assertThat(lookup.call(Map.of("projectPath", 42, "symbols", List.of("a.B"))).isError())
				.as("non-string project rejected")
				.isTrue();
		assertThat(lookup.call(Map.of("projectPath", project.toString(), "symbols", "nope")).isError())
				.as("non-list symbols rejected")
				.isTrue();
		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i <= LookupSymbolsTool.MAX_SYMBOLS; i++) {
			tooMany.add("com.example.Widget" + i);
		}
		assertThat(lookup.call(Map.of("projectPath", project.toString(), "symbols", tooMany)).isError())
				.as("oversize batch rejected")
				.isTrue();
		assertThatThrownBy(() -> lookup.call(null)).as("null rejection")
				.isInstanceOf(NullPointerException.class);

		assertThat(check.call(Map.of("projectPath", project.toString())).isError())
				.as("missing files rejected")
				.isTrue();
		assertThat(check.call(Map.of("projectPath", project.toString(), "filePaths", List.of())).isError())
				.as("empty files rejected")
				.isTrue();
		List<String> tooManyFiles = new ArrayList<>();
		for (int i = 0; i <= CheckFilesTool.MAX_FILES; i++) {
			tooManyFiles.add("File" + i + ".java");
		}
		assertThat(check.call(Map.of("projectPath", project.toString(), "filePaths", tooManyFiles)).isError())
				.as("oversize batch rejected")
				.isTrue();
		assertThatThrownBy(() -> check.call(null)).as("null rejection")
				.isInstanceOf(NullPointerException.class);

		LookupSymbolsTool failingLookup =
				new LookupSymbolsTool(failingLookupService());
		String failure = textOf(failingLookup.call(
				Map.of("projectPath", project.toString(), "symbols", List.of("com.example.Widget"))));

		assertThat(failure).as("resolver failure per item").startsWith("ERROR com.example.Widget: ");

		CheckFilesTool failingCheck = failingFilesTool();
		Path file = file("Missing.java",
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");
		String checkFailure = textOf(failingCheck.call(
				Map.of("projectPath", project.toString(), "filePaths", List.of(file.toString()))));

		assertThat(checkFailure).as("check failure per item").startsWith("ERROR " + file);
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

	private CheckFilesTool filesTool() throws Exception {
		Path jar = project.resolve("dep.jar");
		if (!Files.exists(jar)) {
			try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
				zip.putNextEntry(new ZipEntry("META-INF/"));
				zip.closeEntry();
			}
		}
		JarIndexStore store = new JarIndexStore(state.resolve("check-index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false));
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
		return new CheckFilesTool(store, indexer, resolver, new JdkIndex());
	}

	private LookupService failingLookupService() throws Exception {
		JarIndexStore store = new JarIndexStore(state.resolve("failing-index"));
		LibraryIndexer indexer = jarFile -> List.of();
		DependencyResolver resolver = (projectDir, allowNetwork) -> {
			throw new IOException("no network");
		};
		return new LookupService(store, indexer, resolver, new JdkIndex());
	}

	private CheckFilesTool failingFilesTool() throws Exception {
		JarIndexStore store = new JarIndexStore(state.resolve("failing-check"));
		LibraryIndexer indexer = jarFile -> List.of();
		DependencyResolver resolver = (projectDir, allowNetwork) -> {
			throw new IOException("no network");
		};
		return new CheckFilesTool(store, indexer, resolver, new JdkIndex());
	}

	private Path file(String name, String content) throws Exception {
		Path dir = project.resolve("src/main/java/com/other");
		Files.createDirectories(dir);
		Path file = dir.resolve(name);
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	private static String textOf(CallToolResult result) {
		assertThat(result.isError()).as("no tool error").isFalse();
		return ((TextContent) result.content().get(0)).text();
	}

	private record Services(LookupService lookup, SearchService search) {
	}
}

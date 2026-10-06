package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

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
 * Verifies the check tool: missing lines, policy lines, clean files, errors.
 */
@DisplayName("CheckFileTool")
final class CheckFileToolTest {

	@TempDir
	private Path project;

	@TempDir
	private Path state;

	@Test
	@DisplayName("reports missing, policy, and clean")
	void reportsFindings() throws Exception {
		CheckFileTool tool = tool();
		Path missing = file("Missing.java",
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");
		Path clean = file("Clean.java",
				"package com.other; import java.util.List; public class Clean { List<String> items; }");

		String absent = textOf(tool.call(Map.of("projectPath", project.toString(), "filePath", missing.toString())));
		assertThat(absent).as("missing line").contains("MISSING").contains("org.example.Nope");

		String renamed = textOf(tool.call(Map.of("projectPath", project.toString(), "filePath", legacy.toString())));
		assertThat(renamed).as("policy line").contains("POLICY").contains("tools.jackson.databind.ObjectMapper");

		String spotless = textOf(tool.call(Map.of("projectPath", project.toString(), "filePath", clean.toString())));
		assertThat(spotless).as("clean answer").isEqualTo("clean");
	}

	@Test
	@DisplayName("rejects bad arguments")
	void rejectsBadArguments() {
		CheckFileTool tool = tool();

		CallToolResult result = tool.call(Map.of("projectPath", project.toString()));

		assertThat(result.isError()).as("error flag").isTrue();
	}

	private CheckFileTool tool() {
		try {
			Path jar = project.resolve("dep.jar");
			try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
				zip.putNextEntry(new ZipEntry("META-INF/"));
				zip.closeEntry();
			}
			JarIndexStore store = new JarIndexStore(state.resolve("index"));
			LibraryIndexer indexer = jarFile -> List.of(
					new Symbol("com.fasterxml.jackson.databind.ObjectMapper", SymbolKind.CLASS, null, null, false,
							"", false),
					new Symbol("tools.jackson.databind.ObjectMapper", SymbolKind.CLASS, null, null, false, "", false));
			DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
			return new CheckFileTool(store, indexer, resolver, new JdkIndex());
		} catch (Exception failure) {
			throw new AssertionError("Fixture setup failed", failure);
		}
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
}

package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
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
import java.nio.file.Paths;
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
 * Verifies the check tool: missing lines, policy lines, clean files, errors.
 */
@DisplayName("CheckFileTool")
final class CheckFileToolTest {

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

	private JarIndexStore openStore(Path dir) throws IOException {
		JarIndexStore created = new JarIndexStore(dir);
		openStores.add(created);
		return created;
	}

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
	@DisplayName("rejects calls missing the file path")
	void rejectsMissingFile() {
		CheckFileTool tool = tool();

		CallToolResult result = tool.call(Map.of("projectPath", project.toString()));

		assertThat(result.isError()).as("error flag").isTrue();
	}

	@Test
	@DisplayName("rejects null, blank, and mistyped arguments")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullAndBlank() {
		CheckFileTool tool = tool();

		assertThatThrownBy(() -> tool.call(null)).as("null rejection")
				.isInstanceOf(NullPointerException.class);
		assertThat(tool.call(Map.of("projectPath", " ", "filePath", "x")).isError())
				.as("blank project rejected")
				.isTrue();
		assertThat(tool.call(Map.of("projectPath", project.toString(), "filePath", " ")).isError())
				.as("blank file rejected")
				.isTrue();
		assertThat(tool.call(Map.of("projectPath", project.toString(), "filePath", 42)).isError())
				.as("non-string file rejected")
				.isTrue();
		assertThat(tool.call(Map.of("projectPath", 42, "filePath", "x")).isError())
				.as("non-string project rejected")
				.isTrue();
		assertThat(tool.call(Map.of("filePath", "x")).isError()).as("missing project rejected").isTrue();
	}

	@Test
	@DisplayName("validates pack targets against the JDK")
	void validatesJdkTargets() throws Exception {
		Files.write(project.resolve(".importtruth.yml"),
				"renames:\n  - from: com.example.Old\n    to: java.util.List\n".getBytes(StandardCharsets.UTF_8));
		CheckFileTool tool = tool();
		Path clean = file("Clean.java",
				"package com.other; import java.util.List; public class Clean { List<String> items; }");

		assertThat(textOf(tool.call(Map.of("projectPath", project.toString(), "filePath", clean.toString()))))
				.as("jdk target keeps the rule check honest")
				.isEqualTo("clean");
	}

	@Test
	@DisplayName("matches sub-packages of own code")
	void matchesOwnSubPackages() throws Exception {
		Path own = project.resolve("src/main/java/com/fasterxml/jackson/databind/impl");
		Files.createDirectories(own);
		Files.write(own.resolve("Bar.java"),
				"package com.fasterxml.jackson.databind.impl; public class Bar { }".getBytes(StandardCharsets.UTF_8));
		CheckFileTool tool = tool();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");

		assertThat(textOf(tool.call(Map.of("projectPath", project.toString(), "filePath", legacy.toString()))))
				.as("sub-package keeps the rule alive")
				.contains("POLICY");
	}
	@Test
	@DisplayName("reports broken files as errors and resolver failures as errors")
	void reportsUnhealthyAndFailures() throws Exception {
		CheckFileTool tool = tool();
		Path broken = file("Broken.java",
				"package com.other; import java.util.List; public class Broken { void x( { } }");

		assertThat(textOf(tool.call(Map.of("projectPath", project.toString(), "filePath", broken.toString()))))
				.as("unhealthy file flagged")
				.startsWith("ERROR ")
				.contains("Broken.java");

		CheckFileTool failing = failingTool();
		Path missing = file("Missing.java",
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");
		CallToolResult result =
				failing.call(Map.of("projectPath", project.toString(), "filePath", missing.toString()));

		assertThat(result.isError()).as("resolver failure flagged").isTrue();
	}

	@Test
	@DisplayName("prefers the project override pack")
	void prefersOverridePack() throws Exception {
		Files.write(project.resolve(".importtruth.yml"),
				"allow:\n  - package: com.example.keep\n".getBytes(StandardCharsets.UTF_8));
		CheckFileTool tool = tool();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");

		assertThat(textOf(tool.call(Map.of("projectPath", project.toString(), "filePath", legacy.toString()))))
				.as("override pack drops the rename rule")
				.isEqualTo("clean");
	}

	@Test
	@DisplayName("keeps own packages and survives corrupt indexes")
	void keepsOwnAndToleratesCorruption() throws Exception {
		Path own = project.resolve("src/main/java/com/fasterxml/jackson/databind");
		Files.createDirectories(own);
		Files.write(own.resolve("Foo.java"),
				"package com.fasterxml.jackson.databind; public class Foo { }".getBytes(StandardCharsets.UTF_8));
		CheckFileTool tool = tool();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");

		assertThat(textOf(tool.call(Map.of("projectPath", project.toString(), "filePath", legacy.toString()))))
				.as("own package keeps the rule alive")
				.contains("POLICY");

		closeStores();
		try (var indexes = Files.list(state.resolve("index"))) {
			for (Path db : indexes.filter(p -> p.toString().endsWith(".idx")).toList()) {
				Files.write(db, "corrupt".getBytes(StandardCharsets.UTF_8));
			}
		}
		CheckFileTool reopened = tool();
		CallToolResult result =
				reopened.call(Map.of("projectPath", project.toString(), "filePath", legacy.toString()));

		assertThat(result.isError()).as("corrupt index flagged").isTrue();
	}

	@Test
	@DisplayName("rejects files outside the project")
	void rejectsOutsideProject() throws Exception {
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");

		CallToolResult result = tool().call(
				Map.of("projectPath", "rel-proj", "filePath", legacy.toAbsolutePath().toString()));

		assertThat(result.isError()).as("outside file flagged").isTrue();
		assertThat(((TextContent) result.content().get(0)).text())
				.as("names the violation")
				.contains("outside the project");
	}

	@Test
	@DisplayName("carries the verdict of a clean file")
	void carriesCleanVerdict() throws Exception {
		CheckFileTool tool = tool();
		Path clean = file("Clean.java",
				"package com.other; import java.util.List; public class Clean { List<String> items; }");

		CallToolResult result = tool.call(Map.of("projectPath", project.toString(), "filePath", clean.toString()));
		Map<String, Object> structured = structuredOf(result);

		assertThat(textOf(result)).as("rendered text").isEqualTo("clean");
		assertThat(structured).as("status").containsEntry("status", "CLEAN");
		assertThat(structured).as("file").containsEntry("file", clean.toString());
		assertThat(structured).as("finding count").containsEntry("findingCount", 0);
		assertThat(structured.get("findings")).as("no findings").isEqualTo(List.of());
	}

	@Test
	@DisplayName("carries every finding of a file")
	void carriesFindings() throws Exception {
		CheckFileTool tool = tool();
		Path missing = file("Missing.java",
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");

		CallToolResult result = tool.call(Map.of("projectPath", project.toString(), "filePath", missing.toString()));
		Map<String, Object> structured = structuredOf(result);
		List<Map<String, Object>> findings = listOf(structured, "findings");

		assertThat(textOf(result)).as("rendered text").contains("MISSING").contains("org.example.Nope");
		assertThat(structured).as("status").containsEntry("status", "FINDINGS");
		assertThat(structured).as("finding count").containsEntry("findingCount", 1);
		assertThat(findings).as("one finding").hasSize(1);
		assertThat(findings.get(0)).as("kind").containsEntry("kind", "MISSING");
		assertThat(findings.get(0)).as("a missing import does not resolve").containsEntry("resolved", false);
		assertThat(findings.get(0)).as("line").containsEntry("line", 2);
		assertThat(findings.get(0)).as("text")
				.containsEntry("text", "import org.example.Nope resolves nowhere");
		assertThat(findings.get(0)).as("no suggestion without candidates").doesNotContainKey("suggestion");
	}

	@Test
	@DisplayName("marks a policy finding as resolved")
	void marksPolicyFindingResolved() throws Exception {
		CheckFileTool tool = tool();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");

		CallToolResult result = tool.call(Map.of("projectPath", project.toString(), "filePath", legacy.toString()));
		List<Map<String, Object>> findings = listOf(structuredOf(result), "findings");

		assertThat(findings).as("one finding").hasSize(1);
		assertThat(findings.get(0)).as("kind").containsEntry("kind", "POLICY");
		assertThat(findings.get(0)).as("a policy hit is a resolved import").containsEntry("resolved", true);
		assertThat(findings.get(0)).as("line").containsEntry("line", 1);
		assertThat((String) findings.get(0).get("text")).as("detail").contains("tools.jackson.databind");
		assertThat((String) findings.get(0).get("suggestion")).as("suggestion")
				.contains("tools.jackson.databind.ObjectMapper");
		assertThat(textOf(result)).as("rendered text carries the same detail")
				.contains((String) findings.get(0).get("text"));
	}

	@Test
	@DisplayName("leaves an unverified finding without a verdict")
	void leavesUnverifiedFindingOpen() throws Exception {
		CheckFileTool tool = tool();
		Path own = file("Own.java", "package com.other;\nimport com.other.Own;\npublic class Own { }");

		CallToolResult result = tool.call(Map.of("projectPath", project.toString(), "filePath", own.toString()));
		List<Map<String, Object>> findings = listOf(structuredOf(result), "findings");

		assertThat(findings).as("one finding").hasSize(1);
		assertThat(findings.get(0)).as("kind").containsEntry("kind", "CANDIDATE");
		assertThat(findings.get(0)).as("an unverified import has no verdict").doesNotContainKey("resolved");
		assertThat((String) findings.get(0).get("text")).as("detail")
				.isEqualTo("import com.other.Own project-owned or generated, unverified");
	}

	@Test
	@DisplayName("marks a failed call as an error")
	void marksFailedCall() throws Exception {
		CheckFileTool failing = failingTool();
		Path missing = file("Missing.java",
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");

		CallToolResult result = failing.call(Map.of("projectPath", project.toString(), "filePath", missing.toString()));
		Map<String, Object> structured = structuredOf(result);

		assertThat(result.isError()).as("error flag").isTrue();
		assertThat(structured).as("status").containsEntry("status", "ERROR");
		assertThat((String) structured.get("message")).as("message").contains("check failed");
		assertThat(structured).as("a failure carries no verdict").doesNotContainKey("findings");
	}

	@Test
	@DisplayName("marks a rejected call as an error")
	void marksRejectedCall() {
		CheckFileTool tool = tool();

		CallToolResult result = tool.call(Map.of("projectPath", project.toString()));
		Map<String, Object> structured = structuredOf(result);

		assertThat(result.isError()).as("error flag").isTrue();
		assertThat(structured).as("status").containsEntry("status", "ERROR");
		assertThat((String) structured.get("message")).as("message").contains("required strings");
	}

	@Test
	@DisplayName("marks an unreadable file as an error")
	void marksUnreadableFile() throws Exception {
		CheckFileTool tool = tool();
		Path broken = file("Broken.java",
				"package com.other; import java.util.List; public class Broken { void x( { } }");

		CallToolResult result = tool.call(Map.of("projectPath", project.toString(), "filePath", broken.toString()));
		Map<String, Object> structured = structuredOf(result);

		assertThat(textOf(result)).as("rendered text").startsWith("ERROR ").contains("Broken.java");
		assertThat(structured).as("status").containsEntry("status", "ERROR");
		assertThat(structured).as("file").containsEntry("file", broken.toString());
		assertThat((String) structured.get("message")).as("message").contains("could not be parsed");
	}

	private CheckFileTool tool() {
		try {
			Path jar = project.resolve("dep.jar");
			try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
				ZipEntry meta = new ZipEntry("META-INF/");
				meta.setTime(0);
				zip.putNextEntry(meta);
				zip.closeEntry();
			}
			JarIndexStore store = openStore(state.resolve("index"));
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

	private CheckFileTool failingTool() {
		try {
			JarIndexStore store = openStore(state.resolve("failing-index"));
			LibraryIndexer indexer = jarFile -> List.of();
			DependencyResolver resolver = (projectDir, allowNetwork) -> {
				throw new IOException("no network");
			};
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

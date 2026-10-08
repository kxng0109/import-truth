package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks many files in one call. Each file keeps the exact lines the
 * single-file tool would print, prefixed only where the single answer
 * would be ambiguous in a batch.
 */
public final class CheckFilesTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "check_files";

	/** Maximum files per call. Keeps answers small enough to read. */
	static final int MAX_FILES = 50;

	private final CheckFileTool check;

	/**
	 * Creates the tool.
	 *
	 * @param store    index store, never null
	 * @param indexer  library extractor, never null
	 * @param resolver dependency resolver, never null
	 * @param jdk      JDK index, never null
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public CheckFilesTool(
			JarIndexStore store, LibraryIndexer indexer, DependencyResolver resolver, JdkIndex jdk) {
		this.check = new CheckFileTool(
				Objects.requireNonNull(store, "store"),
				Objects.requireNonNull(indexer, "indexer"),
				Objects.requireNonNull(resolver, "resolver"),
				Objects.requireNonNull(jdk, "jdk"));
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return batch check tool definition
	 */
	public McpSchema.Tool definition() {
		return McpSchema.Tool.builder(
						TOOL_NAME,
						Map.of(
								"type",
								"object",
								"properties",
								Map.of(
										"projectPath", Map.of("type", "string"),
										"filePaths", Map.of("type", "array", "items", Map.of("type", "string"))),
								"required",
								List.of("projectPath", "filePaths")))
				.description("Checks many Java files' imports against the resolved classpath in one call."
						+ " One resolver, index, or pack failure fails the whole call with isError=true;"
						+ " invalid items become inline ERROR lines"
						+ " (an all-invalid list returns success carrying only ERROR lines);"
						+ " clean files render as \"clean: <path>\".")
				.build();
	}

	/**
	 * Handles a batch check call. One resolver, index, or pack failure fails
	 * the whole call with {@code isError=true}; invalid items become inline
	 * {@code ERROR} lines, so an all-invalid list returns success carrying
	 * only {@code ERROR} lines. Clean files render as {@code "clean: <path>"}.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one block per file, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Object project = arguments.get("projectPath");
		Object raw = arguments.get("filePaths");
		if (!(project instanceof String projectPath) || projectPath.isBlank()) {
			return error("projectPath is a required string");
		}
		if (!(raw instanceof List<?> files) || files.isEmpty()) {
			return error("filePaths is a required non-empty array");
		}
		if (files.size() > MAX_FILES) {
			return error("at most " + MAX_FILES + " files per call");
		}
		List<Path> valid = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		for (Object item : files) {
			if (!(item instanceof String filePath) || filePath.isBlank()) {
				lines.add("ERROR file path must be a non-blank string");
				continue;
			}
			valid.add(Paths.get(filePath));
		}
		if (valid.isEmpty()) {
			McpSchema.TextContent onlyErrors =
					McpSchema.TextContent.builder(String.join("\n", lines)).build();
			return new McpSchema.CallToolResult(List.<McpSchema.Content>of(onlyErrors), false, null, null);
		}
		try {
			Map<Path, List<String>> answers = check.checkFiles(Paths.get(projectPath), valid);
			for (Map.Entry<Path, List<String>> entry : answers.entrySet()) {
				if (entry.getValue().isEmpty()) {
					lines.add("clean: " + entry.getKey());
				} else {
					lines.addAll(entry.getValue());
				}
			}
			McpSchema.TextContent text = McpSchema.TextContent.builder(String.join("\n", lines)).build();
			return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), false, null, null);
		} catch (Exception failure) {
			return error("check failed: " + failure.getMessage());
		}
	}

	private static McpSchema.CallToolResult error(String message) {
		McpSchema.TextContent text = McpSchema.TextContent.builder(message).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), true, null, null);
	}
}

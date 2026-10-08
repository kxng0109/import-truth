package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.SuggestService;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Suggests dependency names for many wanted imports in one call.
 * Each item keeps one block so batches stay readable; bad items
 * become inline error lines while good items still answer.
 */
public final class SuggestImportsTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "suggest_imports";

	/** Maximum names per call. Keeps answers small enough to read. */
	static final int MAX_NAMES = 50;

	/** Default and maximum suggestion counts. */
	static final int DEFAULT_LIMIT = 5;
	static final int MAX_LIMIT = 25;

	private final SuggestService suggest;

	/**
	 * Creates the tool.
	 *
	 * @param suggest backing service, never null
	 * @throws NullPointerException if {@code suggest} is {@code null}
	 */
	public SuggestImportsTool(SuggestService suggest) {
		this.suggest = Objects.requireNonNull(suggest, "suggest");
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return suggest tool definition
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
										"names", Map.of("type", "array", "items", Map.of("type", "string")),
										"limit", Map.of("type", "integer", "default", DEFAULT_LIMIT)),
								"required",
								List.of("projectPath", "names")))
				.description("Suggests dependency names for many wanted imports in one call."
						+ " Resolver failures fail the whole call with isError=true;"
						+ " blank or mistyped items become inline ERROR lines"
						+ " (an all-invalid list returns success carrying only ERROR lines);"
						+ " empty matches render as \"no suggestions for <name>\".")
				.build();
	}

	/**
	 * Handles a batch suggest call. Resolver failures fail the whole
	 * call with {@code isError=true}; blank or mistyped items become
	 * inline {@code ERROR} lines inside a success result.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one block per name, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<List<?>> raw = McpArgs.strings(arguments, "names");
		if (project.isEmpty()) {
			return McpResults.err("projectPath is a required string");
		}
		if (raw.isEmpty()) {
			return McpResults.err("names is a required non-empty array");
		}
		List<?> items = raw.get();
		if (items.size() > MAX_NAMES) {
			return McpResults.err("at most " + MAX_NAMES + " names per call");
		}
		int limit = limitOf(arguments.get("limit"));
		List<String> valid = new ArrayList<>();
		for (Object item : items) {
			if (item instanceof String name && !name.isBlank()) {
				valid.add(name);
			}
		}
		if (valid.isEmpty()) {
			List<String> errors = new ArrayList<>();
			for (Object item : items) {
				if (!(item instanceof String name) || name.isBlank()) {
					errors.add("ERROR name must be a non-blank string");
				}
			}
			return McpResults.ok(errors);
		}
		Map<String, List<String>> answers;
		try {
			answers = suggest.suggestBatch(Paths.get(project.get()), valid, limit);
		} catch (Exception failure) {
			return McpResults.err("suggest failed: " + failure);
		}
		List<String> lines = new ArrayList<>();
		for (Object item : items) {
			if (!(item instanceof String name) || name.isBlank()) {
				lines.add("ERROR name must be a non-blank string");
				continue;
			}
			List<String> found = answers.getOrDefault(name, List.of());
			if (found.isEmpty()) {
				lines.add("no suggestions for " + name);
			} else {
				lines.add("suggestions for " + name + ":");
				for (String candidate : found) {
					lines.add("maybe: " + candidate);
				}
			}
		}
		return McpResults.ok(lines);
	}

	static int limitOf(Object raw) {
		return McpArgs.boundedLimit(raw, DEFAULT_LIMIT, MAX_LIMIT);
	}
}

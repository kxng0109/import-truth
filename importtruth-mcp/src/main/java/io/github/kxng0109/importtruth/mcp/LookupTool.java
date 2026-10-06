package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.model.LookupResult;
import io.github.kxng0109.importtruth.model.Symbol;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Answers whether a symbol resolves on a project's classpath.
 */
public final class LookupTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "lookup_symbol";

	private final LookupService lookup;

	/**
	 * Creates the tool.
	 *
	 * @param lookup backing service, never null
	 * @throws NullPointerException if {@code lookup} is {@code null}
	 */
	public LookupTool(LookupService lookup) {
		this.lookup = Objects.requireNonNull(lookup, "lookup");
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return lookup tool definition
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
										"symbol", Map.of("type", "string")),
								"required",
								List.of("projectPath", "symbol")))
				.description("Checks whether a fully qualified name resolves on the project classpath.")
				.build();
	}

	/**
	 * Handles a lookup call.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with the verdict lines, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Object project = arguments.get("projectPath");
		Object symbol = arguments.get("symbol");
		if (!(project instanceof String projectPath) || projectPath.isBlank()
				|| !(symbol instanceof String name) || name.isBlank()) {
			return error("projectPath and symbol are required strings");
		}
		LookupResult answer;
		try {
			answer = lookup.lookup(Paths.get(projectPath), name);
		} catch (Exception failure) {
			return error("lookup failed: " + failure.getMessage());
		}
		List<String> lines = new ArrayList<>();
		if (!answer.found()) {
			lines.add("NOT_FOUND " + answer.confidence().name() + " " + name);
			for (String suggestion : answer.suggestions()) {
				lines.add("maybe: " + suggestion);
			}
		} else if (answer.fromJdk()) {
			lines.add("FOUND definite " + name + " (jdk)");
		} else {
			lines.add("FOUND " + answer.confidence().name() + " " + name);
			for (Symbol match : answer.matches()) {
				StringBuilder row = new StringBuilder("match: ").append(match.fqn())
						.append(" (").append(match.kind().name()).append(')');
				if (match.signature() != null) {
					row.append(' ').append(match.signature());
				}
				if (match.deprecated()) {
					row.append(" deprecated");
					if (!match.deprecatedSince().isEmpty()) {
						row.append(" since ").append(match.deprecatedSince());
					}
					if (match.forRemoval()) {
						row.append(" for-removal");
					}
				}
				lines.add(row.toString());
			}
		}
		McpSchema.TextContent text = McpSchema.TextContent.builder(String.join("\n", lines)).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), false, null, null);
	}

	private static McpSchema.CallToolResult error(String message) {
		McpSchema.TextContent text = McpSchema.TextContent.builder(message).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), true, null, null);
	}
}

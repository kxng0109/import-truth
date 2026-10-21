package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.model.LookupResult;
import io.github.kxng0109.importtruth.model.Symbol;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<String> symbol = McpArgs.string(arguments, "symbol");
		if (project.isEmpty() || symbol.isEmpty()) {
			String message = "projectPath and symbol are required strings";
			return McpResults.err(message, McpResults.failure(message));
		}
		String projectPath = project.get();
		String name = symbol.get();
		LookupResult answer;
		try {
			answer = lookup.lookup(Paths.get(projectPath), name);
		} catch (Exception failure) {
			String message = "lookup failed: " + failure;
			return McpResults.err(message, McpResults.failure(message));
		}
		return McpResults.ok(lines(name, answer), structured(name, answer));
	}

	/**
	 * Renders one answer with the exact single symbol lines. A miss
	 * carries no confidence token: a miss can only ever be a candidate,
	 * so the token held no information and read to a model as "it
	 * probably exists".
	 *
	 * @param name wanted fully qualified name, never null
	 * @param answer lookup answer, never null
	 * @return verdict lines, never null
	 */
	public List<String> lines(String name, LookupResult answer) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(answer, "answer");
		List<String> lines = new ArrayList<>();
		if (!answer.found()) {
			if (answer.jdkChecked()) {
				lines.add("NOT_FOUND " + name);
			} else {
				// The JDK API could not be consulted, so this miss is
				// not proven: it must not read as a verdict.
				lines.add("UNVERIFIED " + name);
				lines.add("not in the dependency classpath; the JDK API index is unavailable in this build");
			}
			for (String suggestion : answer.suggestions()) {
				lines.add("maybe: " + suggestion);
			}
		} else if (answer.fromJdk()) {
			lines.add("FOUND DEFINITE " + name + " (jdk)");
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
		return lines;
	}

	/**
	 * Builds the machine readable answer for one symbol. The keys are
	 * the verdict, the confidence, the name that was asked about, the
	 * artifact the hit came from when it is known, the matching
	 * symbols, and the suggestions of a miss. A key is present only
	 * when its value is known.
	 *
	 * @param name wanted fully qualified name, never null
	 * @param answer lookup answer, never null
	 * @return structured content, never null
	 */
	public Map<String, Object> structured(String name, LookupResult answer) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(answer, "answer");
		Map<String, Object> content = new LinkedHashMap<>();
		boolean verified = answer.found() || answer.jdkChecked();
		content.put("status", answer.found() ? "FOUND" : (verified ? "NOT_FOUND" : "UNVERIFIED"));
		content.put("confidence", answer.confidence().name());
		content.put("name", name);
		content.put("jdkChecked", answer.jdkChecked());
		if (!verified) {
			content.put("message",
					"the JDK API index is unavailable in this build, so this answer is unverified");
		}
		if (answer.fromJdk()) {
			content.put("library", "jdk");
		}
		List<Map<String, Object>> matches = new ArrayList<>();
		for (Symbol match : answer.matches()) {
			matches.add(McpResults.symbol(match));
		}
		content.put("matches", matches);
		content.put("suggestions", new ArrayList<>(answer.suggestions()));
		return content;
	}
}

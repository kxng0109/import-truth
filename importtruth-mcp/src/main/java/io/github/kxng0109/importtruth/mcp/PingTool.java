package io.github.kxng0109.importtruth.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import io.github.kxng0109.importtruth.core.PingService;
import io.github.kxng0109.importtruth.model.ApiInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The ping tool: proves the server is alive and reports its identity.
 */
public final class PingTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "ping";

	private final Supplier<ApiInfo> identity;

	/**
	 * Creates the tool.
	 *
	 * @param ping backing service, never null
	 * @throws NullPointerException if {@code ping} is {@code null}
	 */
	public PingTool(PingService ping) {
		this(Objects.requireNonNull(ping, "ping")::ping);
	}

	/**
	 * Creates the tool over a supplier of the server identity. The seam
	 * keeps the failure path of the tool reachable: a service that
	 * cannot answer must still answer with an error, never with a
	 * verdict.
	 *
	 * @param identity supplier of the server identity, never null
	 * @throws NullPointerException if {@code identity} is {@code null}
	 */
	PingTool(Supplier<ApiInfo> identity) {
		this.identity = Objects.requireNonNull(identity, "identity");
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return ping tool definition with an empty input schema
	 */
	public McpSchema.Tool definition() {
		return McpSchema.Tool.builder(TOOL_NAME, Map.of("type", "object", "properties", Map.of()))
				.description("Returns the importtruth server name and version.")
				.build();
	}

	/**
	 * Handles a ping call. The rendered text stays as it was; the
	 * structured content carries the status of the server and its
	 * identity. A service that cannot answer fails the call with an
	 * error, never with a verdict.
	 *
	 * @return successful result carrying name and version as text
	 */
	public McpSchema.CallToolResult call() {
		ApiInfo info;
		try {
			info = identity.get();
		} catch (RuntimeException failure) {
			String message = "ping failed: " + failure;
			return McpResults.err(message, McpResults.failure(message));
		}
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("status", "OK");
		content.put("name", info.name());
		content.put("version", info.version());
		return McpResults.ok(List.of(info.name() + " " + info.version()), content);
	}
}

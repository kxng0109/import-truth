package io.github.kxng0109.importtruth.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import io.github.kxng0109.importtruth.core.PingService;
import io.github.kxng0109.importtruth.model.ApiInfo;

import java.util.Map;
import java.util.Objects;

/**
 * The ping tool: proves the server is alive and reports its identity.
 */
public final class PingTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "ping";

	private final PingService ping;

	/**
	 * Creates the tool.
	 *
	 * @param ping backing service, never null
	 * @throws NullPointerException if {@code ping} is {@code null}
	 */
	public PingTool(PingService ping) {
		this.ping = Objects.requireNonNull(ping, "ping");
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
	 * Handles a ping call.
	 *
	 * @return successful result carrying name and version as text
	 */
	public McpSchema.CallToolResult call() {
		ApiInfo info = ping.ping();
		return McpResults.ok(info.name() + " " + info.version());
	}
}

package io.github.kxng0109.importtruth.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.core.PingService;
import io.github.kxng0109.importtruth.core.SearchService;
import io.github.kxng0109.importtruth.model.ApiInfo;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * MCP server over standard input and output. Standard output is the protocol
 * channel, so it must be supplied separately and never used for logging.
 */
public final class ImportTruthServer implements AutoCloseable {

	private final McpSyncServer server;

	/**
	 * Starts the server on the given streams.
	 *
	 * @param ping backing ping service, never null
	 * @param in   protocol input, never null
	 * @param out  protocol output, never null
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public ImportTruthServer(PingService ping, InputStream in, OutputStream out) {
		this(ping, null, null, in, out);
	}

	/**
	 * Starts the server with lookup and search tools.
	 *
	 * @param ping   backing ping service, never null
	 * @param lookup backing lookup service, null for ping only
	 * @param search backing search service, null for ping only
	 * @param in     protocol input, never null
	 * @param out    protocol output, never null
	 * @throws NullPointerException if {@code ping}, {@code in}, or {@code out} is {@code null}
	 */
	public ImportTruthServer(
			PingService ping, LookupService lookup, SearchService search, InputStream in, OutputStream out) {
		Objects.requireNonNull(ping, "ping");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		PingTool pingTool = new PingTool(ping);
		ApiInfo info = ping.ping();
		StdioServerTransportProvider provider =
				new StdioServerTransportProvider(McpJsonDefaults.getMapper(), in, out);
		this.server =
				McpServer.sync(provider)
						.serverInfo(info.name(), info.version())
						.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
						.build();
		server.addTool(
				McpServerFeatures.SyncToolSpecification.builder()
						.tool(pingTool.definition())
						.callHandler((exchange, request) -> pingTool.call())
						.build());
		if (lookup != null && search != null) {
			LookupTool lookupTool = new LookupTool(lookup);
			SearchTool searchTool = new SearchTool(search);
			server.addTool(
					McpServerFeatures.SyncToolSpecification.builder()
							.tool(lookupTool.definition())
							.callHandler((exchange, request) -> lookupTool.call(request.arguments()))
							.build());
			server.addTool(
					McpServerFeatures.SyncToolSpecification.builder()
							.tool(searchTool.definition())
							.callHandler((exchange, request) -> searchTool.call(request.arguments()))
							.build());
		}
	}

	@Override
	public void close() {
		server.close();
	}
}

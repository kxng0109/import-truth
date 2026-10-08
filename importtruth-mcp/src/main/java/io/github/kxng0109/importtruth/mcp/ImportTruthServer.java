package io.github.kxng0109.importtruth.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.core.PingService;
import io.github.kxng0109.importtruth.core.SearchService;
import io.github.kxng0109.importtruth.core.SuggestService;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
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
		this.server = boot(ping, in, out);
	}

	/**
	 * Starts the full server.
	 *
	 * @param ping     backing ping service, never null
	 * @param lookup   backing lookup service, never null
	 * @param search   backing search service, never null
	 * @param store    index store, never null
	 * @param indexer  library extractor, never null
	 * @param resolver dependency resolver, never null
	 * @param jdk      JDK index, never null
	 * @param in       protocol input, never null
	 * @param out      protocol output, never null
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public ImportTruthServer(
			PingService ping,
			LookupService lookup,
			SearchService search,
			JarIndexStore store,
			LibraryIndexer indexer,
			DependencyResolver resolver,
			JdkIndex jdk,
			InputStream in,
			OutputStream out) {
		Objects.requireNonNull(ping, "ping");
		Objects.requireNonNull(lookup, "lookup");
		Objects.requireNonNull(search, "search");
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(indexer, "indexer");
		Objects.requireNonNull(resolver, "resolver");
		Objects.requireNonNull(jdk, "jdk");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		this.server = boot(ping, in, out);
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
		LookupSymbolsTool lookupSymbolsTool = new LookupSymbolsTool(lookup);
		server.addTool(
				McpServerFeatures.SyncToolSpecification.builder()
						.tool(lookupSymbolsTool.definition())
						.callHandler((exchange, request) -> lookupSymbolsTool.call(request.arguments()))
						.build());
		CheckFileTool checkTool = new CheckFileTool(store, indexer, resolver, jdk);
		server.addTool(
				McpServerFeatures.SyncToolSpecification.builder()
						.tool(checkTool.definition())
						.callHandler((exchange, request) -> checkTool.call(request.arguments()))
						.build());
		CheckFilesTool checkFilesTool = new CheckFilesTool(store, indexer, resolver, jdk);
		server.addTool(
				McpServerFeatures.SyncToolSpecification.builder()
						.tool(checkFilesTool.definition())
						.callHandler((exchange, request) -> checkFilesTool.call(request.arguments()))
						.build());
		SuggestImportsTool suggestImportsTool =
				new SuggestImportsTool(new SuggestService(store, indexer, resolver));
		server.addTool(
				McpServerFeatures.SyncToolSpecification.builder()
						.tool(suggestImportsTool.definition())
						.callHandler((exchange, request) -> suggestImportsTool.call(request.arguments()))
						.build());
	}

	private static McpSyncServer boot(PingService ping, InputStream in, OutputStream out) {
		Objects.requireNonNull(ping, "ping");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		PingTool pingTool = new PingTool(ping);
		ApiInfo info = ping.ping();
		StdioServerTransportProvider provider =
				new StdioServerTransportProvider(McpJsonDefaults.getMapper(), in, out);
		McpSyncServer server = McpServer.sync(provider)
				.serverInfo(info.name(), info.version())
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		server.addTool(
				McpServerFeatures.SyncToolSpecification.builder()
						.tool(pingTool.definition())
						.callHandler((exchange, request) -> pingTool.call())
						.build());
		return server;
	}

	@Override
	public void close() {
		server.close();
	}
}

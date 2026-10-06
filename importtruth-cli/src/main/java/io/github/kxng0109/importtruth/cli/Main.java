package io.github.kxng0109.importtruth.cli;

import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.core.MavenResolver;
import io.github.kxng0109.importtruth.core.PingService;
import io.github.kxng0109.importtruth.core.SearchService;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.mcp.ImportTruthServer;
import io.github.kxng0109.importtruth.model.LibraryIndexer;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;

/**
 * Composition root. Wires core and MCP, then serves until stopped.
 */
public final class Main {

	private Main() {
	}

	/**
	 * Starts the MCP server on standard input and output, or checks files.
	 *
	 * @param args empty to serve, or {@code check <projectDir> <file>...}
	 * @throws Exception when the server thread is interrupted
	 */
	public static void main(String[] args) throws Exception {
		Path state = Paths.get(System.getProperty("user.home"), ".importtruth");
		JarIndexStore store = new JarIndexStore(state.resolve("index"));
		LibraryIndexer indexer = ServiceLoader.load(LibraryIndexer.class).findFirst().orElseThrow(
				() -> new IllegalStateException("No LibraryIndexer on the classpath"));
		MavenResolver resolver = new MavenResolver(state);
		JdkIndex jdk = new JdkIndex();
		if (args.length >= 3 && "check".equals(args[0])) {
			CheckCommand command = new CheckCommand(store, indexer, resolver, jdk);
			Path project = Paths.get(args[1]);
			int exit = 0;
			for (int i = 2; i < args.length; i++) {
				exit = Math.max(exit, command.run(System.out, System.err, project, Paths.get(args[i])));
			}
			System.exit(exit);
			return;
		}
		// Standard output is the protocol channel: keep it, then point all
		// later System.out writes (ours or any library's) at standard error.
		PrintStream protocolOut = System.out;
		System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
		// Version stamped by the build; never hardcoded.
		PingService ping = new PingService("importtruth", version());
		LookupService lookup = new LookupService(store, indexer, resolver, jdk);
		SearchService search = new SearchService(store, indexer, resolver);
		ImportTruthServer server = new ImportTruthServer(ping, lookup, search, store, indexer, resolver, jdk,
				System.in, protocolOut);
		CountDownLatch stop = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));
		try {
			stop.await();
		} finally {
			server.close();
		}
	}

	/**
	 * Reads the build-stamped version.
	 *
	 * @return implementation version, or a development marker outside the jar
	 */
	private static String version() {
		String stamped = Main.class.getPackage().getImplementationVersion();
		return stamped != null ? stamped : "0.0.0-dev";
	}
}

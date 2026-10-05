package io.github.kxng0109.importtruth.cli;

import io.github.kxng0109.importtruth.core.PingService;
import io.github.kxng0109.importtruth.mcp.ImportTruthServer;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * Composition root. Wires core and MCP, then serves until stopped.
 */
public final class Main {

	private Main() {
	}

	/**
	 * Starts the MCP server on standard input and output.
	 *
	 * @param args unused
	 * @throws Exception when the server thread is interrupted
	 */
	public static void main(String[] args) throws Exception {
		// Standard output is the protocol channel: keep it, then point all
		// later System.out writes (ours or any library's) at standard error.
		PrintStream protocolOut = System.out;
		System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
		// Must match the root pom version until the build stamps it (M1).
		PingService ping = new PingService("importtruth", "0.1.0-SNAPSHOT");
		ImportTruthServer server = new ImportTruthServer(ping, System.in, protocolOut);
		CountDownLatch stop = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));
		try {
			stop.await();
		} finally {
			server.close();
		}
	}
}

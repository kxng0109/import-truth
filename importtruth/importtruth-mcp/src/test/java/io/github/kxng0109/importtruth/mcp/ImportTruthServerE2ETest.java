package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.core.PingService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives the server over piped streams with real JSON-RPC frames.
 */
@DisplayName("ImportTruthServer end to end")
final class ImportTruthServerE2ETest {

	private static final String INITIALIZE =
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
					+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
					+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
	private static final String INITIALIZED =
			"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
	private static final String CALL_PING =
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
					+ "\"params\":{\"name\":\"ping\",\"arguments\":{}}}";

	@Test
	@DisplayName("answers initialize and ping call")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void answersInitializeAndPingCall() throws Exception {
		PipedOutputStream clientToServer = new PipedOutputStream();
		PipedInputStream serverIn = new PipedInputStream(clientToServer);
		PipedOutputStream serverToClient = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(serverToClient);
		BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		BlockingQueue<Throwable> readerErrors = new LinkedBlockingQueue<>();
		Thread reader =
				new Thread(
						() -> {
							try (BufferedReader in =
									new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8))) {
								String line;
								while ((line = in.readLine()) != null) {
									lines.add(line);
								}
							} catch (Exception e) {
								readerErrors.add(e);
							}
						});
		reader.setDaemon(true);
		PingService ping = new PingService("importtruth", "0.2.0-SNAPSHOT");
		try (ImportTruthServer server = new ImportTruthServer(ping, serverIn, serverToClient)) {
			reader.start();
			writeLine(clientToServer, INITIALIZE);
			assertThat(awaitLine(lines, "\"id\":1")).as("initialize answer").contains("importtruth");
			writeLine(clientToServer, INITIALIZED);
			writeLine(clientToServer, CALL_PING);
			assertThat(awaitLine(lines, "\"id\":2"))
					.as("ping call answer")
					.contains("importtruth 0.2.0-SNAPSHOT");
		} finally {
			clientToServer.close();
			serverToClient.close();
		}
		assertThat(readerErrors).as("reader thread errors").isEmpty();
	}

	private static void writeLine(PipedOutputStream out, String frame) throws Exception {
		out.write((frame + "\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static String awaitLine(BlockingQueue<String> lines, String fragment) throws Exception {
		long deadline = System.currentTimeMillis() + 15_000;
		StringBuilder seen = new StringBuilder();
		while (System.currentTimeMillis() < deadline) {
			String line = lines.poll(500, TimeUnit.MILLISECONDS);
			if (line == null) {
				continue;
			}
			seen.append(line).append('\n');
			if (line.contains(fragment)) {
				return line;
			}
		}
		throw new AssertionError("Timed out waiting for " + fragment + ". Seen:\n" + seen);
	}
}

package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.core.PingService;
import io.github.kxng0109.importtruth.core.SearchService;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the full four-tool server over pipes.
 */
@DisplayName("Full server end to end")
final class FullServerE2ETest {

	@TempDir
	private Path state;

	@TempDir
	private Path project;

	@Test
	@DisplayName("answers ping, lookup, search, and check calls")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void answersAllTools() throws Exception {
		Path jar = project.resolve("dep.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
		}
		Path file = project.resolve("Use.java");
		Files.write(file, "package com.other; import com.example.Widget; public class Use { Widget w; }"
				.getBytes(StandardCharsets.UTF_8));
		JarIndexStore store = new JarIndexStore(state.resolve("index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false));
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
		JdkIndex jdk = new JdkIndex();
		PingService ping = new PingService("importtruth", "0.2.0-SNAPSHOT");
		LookupService lookup = new LookupService(store, indexer, resolver, jdk);
		SearchService search = new SearchService(store, indexer, resolver);
		BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
		PipedStreams streams = new PipedStreams(lines, errors);
		try (ImportTruthServer server =
				new ImportTruthServer(ping, lookup, search, store, indexer, resolver, jdk,
						streams.in(), streams.out())) {
			streams.start();
			streams.write(
					"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
							+ "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
			assertThat(awaitLine(lines, "\"id\":1")).as("initialize answer").contains("importtruth");
			streams.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
			streams.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"lookup_symbol\","
					+ "\"arguments\":{\"projectPath\":\"" + escape(project.toString())
					+ "\",\"symbol\":\"com.example.Widget\"}}}");
			assertThat(awaitLine(lines, "\"id\":2")).as("lookup answer").contains("FOUND DEFINITE");
			streams.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"check_file\","
					+ "\"arguments\":{\"projectPath\":\"" + escape(project.toString())
					+ "\",\"filePath\":\"" + escape(file.toString()) + "\"}}}");
			assertThat(awaitLine(lines, "\"id\":3")).as("check answer").contains("clean");
			streams.write("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"ping\","
					+ "\"arguments\":{}}}");
			assertThat(awaitLine(lines, "\"id\":4")).as("ping answer").contains("importtruth");
			streams.write("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"search_api\","
					+ "\"arguments\":{\"projectPath\":\"" + escape(project.toString())
					+ "\",\"query\":\"Widget\"}}}");
			assertThat(awaitLine(lines, "\"id\":5")).as("search answer").contains("com.example.Widget");
		}
		assertThat(errors).as("reader thread errors").isEmpty();
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
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

	/** Piped streams with a background line reader. */
	private static final class PipedStreams {

		private final PipedOutputStream clientToServer = new PipedOutputStream();
		private final PipedInputStream serverIn;
		private final PipedOutputStream serverToClient = new PipedOutputStream();
		private final PipedInputStream clientIn;
		private final BlockingQueue<String> lines;
		private final BlockingQueue<Throwable> errors;

		PipedStreams(BlockingQueue<String> lines, BlockingQueue<Throwable> errors) throws Exception {
			this.serverIn = new PipedInputStream(clientToServer);
			this.clientIn = new PipedInputStream(serverToClient);
			this.lines = lines;
			this.errors = errors;
		}

		InputStream in() {
			return serverIn;
		}

		OutputStream out() {
			return serverToClient;
		}

		void start() {
			Thread reader = new Thread(() -> {
				try (BufferedReader in = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8))) {
					String line;
					while ((line = in.readLine()) != null) {
						lines.add(line);
					}
				} catch (Exception failure) {
					errors.add(failure);
				}
			});
			reader.setDaemon(true);
			reader.start();
		}

		void write(String frame) throws Exception {
			clientToServer.write((frame + "\n").getBytes(StandardCharsets.UTF_8));
			clientToServer.flush();
		}
	}
}

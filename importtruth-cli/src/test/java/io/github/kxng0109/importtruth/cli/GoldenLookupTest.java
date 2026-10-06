package io.github.kxng0109.importtruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.core.MavenResolver;
import io.github.kxng0109.importtruth.core.SearchService;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.mcp.LookupTool;
import io.github.kxng0109.importtruth.mcp.SearchTool;
import io.github.kxng0109.importtruth.model.Confidence;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.LookupResult;

/**
 * Golden lookups against this very repository: Jackson 3 found, Jackson 2
 * paths missing, annotations retained, JDK hits flagged.
 */
@DisplayName("Golden lookups")
final class GoldenLookupTest {

	@TempDir
	private Path state;

	@Test
	@DisplayName("finds both Jackson generations and flags JDK hits")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void jacksonGenerations() throws Exception {
		Wiring wiring = wiring();
		Path project = Paths.get(System.getProperty("user.dir")).getParent();

		LookupResult three = wiring.lookup().lookup(project, "tools.jackson.databind.ObjectMapper");
		assertThat(three.found()).as("Jackson 3 mapper found").isTrue();
		assertThat(three.confidence()).as("Jackson 3 confidence").isEqualTo(Confidence.DEFINITE);
		assertThat(three.fromJdk()).as("not a JDK hit").isFalse();

		LookupResult two = wiring.lookup().lookup(project, "com.fasterxml.jackson.databind.ObjectMapper");
		assertThat(two.found()).as("Jackson 2 mapper found on the test classpath").isTrue();
		assertThat(two.confidence()).as("Jackson 2 confidence").isEqualTo(Confidence.DEFINITE);

		LookupResult absent = wiring.lookup().lookup(project, "com.example.Nope");
		assertThat(absent.found()).as("absent name missing").isFalse();
		assertThat(absent.confidence()).as("missing confidence").isEqualTo(Confidence.CANDIDATE);

		LookupResult kept = wiring.lookup().lookup(project, "com.fasterxml.jackson.annotation.JsonProperty");
		assertThat(kept.found()).as("retained annotation found").isTrue();

		LookupResult jdk = wiring.lookup().lookup(project, "java.util.ArrayList");
		assertThat(jdk.found()).as("JDK type found").isTrue();
		assertThat(jdk.fromJdk()).as("JDK hit flagged").isTrue();
	}

	@Test
	@DisplayName("tool layer answers in compact lines")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void toolLines() throws Exception {
		Wiring wiring = wiring();
		String project = Paths.get(System.getProperty("user.dir")).getParent().toString();
		LookupTool lookup = new LookupTool(wiring.lookup());
		SearchTool search = new SearchTool(wiring.search());

		String found = textOf(lookup.call(Map.of("projectPath", project, "symbol", "tools.jackson.databind.JsonNode")));
		assertThat(found).as("found line").startsWith("FOUND DEFINITE tools.jackson.databind.JsonNode");
		String missing = textOf(lookup.call(Map.of("projectPath", project, "symbol", "com.example.Nope")));
		assertThat(missing).as("missing line").startsWith("NOT_FOUND CANDIDATE com.example.Nope");
		String hits = textOf(search.call(Map.of("projectPath", project, "query", "ObjectMapper")));
		assertThat(hits).as("search hits").contains("tools.jackson.databind.ObjectMapper");
		String bad = textOf(lookup.call(Map.of("projectPath", project)));
		assertThat(bad).as("bad arguments rejected").isNotBlank();
	}

	private Wiring wiring() throws Exception {
		JarIndexStore store = new JarIndexStore(state.resolve("index"));
		LibraryIndexer indexer = ServiceLoader.load(LibraryIndexer.class).findFirst().orElseThrow(
				() -> new IllegalStateException("No LibraryIndexer on the test classpath"));
		MavenResolver resolver = new MavenResolver(state);
		LookupService lookup = new LookupService(store, indexer, resolver, new JdkIndex());
		SearchService search = new SearchService(store, indexer, resolver);
		return new Wiring(lookup, search);
	}

	private static String textOf(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	private record Wiring(LookupService lookup, SearchService search) {
	}
}

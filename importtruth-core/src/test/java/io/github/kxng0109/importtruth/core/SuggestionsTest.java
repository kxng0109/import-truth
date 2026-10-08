package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies suggestion tiers, distances, validation, and caps.
 */
@DisplayName("Suggestions")
final class SuggestionsTest {

	@TempDir
	private Path state;

	@TempDir
	private Path jars;

	private final List<JarIndexStore> openStores = new ArrayList<>();

	@AfterEach
	void closeStores() {
		for (JarIndexStore store : openStores) {
			try {
				store.close();
			} catch (Exception ignored) {
				// Best effort: temp cleanup reclaims the rest.
			}
		}
		openStores.clear();
	}

	@Test
	@DisplayName("orders exact, package, typo, rest")
	void ordersTiers() {
		List<String> ranked = Suggestions.rank(
				"com.example.Widget",
				List.of(
						"org.other.WidgetXtra",
						"com.example.Helper",
						"com.example.Widgte",
						"com.example.Widget",
						"com.example.Widget",
						"org.other.Zed"),
				10);

		assertThat(ranked).as("tier order deduped").containsExactly(
				"com.example.Widget",
				"com.example.Helper",
				"com.example.Widgte",
				"org.other.WidgetXtra",
				"org.other.Zed");
	}

	@Test
	@DisplayName("sorts typos by distance then name and caps output")
	void sortsTyposAndCaps() {
		List<String> ranked = Suggestions.rank(
				"com.example.Widget",
				List.of("org.a.Wodget", "org.a.Widgx", "org.a.Widget"),
				2);

		assertThat(ranked).as("distance order capped").containsExactly("org.a.Widget", "org.a.Wodget");
	}

	@Test
	@DisplayName("scores transpositions as one edit with early exit")
	void scoresDistances() {
		assertThat(Suggestions.distance("Widget", "Widgte", 2)).as("transposition").isEqualTo(1);
		assertThat(Suggestions.distance("Widget", "Widget", 2)).as("identical").isEqualTo(0);
		assertThat(Suggestions.distance("Widget", "Wid", 2)).as("deletions").isEqualTo(3);
		assertThat(Suggestions.distance("A", "completely-different", 2)).as("early exit").isEqualTo(3);
		assertThat(Suggestions.distance("", "", 2)).as("empty").isEqualTo(0);
	}

	@Test
	@DisplayName("rejects nulls and non-positive limits")
	@SuppressWarnings("DataFlowIssue")
	void rejectsBadInput() {
		assertThatThrownBy(() -> Suggestions.rank(null, List.of("a.B"), 3)).as("null want")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.rank("a.B", null, 3)).as("null candidates")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.rank("a.B", List.of("a.B"), 0)).as("zero limit")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("collect validates inputs and caps collection")
	@SuppressWarnings("DataFlowIssue")
	void collectValidatesAndCaps() throws Exception {
		JarIndexStore store = openStore("collect");
		Path jar = jars.resolve("collect.jar");
		Files.write(jar, "collect".getBytes(StandardCharsets.UTF_8));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false),
				new Symbol("com.example.WidgetHelper", SymbolKind.CLASS, null, null, false, "", false),
				new Symbol("com.example.WidgetFactory", SymbolKind.CLASS, null, null, false, "", false));
		Path db = store.ensureIndexed(jar, indexer);
		List<Path> dbs = List.of(db);

		assertThatThrownBy(() -> Suggestions.collect(null, dbs, "a.B", 5, 5)).as("null store")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.collect(store, null, "a.B", 5, 5)).as("null dbs")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.collect(store, dbs, null, 5, 5)).as("null want")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.collect(store, dbs, "a.B", 0, 5)).as("zero collected")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Suggestions.collect(store, dbs, "a.B", 5, 0)).as("zero limit")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Suggestions.collectInsensitive(null, dbs, "a.B", 5, 5))
				.as("null insensitive store")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.collectInsensitive(store, null, "a.B", 5, 5))
				.as("null insensitive dbs")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.collectInsensitive(store, dbs, null, 5, 5))
				.as("null insensitive want")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.collectInsensitive(store, dbs, "a.B", 0, 5))
				.as("zero insensitive collected")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Suggestions.collectInsensitive(store, dbs, "a.B", 5, 0))
				.as("zero insensitive limit")
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(Suggestions.collect(store, dbs, "com.example.Widget", 1, 5))
				.as("collection capped")
				.hasSize(1);
		assertThat(Suggestions.collectInsensitive(store, dbs, "com.example.widget", 1, 5))
				.as("insensitive collection capped")
				.hasSize(1);
		assertThat(Suggestions.collectInsensitive(store, dbs, "COM.EXAMPLE.WIDGET", 5, 5))
				.as("insensitive finds Widget")
				.contains("com.example.Widget");
	}

	private JarIndexStore openStore(String name) throws Exception {
		JarIndexStore created = new JarIndexStore(state.resolve(name));
		openStores.add(created);
		return created;
	}
}

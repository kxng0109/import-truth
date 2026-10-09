package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies suggest batches: shared resolve, case insensitive matches,
 * limits, and input validation.
 */
@DisplayName("SuggestService")
final class SuggestServiceTest {

	@TempDir
	private Path state;

	@TempDir
	private Path project;

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
	@DisplayName("suggests without regard to case and caps output")
	void suggestsInsensitiveAndCaps() throws Exception {
		Services services = services();
		Path projectDir = project;

		List<String> lower = services.suggest().suggest(projectDir, "com.example.widget", 5);

		assertThat(lower).as("lowercase finds Widget").contains("com.example.Widget");
		assertThat(services.suggest().suggest(projectDir, "COM.EXAMPLE.WIDGET", 5))
				.as("uppercase finds Widget")
				.contains("com.example.Widget");
		assertThat(services.suggest().suggest(projectDir, "com.example.Widget", 1))
				.as("limit caps output")
				.hasSize(1);
	}

	@Test
	@DisplayName("batches with one shared resolve and strips generics")
	void batchesSharedResolve() throws Exception {
		Services services = services();

		Map<String, List<String>> answers = services.suggest().suggestBatch(project,
				List.of("com.example.Widget<String>", "org.example.Nope", " "), 5);

		assertThat(services.resolves().get()).as("single resolve").isEqualTo(1);
		assertThat(answers.get("com.example.Widget<String>")).as("generic spelling resolves")
				.contains("com.example.Widget");
		assertThat(answers.get("org.example.Nope")).as("unknown stays empty").isEmpty();
		assertThat(answers.get(" ")).as("blank stays empty").isEmpty();
	}

	@Test
	@DisplayName("matches single suggests for retry spellings")
	void matchesSingleSuggests() throws Exception {
		Services services = services();
		List<String> retry = List.of("com.example.Widget", "com.example.Widget[]",
				"com.example.Widget<String>", "org.example.Nope");

		Map<String, List<String>> answers = services.suggest().suggestBatch(project, retry, 5);

		for (String name : retry) {
			assertThat(answers.get(name)).as("batch matches single for " + name)
					.isEqualTo(services.suggest().suggest(project, name, 5));
		}
	}

	@Test
	@DisplayName("rejects bad single inputs")
	@SuppressWarnings("DataFlowIssue")
	void rejectsBadSingle() throws Exception {
		Services services = services();

		assertThat(services.suggest().suggest(project, " ", 5)).as("blank stays empty").isEmpty();
		assertThat(services.suggest().suggest(project, "<T>", 5)).as("bare stays empty").isEmpty();
		assertThatThrownBy(() -> services.suggest().suggest(project, null, 5))
				.as("null name rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("rejects nulls and non-positive limits")
	@SuppressWarnings("DataFlowIssue")
	void rejectsBadInput() throws Exception {
		Services services = services();

		assertThatThrownBy(() -> services.suggest().suggest(null, "a.B", 5))
				.as("null project rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> services.suggest().suggest(project, null, 5))
				.as("null name rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> services.suggest().suggest(project, "a.B", 0))
				.as("zero limit rejection")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> services.suggest().suggestBatch(project, null, 5))
				.as("null batch rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> services.suggest().suggestBatch(null, List.of("a.B"), 5))
				.as("null batch project rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> services.suggest().suggestBatch(project, List.of("a.B"), 0))
				.as("zero batch limit rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("tolerates null and bare type variable items in batches")
	void toleratesNullAndBareItems() throws Exception {
		Services services = services();
		List<String> mixed = new ArrayList<>(List.of("com.example.Widget"));
		mixed.add(null);
		mixed.add("<T>");

		Map<String, List<String>> answers = services.suggest().suggestBatch(project, mixed, 5);

		assertThat(answers.get("com.example.Widget")).as("valid item resolves")
				.contains("com.example.Widget");
		assertThat(answers.get(null)).as("null item stays empty").isEmpty();
		assertThat(answers.get("<T>")).as("bare type variable stays empty").isEmpty();
	}

	private Services services() throws Exception {
		Path jar = project.resolve("dep.jar");
		if (!Files.exists(jar)) {
			try (OutputStream out = Files.newOutputStream(jar);
					JarOutputStream zip = new JarOutputStream(out)) {
				zip.putNextEntry(new ZipEntry("META-INF/"));
				zip.closeEntry();
			}
		}
		Files.write(project.resolve("marker.txt"), "ok".getBytes(StandardCharsets.UTF_8));
		JarIndexStore store = openStore(state.resolve("suggest-index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false),
				new Symbol("com.example.WidgetHelper", SymbolKind.CLASS, null, null, false, "", false));
		AtomicInteger resolves = new AtomicInteger();
		DependencyResolver resolver = (projectDir, allowNetwork) -> {
			resolves.incrementAndGet();
			return List.of(jar);
		};
		return new Services(new SuggestService(store, indexer, resolver), resolves);
	}

	private JarIndexStore openStore(Path dir) throws Exception {
		JarIndexStore created = new JarIndexStore(dir);
		openStores.add(created);
		return created;
	}

	private record Services(SuggestService suggest, AtomicInteger resolves) {
	}
}

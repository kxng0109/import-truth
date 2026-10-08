package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.Confidence;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.LookupResult;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
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
 * Verifies lookup and search directly: dependency hits, JDK hits, misses
 * with suggestions, and search caps.
 */
@DisplayName("Lookup and search services")
final class LookupSearchTest {

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

	private JarIndexStore openStore(Path dir) throws IOException {
		JarIndexStore created = new JarIndexStore(dir);
		openStores.add(created);
		return created;
	}

	@Test
	@DisplayName("finds dependency symbols with details")
	void findsDependencySymbols() throws Exception {
		Services services = services();
		Path projectDir = project;

		LookupResult found = services.lookup().lookup(projectDir, "com.example.Widget");

		assertThat(found.found()).as("found").isTrue();
		assertThat(found.confidence()).as("confidence").isEqualTo(Confidence.DEFINITE);
		assertThat(found.matches()).as("match details").hasSize(1);
		assertThat(found.matches().get(0).deprecated()).as("match deprecation").isFalse();
		assertThat(found.fromJdk()).as("not JDK").isFalse();
	}

	@Test
	@DisplayName("flags JDK hits")
	void flagsJdkHits() throws Exception {
		Services services = services();

		LookupResult jdk = services.lookup().lookup(project, "java.util.ArrayList");

		assertThat(jdk.found()).as("JDK found").isTrue();
		assertThat(jdk.fromJdk()).as("JDK flagged").isTrue();
	}

	@Test
	@DisplayName("misses with suggestions")
	void missesWithSuggestions() throws Exception {
		Services services = services();

		LookupResult missing = services.lookup().lookup(project, "org.example.Widget");

		assertThat(missing.found()).as("missing").isFalse();
		assertThat(missing.confidence()).as("candidate").isEqualTo(Confidence.CANDIDATE);
		assertThat(missing.suggestions()).as("suggestions name the neighbor").contains("com.example.Widget");
	}

	@Test
	@DisplayName("strips generics and arrays from lookups")
	void stripsGenericsAndArrays() throws Exception {
		Services services = services();

		LookupResult generic = services.lookup().lookup(project, "com.example.Widget<String>");

		assertThat(generic.found()).as("generic spelling resolves").isTrue();
		assertThat(LookupService.plainSymbol("java.util.Map<String, List<String>>[]"))
				.as("nested generics plus array")
				.isEqualTo("java.util.Map");
		assertThat(LookupService.plainSymbol("<T>")).as("bare type variable").isEmpty();
	}

	@Test
	@DisplayName("suggests across indexes without dots")
	void suggestsAcrossIndexes() throws Exception {
		Services services = twoJarServices();

		LookupResult missing = services.lookup().lookup(project, "Widget");

		assertThat(missing.found()).as("missing").isFalse();
		assertThat(missing.suggestions()).as("five suggestions across jars")
				.hasSize(5)
				.contains("com.example.Widget", "com.example.WidgetInfo");
	}

	@Test
	@DisplayName("batches lookups with one shared resolve")
	void batchesWithSharedResolve() throws Exception {
		Path jar = project.resolve("dep.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
		}
		JarIndexStore store = openStore(state.resolve("batch-index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false));
		AtomicInteger resolves = new AtomicInteger();
		DependencyResolver resolver = (projectDir, allowNetwork) -> {
			resolves.incrementAndGet();
			return List.of(jar);
		};
		LookupService lookup = new LookupService(store, indexer, resolver, new JdkIndex());

		List<String> wanted = new ArrayList<>(
				List.of("com.example.Widget", "org.example.Widget", "org.example.Nope", " ", "java.util.ArrayList"));
		wanted.add(null);
		Map<String, LookupResult> answers = lookup.lookupBatch(project, wanted);

		assertThat(resolves.get()).as("single resolve").isEqualTo(1);
		assertThat(answers.get("com.example.Widget").found()).as("hit found").isTrue();
		assertThat(answers.get("org.example.Widget").found()).as("wrong package miss").isFalse();
		assertThat(answers.get("org.example.Widget").suggestions()).as("miss suggests neighbor")
				.contains("com.example.Widget");
		assertThat(answers.get("org.example.Nope").found()).as("unknown miss").isFalse();
		assertThat(answers.get(" ").found()).as("blank miss").isFalse();
		assertThat(answers.get(null).found()).as("null miss").isFalse();
		assertThat(answers.get("java.util.ArrayList").fromJdk()).as("JDK flagged").isTrue();
		assertThat(lookup.lookup(project, "com.example.Widget"))
				.as("batch matches single")
				.isEqualTo(answers.get("com.example.Widget"));
	}

	@Test
	@DisplayName("rejects null batch inputs")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullBatch() throws Exception {
		Services services = services();

		assertThatThrownBy(() -> services.lookup().lookupBatch(null, List.of("a.B")))
				.as("null project rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> services.lookup().lookupBatch(project, null))
				.as("null symbols rejection")
				.isInstanceOf(NullPointerException.class);
	}
	@Test
	@DisplayName("search caps rows")
	void searchCapsRows() throws Exception {
		Services services = services();

		assertThat(services.search().search(project, "com.example", 1))
				.as("capped search")
				.hasSize(1);
		assertThat(services.search().search(project, "com.nothing.here", 5))
				.as("empty search")
				.isEmpty();
		assertThatThrownBy(() -> services.search().search(project, "com.example", 0))
				.as("non-positive limit rejected")
				.isInstanceOf(IllegalArgumentException.class);
	}

	private Services services() throws IOException {
		Path jar = project.resolve("dep.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
		}
		JarIndexStore store = openStore(state.resolve("index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false),
				new Symbol("com.example.Widget.build", SymbolKind.METHOD, "()V",
						"com.example.Widget", true, "2.0", false),
				new Symbol("com.example.Widget.help", SymbolKind.METHOD, "()V",
						"com.example.Widget", false, "", false),
				new Symbol("com.example.Widget.version", SymbolKind.FIELD, "Ljava/lang/String;",
						"com.example.Widget", false, "", false));
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
		JdkIndex jdk = new JdkIndex();
		return new Services(
				new LookupService(store, indexer, resolver, jdk),
				new SearchService(store, indexer, resolver));
	}

	private Services twoJarServices() throws IOException {
		Path first = project.resolve("one.jar");
		Path second = project.resolve("two.jar");
		for (Path jar : List.of(first, second)) {
			try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
				zip.putNextEntry(new ZipEntry("META-INF/"));
				zip.closeEntry();
				zip.putNextEntry(new ZipEntry(jar.getFileName().toString() + ".marker"));
				zip.write(jar.getFileName().toString().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		JarIndexStore store = openStore(state.resolve("split"));
		LibraryIndexer indexer = jarFile -> {
			if (jarFile.equals(first)) {
				return List.of(new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false));
			}
			return List.of(
					new Symbol("com.example.Widget.build", SymbolKind.METHOD, "()V",
							"com.example.Widget", true, "2.0", false),
					new Symbol("com.example.Widget.help", SymbolKind.METHOD, "()V",
							"com.example.Widget", false, "", false),
					new Symbol("com.example.Widget.version", SymbolKind.FIELD, "Ljava/lang/String;",
							"com.example.Widget", false, "", false),
					new Symbol("com.example.WidgetInfo", SymbolKind.CLASS, null, null, false, "", false));
		};
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(first, second);
		JdkIndex jdk = new JdkIndex();
		return new Services(
				new LookupService(store, indexer, resolver, jdk),
				new SearchService(store, indexer, resolver));
	}

	private record Services(LookupService lookup, SearchService search) {
	}
}

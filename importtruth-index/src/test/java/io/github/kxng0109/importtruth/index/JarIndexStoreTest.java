package io.github.kxng0109.importtruth.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies store mechanics with a fake extractor: round trip, search,
 * struct Trust, single-flight, and temp cleanup.
 */
@DisplayName("JarIndexStore")
final class JarIndexStoreTest {

	@TempDir
	private Path state;

	@TempDir
	private Path jars;

	@Test
	@DisplayName("round-trips symbols and searches by substring")
	void roundTripsAndSearches() throws Exception {
		JarIndexStore store = new JarIndexStore(state);
		FakeIndexer indexer = new FakeIndexer();
		Path jar = fakeJar("first.jar");
		Path db = store.ensureIndexed(jar, indexer);

		assertThat(db.getFileName().toString()).as("content-addressed name").endsWith(".mv.db");
		assertThat(store.findIn(db, "com.example.Widget"))
				.as("exact match")
				.hasSize(1)
				.allSatisfy(symbol -> {
					assertThat(symbol.kind()).as("stored kind").isEqualTo(SymbolKind.CLASS);
					assertThat(symbol.deprecated()).as("stored deprecation").isFalse();
				});
		assertThat(store.findIn(db, "com.example.Missing")).as("absent name").isEmpty();
		assertThat(store.searchIn(db, "Widg", 5))
				.as("substring search, shortest first")
				.hasSize(2)
				.first()
				.extracting(Symbol::fqn)
				.isEqualTo("com.example.Widget");
		assertThat(store.findAcross(List.of(db), "com.example.Widget")).as("across indexes").hasSize(1);
		try (Stream<Path> leftovers = Files.list(state)) {
			assertThat(leftovers.filter(p -> p.getFileName().toString().contains(".tmp")).count())
					.as("no temp files left")
					.isEqualTo(0L);
		}
	}

	@Test
	@DisplayName("indexes each library once under parallel requests")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void indexesOnceUnderParallelRequests() throws Exception {
		JarIndexStore store = new JarIndexStore(state);
		FakeIndexer indexer = new FakeIndexer();
		Path jar = fakeJar("shared.jar");
		int threads = 8;
		CountDownLatch gate = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Path>> runs = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				runs.add(pool.submit(() -> {
					gate.await(10, TimeUnit.SECONDS);
					return store.ensureIndexed(jar, indexer);
				}));
			}
			gate.countDown();
			List<Path> results = new ArrayList<>();
			for (Future<Path> run : runs) {
				results.add(run.get(20, TimeUnit.SECONDS));
			}
			Path first = results.get(0);
			assertThat(results).as("shared index path").allSatisfy(db -> assertThat(db).isEqualTo(first));
			assertThat(indexer.runs.get()).as("extractor invocations").isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	private Path fakeJar(String name) throws IOException {
		Path jar = jars.resolve(name);
		Files.write(jar, name.getBytes(StandardCharsets.UTF_8));
		return jar;
	}

	/** Deterministic canned symbols, no bytecode involved. */
	private static final class FakeIndexer implements LibraryIndexer {

		final AtomicInteger runs = new AtomicInteger();

		@Override
		public List<Symbol> index(Path jar) {
			runs.incrementAndGet();
			return List.of(
					new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false),
					new Symbol("com.example.Widget.build", SymbolKind.METHOD, "()V",
							"com.example.Widget", true, "2.0", false));
		}
	}
}

package io.github.kxng0109.importtruth.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
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

	private JarIndexStore openStore(String name) throws IOException {
		JarIndexStore created = new JarIndexStore(state.resolve(name));
		openStores.add(created);
		return created;
	}

	@Test
	@DisplayName("round-trips symbols and searches by substring")
	void roundTripsAndSearches() throws Exception {
		JarIndexStore store = openStore("roundtrip");
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
	@DisplayName("searches without regard to case and escapes wildcards")
	void searchesInsensitive() throws Exception {
		JarIndexStore store = openStore("insensitive");
		Path db = store.ensureIndexed(fakeJar("case.jar"), new FakeIndexer());

		assertThat(store.searchInInsensitive(db, "widget", 5))
				.as("lowercase finds Widget")
				.hasSize(2);
		assertThat(store.searchInInsensitive(db, "WIDGET", 5))
				.as("uppercase finds Widget")
				.hasSize(2);
		assertThat(store.searchInInsensitive(db, "Wid%", 5))
				.as("percent escaped literally")
				.isEmpty();
		assertThatThrownBy(() -> store.searchInInsensitive(db, "x", 0))
				.as("non-positive limit rejected")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("tests existence with early exit")
	void testsExistence() throws Exception {
		JarIndexStore store = openStore("exists");
		Path db = store.ensureIndexed(fakeJar("e.jar"), new FakeIndexer());

		assertThat(store.existsIn(db, "com.example.Widget")).as("present").isTrue();
		assertThat(store.existsIn(db, "com.example.Missing")).as("absent").isFalse();
		assertThat(store.existsAcross(List.of(db), "com.example.Widget")).as("across hit").isTrue();
		assertThat(store.existsAcross(List.of(), "com.example.Widget")).as("no indexes").isFalse();
		assertThatThrownBy(() -> store.searchPrefix(db, "x", 0))
				.as("non-positive prefix limit rejected")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("searches by prefix and escapes wildcards")
	void searchesByPrefix() throws Exception {
		JarIndexStore store = openStore("prefix");
		Path db = store.ensureIndexed(fakeJar("p.jar"), new FakeIndexer());

		assertThat(store.searchPrefix(db, "com.example.", 5))
				.as("prefix finds Widget first")
				.hasSize(2)
				.first()
				.extracting(Symbol::fqn)
				.isEqualTo("com.example.Widget");
		assertThat(store.searchPrefix(db, "example.", 5))
				.as("mid-string prefix misses")
				.isEmpty();
		assertThat(store.searchPrefix(db, "com.example.", 1))
				.as("limit one probe stops fast")
				.hasSize(1);
		assertThat(store.searchPrefix(db, "com.example_Widget", 5))
				.as("underscore escaped literally")
				.isEmpty();
	}

	@Test
	@DisplayName("prefilters databases by name segments")
	void prefiltersBySegments() throws Exception {
		JarIndexStore store = openStore("segments");
		Path db = store.ensureIndexed(fakeJar("s.jar"), new FakeIndexer());

		assertThat(store.maybeMatches(db, "Widget", false)).as("simple present").isTrue();
		assertThat(store.maybeMatches(db, "example", false)).as("package part present").isTrue();
		assertThat(store.maybeMatches(db, "Nope", false)).as("absent simple").isFalse();
		assertThat(store.maybeMatches(db, "widget", false)).as("case sensitive miss").isFalse();
		assertThat(store.maybeMatches(db, "widget", true)).as("case insensitive hit").isTrue();
		assertThat(store.maybeMatches(db, "WIDGET", true)).as("uppercase hit").isTrue();
		assertThat(store.maybeMatches(db, "xyz", true)).as("absent insensitive").isFalse();
	}

	@Test
	@DisplayName("rejects null prefilter queries")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullPrefilter() throws Exception {
		JarIndexStore store = openStore("segments-null");
		Path db = store.ensureIndexed(fakeJar("sn.jar"), new FakeIndexer());

		assertThatThrownBy(() -> store.maybeMatches(null, "x", false))
				.as("null db rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.maybeMatches(db, null, false))
				.as("null query rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("rejects null existence and prefix queries")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullExistence() throws Exception {
		JarIndexStore store = openStore("exists-null");
		Path db = store.ensureIndexed(fakeJar("n.jar"), new FakeIndexer());

		assertThatThrownBy(() -> store.existsIn(null, "a.B"))
				.as("null db rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.existsIn(db, null))
				.as("null name rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.existsAcross(null, "a.B"))
				.as("null dbs rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.existsAcross(List.of(db), null))
				.as("null across name rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.searchPrefix(null, "a.", 5))
				.as("null prefix db rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.searchPrefix(db, null, 5))
				.as("null prefix rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("evicts eldest segment sets when full")
	void evictsEldestSegments() throws Exception {
		JarIndexStore store = openStore("segments-full");
		FakeIndexer indexer = new FakeIndexer();
		Path first = null;
		for (int i = 0; i < 65; i++) {
			Path db = store.ensureIndexed(fakeJar("seg-" + i + ".jar"), indexer);
			if (i == 0) {
				first = db;
			}
			assertThat(store.maybeMatches(db, "Widget", false)).as("segments load").isTrue();
		}

		assertThat(store.maybeMatches(first, "Widget", false)).as("evicted set reloads").isTrue();
	}

	@Test
	@DisplayName("rejects null insensitive queries")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullInsensitive() throws Exception {		JarIndexStore store = openStore("insensitive-null");
		Path db = store.ensureIndexed(fakeJar("null.jar"), new FakeIndexer());

		assertThatThrownBy(() -> store.searchInInsensitive(null, "x", 5))
				.as("null db rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.searchInInsensitive(db, null, 5))
				.as("null query rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("indexes each library once under parallel requests")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void indexesOnceUnderParallelRequests() throws Exception {
		JarIndexStore store = openStore("shared");
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

	@Test
	@DisplayName("reindexes replaced jars")
	void reindexesReplaced() throws Exception {
		JarIndexStore store = openStore("swap");
		FakeIndexer indexer = new FakeIndexer();
		Path jar = fakeJar("swap.jar");

		Path first = store.ensureIndexed(jar, indexer);
		Files.write(jar, "different-bytes".getBytes(StandardCharsets.UTF_8));
		Path second = store.ensureIndexed(jar, indexer);

		assertThat(second).as("new content means new index").isNotEqualTo(first);
		assertThat(indexer.runs.get()).as("indexed twice").isEqualTo(2);
	}

	@Test
	@DisplayName("reuses existing indexes and rejects bad limits")
	void reusesAndValidates() throws Exception {
		JarIndexStore store = openStore("reuse");
		FakeIndexer indexer = new FakeIndexer();
		Path jar = fakeJar("again.jar");

		Path first = store.ensureIndexed(jar, indexer);
		Path second = store.ensureIndexed(jar, indexer);

		assertThat(second).as("same index reused").isEqualTo(first);
		assertThat(indexer.runs.get()).as("indexed once").isEqualTo(1);
		assertThatThrownBy(() -> store.searchIn(first, "x", 0))
				.as("non-positive limit rejected")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("propagates extractor failures")
	void propagatesFailures() throws Exception {
		JarIndexStore store = openStore("broken");
		Path jar = fakeJar("broken.jar");

		assertThatThrownBy(() -> store.ensureIndexed(jar, jarFile -> {
			throw new IOException("unreadable");
		})).as("io failure surfaces").isInstanceOf(IOException.class).hasMessageContaining("unreadable");
		assertThatThrownBy(() -> store.ensureIndexed(jar, jarFile -> {
			throw new IllegalStateException("broken");
		})).as("runtime failure wrapped").isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("wraps unreadable databases")
	void wrapsUnreadable() throws Exception {
		JarIndexStore store = openStore("unreadable");
		Path dir = state.resolve("notadb");
		Files.createDirectories(dir);

		assertThatThrownBy(() -> store.findIn(dir, "a.B")).as("unreadable find").isInstanceOf(IOException.class);
		assertThatThrownBy(() -> store.searchIn(dir, "a", 5)).as("unreadable search").isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rolls back oversized rows")
	void rollsBackOversized() throws Exception {
		JarIndexStore store = openStore("oversized");
		Path jar = fakeJar("wide.jar");
		String wide = "com.example." + "W".repeat(2000);
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol(wide, SymbolKind.CLASS, null, null, false, "", false));

		assertThatThrownBy(() -> store.ensureIndexed(jar, indexer))
				.as("oversized row fails")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("translates follower failures to IOException")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void translatesFollowerFailures() throws Exception {
		JarIndexStore store = openStore("follower-fail");
		Path jar = fakeJar("ff.jar");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		LibraryIndexer broken = jarFile -> {
			entered.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new IOException("test gate timed out");
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", interrupted);
			}
			throw new IOException("broken extractor");
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Path> leader = pool.submit(() -> store.ensureIndexed(jar, broken));
			assertThat(entered.await(10, TimeUnit.SECONDS)).as("leader entered").isTrue();
			Future<Path> follower = pool.submit(() -> store.ensureIndexed(jar, broken));
			Thread.sleep(200);
			release.countDown();
			assertThatThrownBy(() -> follower.get(10, TimeUnit.SECONDS))
					.as("follower sees IOException")
					.isInstanceOf(ExecutionException.class)
					.hasMessageContaining("Indexing failed");
			assertThatThrownBy(() -> leader.get(10, TimeUnit.SECONDS))
					.as("leader fails too")
					.isInstanceOf(ExecutionException.class);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("answers identically under concurrent load")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void answersConcurrently() throws Exception {
		JarIndexStore store = openStore("concurrent");
		Path first = fakeJar("cone.jar");
		Path second = fakeJar("ctwo.jar");
		LibraryIndexer indexer = jarFile -> jarFile.getFileName().toString().equals("cone.jar")
				? List.of(new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false))
				: List.of(new Symbol("com.example.Gadget", SymbolKind.CLASS, null, null, false, "", false));
		Path dbOne = store.ensureIndexed(first, indexer);
		Path dbTwo = store.ensureIndexed(second, indexer);
		List<Path> dbs = List.of(dbOne, dbTwo);

		assertThat(store.findAcross(dbs, "com.example.Widget")).as("serial baseline").hasSize(1);
		assertThat(store.existsAcross(dbs, "com.example.Gadget")).as("serial exists").isTrue();

		int threads = 8;
		CountDownLatch gate = new CountDownLatch(1);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> runs = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				runs.add(pool.submit(() -> {
					try {
						if (!gate.await(10, TimeUnit.SECONDS)) {
							throw new IOException("test gate timed out");
						}
						for (int round = 0; round < 25; round++) {
							if (store.findAcross(dbs, "com.example.Widget").size() != 1) {
								throw new AssertionError("find drifted");
							}
							if (!store.existsAcross(dbs, "com.example.Gadget")) {
								throw new AssertionError("exists drifted");
							}
							if (store.searchPrefix(dbOne, "com.example.", 5).size() != 1) {
								throw new AssertionError("prefix drifted");
							}
							if (!store.maybeMatches(dbTwo, "Gadget", false)) {
								throw new AssertionError("prefilter drifted");
							}
							if (store.searchIn(dbTwo, "Gadget", 5).size() != 1) {
								throw new AssertionError("search drifted");
							}
						}
					} catch (Throwable failure) {
						failures.add(failure);
					}
					return null;
				}));
			}
			gate.countDown();
			for (Future<?> run : runs) {
				run.get(20, TimeUnit.SECONDS);
			}
			assertThat(failures).as("zero concurrent failures").isEmpty();
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("publishes temp files and tolerates races")
	void publishesTempFiles() throws Exception {
		Path target = state.resolve("final.mv.db");
		Path tmp = state.resolve("staged.tmp.mv.db");
		Files.write(tmp, "data".getBytes(StandardCharsets.UTF_8));

		JarIndexStore.publishTmp(tmp, target);
		assertThat(target).as("published file").exists();
		assertThat(tmp).as("temp consumed").doesNotExist();

		Path missing = state.resolve("ghost.tmp.mv.db");
		assertThatThrownBy(() -> JarIndexStore.publishTmp(missing, state.resolve("ghost.mv.db")))
				.as("missing temp throws")
				.isInstanceOf(IOException.class);

		Path staged = state.resolve("race.tmp.mv.db");
		Files.write(staged, "new".getBytes(StandardCharsets.UTF_8));
		byte[] winner = Files.readAllBytes(target);
		JarIndexStore.publishTmp(staged, target);
		assertThat(target).as("loser tolerated").exists();
		assertThat(Files.readAllBytes(target)).as("winner content preserved").isEqualTo(winner);
		assertThat(staged).as("loser temp consumed").doesNotExist();
	}

	@Test
	@DisplayName("refuses queries after close")
	void refusesAfterClose() throws Exception {
		JarIndexStore store = openStore("closed");
		Path db = store.ensureIndexed(fakeJar("c.jar"), new FakeIndexer());
		assertThat(store.findIn(db, "com.example.Widget")).as("warmed reader").hasSize(1);
		store.close();
		openStores.remove(store);

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget")).as("closed store fails")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects null hashes")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullHashes() {
		assertThatThrownBy(() -> Sha256.ofFile(null))
				.as("null file rejection")
				.isInstanceOf(NullPointerException.class);
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

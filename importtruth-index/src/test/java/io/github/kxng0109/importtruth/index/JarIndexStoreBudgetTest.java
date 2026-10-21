package io.github.kxng0109.importtruth.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the resource budget of the index: the bytes one symbol costs on
 * disk, the bytes held in memory, and the promise that a lookup
 * touches no file. A budget is a promise the build has to keep, so
 * every number here is measured rather than assumed, and none of them
 * is a timing, so none of them can flake on a busy machine.
 */
@DisplayName("Index resource budget")
@Tag("slow")
final class JarIndexStoreBudgetTest {

	/** Bytes one symbol may cost on disk, its name and metadata included. */
	private static final long BYTES_PER_SYMBOL = 96;

	/** Symbols in the measured jar. */
	private static final int SYMBOLS = 3000;

	/** Lookups in the measured round. */
	private static final int LOOKUPS = 1000;

	@TempDir
	private Path state;

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
	@DisplayName("keeps the index small per symbol")
	void keepsTheIndexSmallPerSymbol() throws Exception {
		JarIndexStore store = openStore("budget-size");
		Path db = store.ensureIndexed(jar("budget-size.jar"), new ManySymbols());

		double perSymbol = (double) Files.size(db) / SYMBOLS;

		assertThat(perSymbol).as("bytes per symbol on disk")
				.isLessThanOrEqualTo((double) BYTES_PER_SYMBOL);
	}

	@Test
	@DisplayName("holds the index in memory inside the budget")
	void holdsTheIndexInsideTheBudget() throws Exception {
		JarIndexStore store = openStore("budget-memory");
		Path db = store.ensureIndexed(jar("budget-memory.jar"), new ManySymbols());
		store.findIn(db, "com.example.Widget0000");

		assertThat(store.cachedBytes()).as("bytes held in memory")
				.isLessThanOrEqualTo(JarIndexStore.cacheBudget);
	}

	@Test
	@DisplayName("answers lookups without touching a file")
	void answersLookupsWithoutTouchingFiles() throws Exception {
		JarIndexStore store = openStore("budget-noread");
		Path db = store.ensureIndexed(jar("budget-noread.jar"), new ManySymbols());
		store.findIn(db, "com.example.Widget0000");
		int readsAfterLoad = store.fileReads();
		// The index file is gone, so a lookup that still answers came
		// from memory: the query path touches no file at all.
		Files.delete(db);

		for (int i = 0; i < LOOKUPS; i++) {
			String name = "com.example.Widget" + String.format("%04d", i % SYMBOLS);
			assertThat(store.findIn(db, name)).as("lookup " + i).isNotEmpty();
		}

		assertThat(store.fileReads()).as("no file read while answering")
				.isEqualTo(readsAfterLoad);
	}

	private JarIndexStore openStore(String name) throws IOException {
		JarIndexStore created = new JarIndexStore(state.resolve(name));
		openStores.add(created);
		return created;
	}

	private Path jar(String name) throws IOException {
		Path jar = state.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
		}
		return jar;
	}

	/** Canned symbols: three thousand unique names. */
	private static final class ManySymbols implements LibraryIndexer {

		@Override
		public List<Symbol> index(Path jar) {
			List<Symbol> symbols = new ArrayList<>(SYMBOLS);
			for (int i = 0; i < SYMBOLS; i++) {
				symbols.add(new Symbol("com.example.Widget" + String.format("%04d", i),
						SymbolKind.CLASS, null, null, false, "", false));
			}
			return symbols;
		}
	}
}

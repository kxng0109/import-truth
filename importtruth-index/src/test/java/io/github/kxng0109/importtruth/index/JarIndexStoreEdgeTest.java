package io.github.kxng0109.importtruth.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the edges of an index file: foreign and truncated files, a
 * flipped byte in every region the file carries, a header that does not
 * describe its own layout, an empty query, an oversized row, the
 * pruning of older formats, and the measurement of the lookup path.
 */
@DisplayName("Index file edges")
final class JarIndexStoreEdgeTest {

	/** Index files the measurement spreads its lookups over. */
	private static final int BENCH_FILES = 39;

	/** Symbols per measured index file. */
	private static final int BENCH_SYMBOLS = 3000;

	/** Lookups per measured round. */
	private static final int BENCH_LOOKUPS = 1000;

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
	@DisplayName("rejects a file that is not an index")
	void rejectsForeignFile() throws Exception {
		JarIndexStore store = openStore("foreign");
		Path db = state.resolve("foreign.idx");
		Files.writeString(db, "not an index at all");

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("foreign file rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects a truncated index")
	void rejectsTruncatedFile() throws Exception {
		JarIndexStore store = openStore("truncated");
		Path db = state.resolve("truncated.idx");
		Files.write(db, new byte[8]);

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("truncated file rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("drops an index whose name byte was flipped")
	void dropsIndexWithFlippedNameByte() throws Exception {
		JarIndexStore store = openStore("flipped-name");
		Path db = store.ensureIndexed(jar("flipped-name.jar"), new Symbols());

		assertDamagedIndexIsDropped(store, db, JarIndexStore.HEADER, 0x40, "a flipped name byte");
	}

	@Test
	@DisplayName("drops an index whose offset byte was flipped")
	void dropsIndexWithFlippedOffsetByte() throws Exception {
		JarIndexStore store = openStore("flipped-offsets");
		Path db = store.ensureIndexed(jar("flipped-offsets.jar"), new Symbols());
		int offsetsAt = headerInt(db, 16);

		// The low byte of the third name offset, one bit up. The offsets
		// stay in order and inside the name region, so only a checksum
		// can catch it: the reader would otherwise answer with a
		// truncated name.
		assertDamagedIndexIsDropped(store, db, offsetsAt + 2 * Integer.BYTES + 3, 0x01,
				"a flipped offset byte");
	}

	@Test
	@DisplayName("drops an index whose metadata byte was flipped")
	void dropsIndexWithFlippedMetadataByte() throws Exception {
		JarIndexStore store = openStore("flipped-metadata");
		Path db = store.ensureIndexed(jar("flipped-metadata.jar"), new Symbols());
		int metaAt = headerInt(db, 20);

		assertDamagedIndexIsDropped(store, db, metaAt, 0x40, "a flipped metadata byte");
	}

	@Test
	@DisplayName("drops an index whose descriptor byte was flipped")
	void dropsIndexWithFlippedSignatureByte() throws Exception {
		JarIndexStore store = openStore("flipped-signature");
		Path db = store.ensureIndexed(jar("flipped-signature.jar"), new Symbols());
		int signatureAt = headerInt(db, 24);

		assertDamagedIndexIsDropped(store, db, signatureAt, 0x40, "a flipped descriptor byte");
	}

	@Test
	@DisplayName("drops an index whose since byte was flipped")
	void dropsIndexWithFlippedSinceByte() throws Exception {
		JarIndexStore store = openStore("flipped-since");
		Path db = store.ensureIndexed(jar("flipped-since.jar"), new Symbols());
		int sinceAt = headerInt(db, 28);

		assertDamagedIndexIsDropped(store, db, sinceAt, 0x40, "a flipped since byte");
	}

	@Test
	@DisplayName("drops an index whose header points the since blob at the wrong place")
	void dropsIndexWithShiftedHeaderBoundary() throws Exception {
		JarIndexStore store = openStore("flipped-header");
		Path db = store.ensureIndexed(jar("flipped-header.jar"), new Symbols());
		byte[] bytes = Files.readAllBytes(db);
		int sinceAt = ByteBuffer.wrap(bytes).getInt(28);
		ByteBuffer.wrap(bytes).putInt(28, sinceAt - 4);
		Files.write(db, bytes);

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("a shifted boundary is never answered from")
				.isInstanceOf(IOException.class);
		assertThat(Files.exists(db)).as("the damaged index is dropped").isFalse();
	}

	@Test
	@DisplayName("drops an index that carries the previous checksum layout")
	void dropsIndexWithPreviousChecksumLayout() throws Exception {
		JarIndexStore store = openStore("old-checksums");
		Path db = store.ensureIndexed(jar("old-checksums.jar"), new Symbols());
		byte[] bytes = Files.readAllBytes(db);
		int namesLength = ByteBuffer.wrap(bytes).getInt(12);
		CRC32C crc = new CRC32C();
		crc.update(bytes, JarIndexStore.HEADER, namesLength);
		ByteBuffer.wrap(bytes).putInt(32, (int) crc.getValue());
		Files.write(db, bytes);

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("an index with the old checksums is never answered from")
				.isInstanceOf(IOException.class);
		assertThat(Files.exists(db)).as("the old index is dropped").isFalse();
	}

	/**
	 * Flips one byte in an index file, then proves the damaged index is
	 * never answered from and is dropped so the next call rebuilds it.
	 *
	 * @param store the store holding the index, never null
	 * @param db    the index file, never null
	 * @param at    the byte to flip, inside the file
	 * @param mask  the bits to flip in that byte
	 * @param what  what was damaged, for the failure messages
	 * @throws IOException when the file cannot be read or written
	 */
	private void assertDamagedIndexIsDropped(JarIndexStore store, Path db, int at, int mask, String what)
			throws IOException {
		byte[] bytes = Files.readAllBytes(db);
		bytes[at] = (byte) (bytes[at] ^ mask);
		Files.write(db, bytes);

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as(what + " is never answered from")
				.isInstanceOf(IOException.class)
				.hasMessageContaining("checksum");
		assertThat(Files.exists(db)).as(what + " is dropped").isFalse();
		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("a dropped index is not answered from either")
				.isInstanceOf(IOException.class);
	}

	/**
	 * Reads one header field out of an index file.
	 *
	 * @param db the index file, never null
	 * @param at the offset of the field
	 * @return the value of the field
	 * @throws IOException when the file cannot be read
	 */
	private static int headerInt(Path db, int at) throws IOException {
		return ByteBuffer.wrap(Files.readAllBytes(db)).getInt(at);
	}

	@Test
	@DisplayName("detects a truncated index that claims to be whole")
	void detectsTruncatedIndex() throws Exception {
		JarIndexStore store = openStore("truncated-real");
		Path db = store.ensureIndexed(jar("truncated-real.jar"), new Symbols());
		byte[] bytes = Files.readAllBytes(db);

		Path shortFile = writeCrafted("truncated-short.idx", Arrays.copyOf(bytes, 8));
		assertThatThrownBy(() -> store.findIn(shortFile, "com.example.Widget"))
				.as("a file too short for a header rejected")
				.isInstanceOf(IOException.class);

		Path cutFile = writeCrafted("truncated-cut.idx",
				Arrays.copyOf(bytes, JarIndexStore.HEADER + 20));
		assertThatThrownBy(() -> store.findIn(cutFile, "com.example.Widget"))
				.as("truncated index rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rebuilds an index that was dropped as damaged")
	void rebuildsDroppedIndex() throws Exception {
		JarIndexStore store = openStore("rebuild");
		Path jar = jar("rebuild.jar");
		Path db = store.ensureIndexed(jar, new Symbols());
		byte[] bytes = Files.readAllBytes(db);
		bytes[JarIndexStore.HEADER + 2] = (byte) (bytes[JarIndexStore.HEADER + 2] ^ 0x40);
		Files.write(db, bytes);

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("the damaged index is not answered from")
				.isInstanceOf(IOException.class);

		Path rebuilt = store.ensureIndexed(jar, new Symbols());

		assertThat(rebuilt).as("the same index path").isEqualTo(db);
		assertThat(store.findIn(db, "com.example.Widget")).as("the rebuilt index answers").hasSize(1);
	}

	@Test
	@DisplayName("rejects an index with an unknown checksum algorithm")
	void rejectsUnknownChecksumAlgorithm() throws Exception {
		JarIndexStore store = openStore("algorithm");
		Path db = store.ensureIndexed(jar("algorithm.jar"), new Symbols());
		byte[] bytes = Files.readAllBytes(db);
		bytes[40] = 2;
		Files.write(db, bytes);

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("unknown algorithm rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects an index whose layout does not add up")
	void rejectsBrokenLayout() throws Exception {
		JarIndexStore store = openStore("layout");
		Path db = store.ensureIndexed(jar("layout.jar"), new Symbols());
		byte[] bytes = Files.readAllBytes(db);
		ByteBuffer.wrap(bytes).putInt(8, 1_000_000);
		Files.write(db, withChecksum(bytes));

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("broken layout rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects an index whose row names an unknown kind")
	void rejectsUnknownSymbolKind() throws Exception {
		JarIndexStore store = openStore("kind");
		Path db = store.ensureIndexed(jar("kind.jar"), new Symbols());
		byte[] bytes = Files.readAllBytes(db);
		int metaAt = ByteBuffer.wrap(bytes).getInt(20);
		bytes[metaAt] = (byte) 0xfe;
		Files.write(db, withChecksum(bytes));

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("unknown kind rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects an index whose row points outside the descriptor")
	void rejectsDescriptorOutOfRange() throws Exception {
		JarIndexStore store = openStore("descriptor");
		Path db = store.ensureIndexed(jar("descriptor.jar"), new Symbols());
		byte[] bytes = Files.readAllBytes(db);
		int metaAt = ByteBuffer.wrap(bytes).getInt(20);
		ByteBuffer.wrap(bytes).putInt(metaAt + 2, 9_999_999);
		Files.write(db, withChecksum(bytes));

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("descriptor out of range rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects an index whose name offsets do not rise")
	void rejectsBrokenNameOffsets() throws Exception {
		JarIndexStore store = openStore("offsets");
		Path db = store.ensureIndexed(jar("offsets.jar"), new Symbols());
		byte[] bytes = Files.readAllBytes(db);
		int offsetsAt = ByteBuffer.wrap(bytes).getInt(16);
		ByteBuffer.wrap(bytes).putInt(offsetsAt, 9_999_999);
		Files.write(db, withChecksum(bytes));

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("broken name offsets rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects a file that carries the right size but no magic")
	void rejectsFileWithoutMagic() throws Exception {
		JarIndexStore store = openStore("no-magic");
		Path db = state.resolve("no-magic.idx");
		Files.write(db, new byte[JarIndexStore.HEADER]);

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("file without magic rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects an index whose header does not describe its own layout")
	void rejectsBrokenHeader() throws Exception {
		JarIndexStore store = openStore("broken-header");
		byte[] bytes = realIndexBytes("broken-header");

		assertRejected(store, bytes, 16, 7, "wrong offset table");
		assertRejected(store, bytes, 20, 0, "wrong metadata offset");
		assertRejected(store, bytes, 24, 0, "wrong descriptor offset");
		assertRejected(store, bytes, 28, 0, "wrong since offset");
		assertRejected(store, bytes, 28, 999_999, "since offset past the end");
		assertRejected(store, bytes, 8, -1, "negative row count");
		assertRejected(store, bytes, 12, -1, "negative name length");
		assertRejected(store, bytes, 0, 0, "no magic");
	}

	@Test
	@DisplayName("rejects an index whose name offsets fall")
	void rejectsFallingNameOffsets() throws Exception {
		JarIndexStore store = openStore("falling-offsets");
		byte[] bytes = realIndexBytes("falling-offsets");
		int offsetsAt = ByteBuffer.wrap(bytes).getInt(16);
		ByteBuffer.wrap(bytes).putInt(offsetsAt + 2 * Integer.BYTES, 5);

		assertThatThrownBy(() -> store.findIn(writeCrafted("falling-offsets.idx", withChecksum(bytes)),
				"com.example.Widget"))
				.as("falling name offsets rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects an index whose descriptor points backwards")
	void rejectsNegativeDescriptor() throws Exception {
		JarIndexStore store = openStore("negative-descriptor");
		byte[] bytes = realIndexBytes("negative-descriptor");
		int metaAt = ByteBuffer.wrap(bytes).getInt(20);
		ByteBuffer.wrap(bytes).putInt(metaAt + 2, -1);

		assertThatThrownBy(() -> store.findIn(
				writeCrafted("negative-descriptor.idx", withChecksum(bytes)), "com.example.Widget"))
				.as("negative descriptor rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("refuses an index file that is larger than the store allows")
	void refusesOversizedIndex() throws Exception {
		long before = JarIndexStore.maxIndexBytes;
		JarIndexStore.maxIndexBytes = 8L;
		try {
			JarIndexStore store = openStore("oversized-file");
			Path db = store.ensureIndexed(jar("oversized-file.jar"), new Symbols());

			assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
					.as("oversized index rejected")
					.isInstanceOf(IOException.class);
		} finally {
			JarIndexStore.maxIndexBytes = before;
		}
	}

	@Test
	@DisplayName("keeps files that are not indexes")
	void keepsForeignFiles() throws Exception {
		Path dir = state.resolve("foreign-files");
		Files.createDirectories(dir);
		Path note = dir.resolve("notes.txt");
		Files.writeString(note, "not an index");
		Path stale = dir.resolve("oldsha.v3.idx");
		Files.writeString(stale, "old format");
		Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 7_200_000L));

		openStore("foreign-files");

		assertThat(Files.exists(note)).as("foreign file kept").isTrue();
		assertThat(Files.exists(stale)).as("older format removed").isFalse();
	}

	@Test
	@DisplayName("searches with a needle longer than the names")
	void searchesWithLongNeedle() throws Exception {
		JarIndexStore store = openStore("long-needle");
		Path db = store.ensureIndexed(jar("long-needle.jar"), new Symbols());

		assertThat(store.searchIn(db, "com.example.Widget.build.muchLonger", 5))
				.as("substring longer than every name")
				.isEmpty();
		assertThat(store.searchPrefix(db, "com.example.Widget.build.muchLonger", 5))
				.as("prefix longer than every name")
				.isEmpty();
	}

	@Test
	@DisplayName("answers from an index that holds a plain name")
	void answersFromPlainName() throws Exception {
		JarIndexStore store = openStore("plain-name");
		Path db = store.ensureIndexed(jar("plain-name.jar"), new PlainSymbols());

		assertThat(store.findIn(db, "Widget")).as("name without a package").hasSize(1);
		assertThat(store.findIn(db, "Widget").get(0).parentFqn()).as("no declaring type").isNull();
		assertThat(store.findIn(db, "com.example.Widget").get(0).forRemoval())
				.as("removal flag kept")
				.isTrue();
	}

	@Test
	@DisplayName("answers from an index that holds no symbols")
	void answersFromEmptyIndex() throws Exception {
		JarIndexStore store = openStore("empty-index");
		Path db = store.ensureIndexed(jar("empty-index.jar"), jarFile -> List.of());

		assertThat(store.findIn(db, "com.example.Widget")).as("no match").isEmpty();
		assertThat(store.existsIn(db, "com.example.Widget")).as("not present").isFalse();
		assertThat(store.searchIn(db, "Widget", 5)).as("no match").isEmpty();
		assertThat(store.maybeMatches(db, "Widget", false)).as("no match").isFalse();
	}

	private void assertRejected(JarIndexStore store, byte[] bytes, int at, int value, String what)
			throws IOException {
		byte[] crafted = bytes.clone();
		ByteBuffer.wrap(crafted).putInt(at, value);
		Path db = writeCrafted("crafted-" + at + "-" + value + ".idx", withChecksum(crafted));

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as(what + " rejected")
				.isInstanceOf(IOException.class);
	}

	/**
	 * Recomputes the checksum of an index file and stores it, so a
	 * crafted file passes the checksum and reaches the structural check
	 * it was built for. The checksum covers the whole payload, every
	 * byte after the header, and is stored in the header at offset 32.
	 *
	 * @param bytes the file bytes, never null
	 * @return the same array, with the checksum written into it
	 */
	private static byte[] withChecksum(byte[] bytes) {
		CRC32C crc = new CRC32C();
		crc.update(bytes, JarIndexStore.HEADER, bytes.length - JarIndexStore.HEADER);
		ByteBuffer.wrap(bytes).putInt(32, (int) crc.getValue());
		return bytes;
	}

	private byte[] realIndexBytes(String name) throws Exception {
		JarIndexStore store = openStore("craft-" + name);
		Path db = store.ensureIndexed(jar(name + ".jar"), new Symbols());
		return Files.readAllBytes(db);
	}

	private Path writeCrafted(String fileName, byte[] bytes) throws IOException {
		Path db = state.resolve(fileName);
		Files.write(db, bytes);
		return db;
	}

	@Test
	@DisplayName("rejects an index of an unknown version")
	void rejectsUnknownVersion() throws Exception {
		JarIndexStore store = openStore("version");
		Path db = state.resolve("version.idx");
		ByteBuffer buffer = ByteBuffer.allocate(JarIndexStore.HEADER);
		buffer.putInt(0x49545449);
		buffer.putInt(99);
		Files.write(db, buffer.array());

		assertThatThrownBy(() -> store.findIn(db, "com.example.Widget"))
				.as("unknown version rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("an empty query matches every name")
	void emptyQueryMatchesEverything() throws Exception {
		JarIndexStore store = openStore("empty-query");
		Path db = store.ensureIndexed(jar("empty.jar"), new Symbols());

		assertThat(store.searchIn(db, "", 10)).as("empty substring search").isNotEmpty();
		assertThat(store.maybeMatches(db, "", false)).as("empty segment prefilter").isTrue();
	}

	@Test
	@DisplayName("rejects a row whose descriptor is too long")
	void rejectsOversizedSignature() throws Exception {
		JarIndexStore store = openStore("wide-signature");
		String wide = "(" + "L".repeat(4000) + ")V";
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.METHOD, wide, "com.example.Widget", false, "", false));

		assertThatThrownBy(() -> store.ensureIndexed(jar("wide-signature.jar"), indexer))
				.as("oversized descriptor fails")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("keeps index files of the current format")
	void keepsCurrentFormatFiles() throws Exception {
		Path dir = state.resolve("prune");
		Files.createDirectories(dir);
		Path keep = dir.resolve("current.v4.idx");
		Files.writeString(keep, "keep me");
		Path stale = dir.resolve("oldsha.mv.db");
		Files.writeString(stale, "old me");
		Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 7_200_000L));

		openStore("prune");

		assertThat(Files.exists(keep)).as("current format kept").isTrue();
		assertThat(Files.exists(stale)).as("older format removed").isFalse();
	}

	@Test
	@DisplayName("derives the declaring type and keeps members apart")
	void derivesDeclaringType() throws Exception {
		JarIndexStore store = openStore("parents");
		Path db = store.ensureIndexed(jar("parents.jar"), new Symbols());

		List<Symbol> found = store.findIn(db, "com.example.Widget.build");

		assertThat(found).as("member found").hasSize(1);
		assertThat(found.get(0).parentFqn()).as("declaring type derived")
				.isEqualTo("com.example.Widget");
		assertThat(found.get(0).signature()).as("descriptor kept byte for byte")
				.isEqualTo("()V");
		assertThat(found.get(0).deprecatedSince()).as("since kept byte for byte")
				.isEqualTo("2.0");
		assertThat(store.findIn(db, "com.example.Widget").get(0).parentFqn())
				.as("a type has no declaring type")
				.isNull();
	}

	@Test
	@DisplayName("filters kinds when asked")
	void filtersKinds() throws Exception {
		JarIndexStore store = openStore("kinds");
		Path db = store.ensureIndexed(jar("kinds.jar"), new Symbols());

		assertThat(store.searchIn(db, "com.example", 10, Set.of(SymbolKind.METHOD)))
				.as("methods only")
				.allSatisfy(symbol -> assertThat(symbol.kind()).isEqualTo(SymbolKind.METHOD));
		assertThat(store.searchPrefix(db, "com.example", 10, Set.of(SymbolKind.CLASS)))
				.as("classes only")
				.allSatisfy(symbol -> assertThat(symbol.kind()).isEqualTo(SymbolKind.CLASS));
		assertThat(store.searchInInsensitive(db, "COM.EXAMPLE", 10, Set.of(SymbolKind.CLASS)))
				.as("classes only, case insensitive")
				.allSatisfy(symbol -> assertThat(symbol.kind()).isEqualTo(SymbolKind.CLASS));
	}

	@Test
	@DisplayName("releases held indexes when the budget is exceeded")
	void releasesNamesUnderBudget() throws Exception {
		long before = JarIndexStore.cacheBudget;
		JarIndexStore.cacheBudget = 1L;
		try {
			JarIndexStore store = openStore("budget");
			Path db = store.ensureIndexed(jar("budget.jar"), new Symbols());

			assertThat(store.findIn(db, "com.example.Widget")).as("first lookup").hasSize(1);
			assertThat(store.findIn(db, "com.example.Widget")).as("lookup after a release").hasSize(1);
			assertThat(store.findIn(db, "com.example.Absent")).as("miss after a release").isEmpty();
			assertThat(store.fileReads()).as("file read again after the release").isEqualTo(3);
		} finally {
			JarIndexStore.cacheBudget = before;
		}
	}

	@Test
	@DisplayName("serves the same index to many threads at once")
	void servesManyThreadsAtOnce() throws Exception {
		int before = JarIndexStore.trimInterval;
		JarIndexStore.trimInterval = 1;
		try {
			JarIndexStore store = openStore("threads");
			Path db = store.ensureIndexed(jar("threads.jar"), new Symbols());
			ExecutorService pool = Executors.newFixedThreadPool(4);
			try {
				List<Future<List<Symbol>>> results = new ArrayList<>();
				for (int i = 0; i < 4; i++) {
					results.add(pool.submit(() -> store.findIn(db, "com.example.Widget")));
				}
				for (Future<List<Symbol>> result : results) {
					assertThat(result.get(10, TimeUnit.SECONDS)).as("thread answered").hasSize(1);
				}
			} finally {
				pool.shutdownNow();
			}
		} finally {
			JarIndexStore.trimInterval = before;
		}
	}

	@Test
	@DisplayName("measures the lookup path over many index files")
	void measuresLookupPath() throws Exception {
		Path dir = state.resolve("bench");
		Files.createDirectories(dir);
		JarIndexStore writer = openStore("bench-writer");
		List<Path> dbs = new ArrayList<>();
		List<String> probes = new ArrayList<>();
		for (int file = 0; file < BENCH_FILES; file++) {
			Path jar = dir.resolve("bench-" + file + ".jar");
			Files.write(jar, ("bench-" + file).getBytes(StandardCharsets.UTF_8));
			dbs.add(writer.ensureIndexed(jar, new BenchIndexer(file)));
			probes.add("com.example.bench" + file + ".Type" + (BENCH_SYMBOLS / 2));
		}

		JarIndexStore reader = openStore("bench-reader");
		long cold = timeLookups(reader, dbs, probes);
		long warm = Long.MAX_VALUE;
		for (int round = 0; round < 3; round++) {
			warm = Math.min(warm, timeLookups(reader, dbs, probes));
		}
		long load = Long.MAX_VALUE;
		for (int round = 0; round < 3; round++) {
			load = Math.min(load, timeLoads(dbs, probes));
		}
		long[] checksums = timeChecksums(dbs);

		System.out.println("BENCH index-lookup files=" + BENCH_FILES + " symbols=" + BENCH_SYMBOLS
				+ " lookups=" + BENCH_LOOKUPS + " cold=" + millis(cold) + "ms warm=" + millis(warm)
				+ "ms load=" + millis(load) + "ms checksumWhole=" + micros(checksums[0])
				+ "us checksumRegions=" + micros(checksums[1]) + "us payload=" + checksums[2] / (1024 * 1024)
				+ "MB");
	}

	/**
	 * Times the checksum over the measured payload twice: once over the
	 * whole payload, which is what the file now carries, and once over
	 * the name and metadata regions only, which is what it carried
	 * before. The file bytes are read first, so only the hashing is
	 * timed.
	 *
	 * @param dbs the measured index files, never null
	 * @return the whole payload nanoseconds, the region nanoseconds and
	 *         the payload size in bytes
	 * @throws IOException when a file cannot be read
	 */
	private long[] timeChecksums(List<Path> dbs) throws IOException {
		List<byte[]> files = new ArrayList<>();
		long payload = 0;
		for (Path db : dbs) {
			byte[] bytes = Files.readAllBytes(db);
			files.add(bytes);
			payload += bytes.length - JarIndexStore.HEADER;
		}
		long start = System.nanoTime();
		CRC32C whole = new CRC32C();
		for (byte[] bytes : files) {
			whole.update(bytes, JarIndexStore.HEADER, bytes.length - JarIndexStore.HEADER);
		}
		long wholeElapsed = System.nanoTime() - start;
		start = System.nanoTime();
		CRC32C regions = new CRC32C();
		for (byte[] bytes : files) {
			ByteBuffer header = ByteBuffer.wrap(bytes);
			int namesLength = header.getInt(12);
			int metaAt = header.getInt(20);
			int count = header.getInt(8);
			regions.update(bytes, JarIndexStore.HEADER, namesLength);
			regions.update(bytes, metaAt, count * JarIndexStore.META);
		}
		long regionElapsed = System.nanoTime() - start;
		if (whole.getValue() == regions.getValue()) {
			throw new AssertionError("the measurement hashed the same bytes twice");
		}
		return new long[] {wholeElapsed, regionElapsed, payload};
	}

	/**
	 * Times the load of every measured index file with a cold cache.
	 * The store is fresh, so every file is read and checked once, which
	 * is where the checksum is paid.
	 *
	 * @param dbs    the measured index files, never null
	 * @param probes one known name per file, never null
	 * @return the elapsed nanoseconds
	 * @throws IOException when a file cannot be read
	 */
	private long timeLoads(List<Path> dbs, List<String> probes) throws IOException {
		JarIndexStore fresh = openStore("bench-loader");
		long start = System.nanoTime();
		for (int i = 0; i < dbs.size(); i++) {
			if (fresh.findIn(dbs.get(i), probes.get(i)).isEmpty()) {
				throw new AssertionError("the measurement lost a hit");
			}
		}
		return System.nanoTime() - start;
	}

	private static long timeLookups(JarIndexStore store, List<Path> dbs, List<String> probes) throws IOException {
		long start = System.nanoTime();
		int hits = 0;
		for (int i = 0; i < BENCH_LOOKUPS; i++) {
			int slot = i % dbs.size();
			if (!store.findIn(dbs.get(slot), probes.get(slot)).isEmpty()) {
				hits++;
			}
		}
		long elapsed = System.nanoTime() - start;
		if (hits != BENCH_LOOKUPS) {
			throw new AssertionError("measurement lost a hit: " + hits);
		}
		return elapsed;
	}

	private static long millis(long nanos) {
		return Math.round(nanos / 1_000_000.0);
	}

	private static long micros(long nanos) {
		return Math.round(nanos / 1_000.0);
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

	/**
	 * Canned symbols: one type, one method, one field.
	 */
	private static final class Symbols implements LibraryIndexer {

		@Override
		public List<Symbol> index(Path jar) {
			return List.of(
					new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false),
					new Symbol("com.example.Widget.build", SymbolKind.METHOD, "()V",
							"com.example.Widget", true, "2.0", false),
					new Symbol("com.example.Widget.version", SymbolKind.FIELD, "Ljava/lang/String;",
							"com.example.Widget", false, "", false));
		}
	}

	/**
	 * Canned symbols with a name that carries no package and a removal
	 * flag on every row.
	 */
	private static final class PlainSymbols implements LibraryIndexer {

		@Override
		public List<Symbol> index(Path jar) {
			return List.of(
					new Symbol("Widget", SymbolKind.CLASS, null, null, false, "", true),
					new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", true));
		}
	}

	/**
	 * Canned symbols for the measurement: one file worth of type names
	 * with a descriptor, so the index carries the same shape as a real
	 * library.
	 */
	private static final class BenchIndexer implements LibraryIndexer {
		private final int file;

		BenchIndexer(int file) {
			this.file = file;
		}

		@Override
		public List<Symbol> index(Path jar) {
			List<Symbol> symbols = new ArrayList<>(BENCH_SYMBOLS);
			for (int i = 0; i < BENCH_SYMBOLS; i++) {
				symbols.add(new Symbol("com.example.bench" + file + ".Type" + i, SymbolKind.CLASS,
						"Ljava/lang/Object;()V", null, false, "", false));
			}
			return symbols;
		}
	}
}

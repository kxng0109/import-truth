package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies JDK answers: plain types, nested types, and absent names.
 */
@DisplayName("JdkIndex")
final class JdkIndexTest {

	private final JdkIndex jdk = new JdkIndex();

	@TempDir
	private Path state;

	@Test
	@DisplayName("finds a JDK type")
	void findsJdkType() {
		assertThat(jdk.exists("java.util.ArrayList")).as("JDK type present").isTrue();
	}

	@Test
	@DisplayName("finds a nested JDK type by dotted name")
	void findsNestedJdkType() {
		assertThat(jdk.exists("java.util.Map.Entry")).as("nested JDK type present").isTrue();
	}

	@Test
	@DisplayName("misses a name from nowhere")
	void missesAbsentName() {
		assertThat(jdk.exists("com.example.Nope")).as("absent name missing").isFalse();
	}

	@Test
	@DisplayName("evicts eldest probes under typo storms")
	void evictsEldestProbes() {
		JdkIndex index = new JdkIndex();
		for (int i = 0; i < 4100; i++) {
			assertThat(index.exists("com.example.Nope" + i)).as("typo miss").isFalse();
		}

		assertThat(index.exists("com.example.Nope0")).as("evicted re-probe agrees").isFalse();
		assertThat(index.exists("java.util.ArrayList")).as("true survives").isTrue();
	}

	@Test
	@DisplayName("reports its own version")
	void reportsVersion() {
		assertThat(jdk.jdkVersion()).as("JDK version recorded").isNotBlank();
		assertThat(jdk.runningRelease()).as("running release sane").isGreaterThanOrEqualTo(21);
	}

	@Test
	@DisplayName("answers per target release from ct.sym")
	void answersPerRelease() {
		int running = jdk.runningRelease();

		assertThat(jdk.existsIn("java.util.List", 8)).as("ancient type in 8").isTrue();
		assertThat(jdk.existsIn("java.util.List", running)).as("current type now").isTrue();
		assertThat(jdk.packageExistsIn("java.util", 8)).as("ancient package in 8").isTrue();
		if (running >= 22) {
			assertThat(jdk.existsIn("java.util.stream.Gatherers", 21))
					.as("newer type absent in 21").isFalse();
			assertThat(jdk.existsIn("java.util.stream.Gatherers", running))
					.as("newer type present now").isTrue();
		}
		assertThat(jdk.existsIn("com.example.Nope", 8)).as("absent everywhere").isFalse();
		assertThat(jdk.existsIn("java.util.List", running + 1))
				.as("future release falls back").isEqualTo(jdk.exists("java.util.List"));
		assertThat(jdk.existsIn("java.util.Map.Entry", 8)).as("nested type in 8").isTrue();
		assertThat(jdk.existsIn("java.util.List", 9)).as("type in 9").isTrue();
		assertThat(jdk.existsIn("java.util.List", 7)).as("old release falls back").isTrue();
		assertThat(jdk.packageExistsIn("java.util", running + 1))
				.as("future package falls back")
				.isEqualTo(jdk.packageExists("java.util"));
	}

	@Test
	@DisplayName("answers when the JDK has no readable ct.sym")
	void answersWithoutCtSym() {
		Path archive = state.resolve("absent").resolve("ct.sym");
		JdkIndex index = archiveOnly(archive, state);

		assertThat(index.existsIn("java.util.List", 17)).as("falls back to the running image").isTrue();
		assertThat(index.existsIn("com.example.Nope", 17)).as("absent everywhere").isFalse();
		assertThat(index.packageExistsIn("java.util", 17)).as("package falls back").isTrue();
	}

	@Test
	@DisplayName("caches the missing archive instead of parsing it again")
	void cachesMissingArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		JdkIndex index = archiveOnly(archive, state);

		assertThat(index.existsIn("com.example.Synthetic", 17)).as("nothing known yet").isFalse();

		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");

		assertThat(index.existsIn("com.example.Synthetic", 17))
				.as("the archive was not parsed again")
				.isFalse();
	}

	@Test
	@DisplayName("serves the release index from the disk cache")
	void servesReleaseIndexFromDisk() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		JdkIndex first = archiveOnly(archive, state);

		assertThat(first.existsIn("com.example.Synthetic", 17)).as("read from the archive").isTrue();

		Files.delete(archive);
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("read from the cache").isTrue();
	}

	@Test
	@DisplayName("ignores a corrupt cache file")
	void ignoresCorruptCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), "not a cache file at all");
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("parses the archive again when it changes")
	void reparsesChangedArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);

		writeArchive(archive, "H/java.base/com/example/Other.sig", "H/java.base/com/example/Third.sig");
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the stale cache is not used").isFalse();
		assertThat(second.existsIn("com.example.Other", 17)).as("the new archive is read").isTrue();
	}

	@Test
	@DisplayName("parses the archive again when it is touched")
	void reparsesTouchedArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);

		Files.setLastModifiedTime(archive, FileTime.fromMillis(1_000_000L));
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the stale cache is not used").isTrue();
	}

	@Test
	@DisplayName("caches the release index under the state directory")
	void cachesUnderTheStateDirectory() throws Exception {
		JdkIndex index = archiveOnly(defaultArchive(), state);

		assertThat(index.existsIn("java.util.List", 17)).as("documented type").isTrue();
		assertThat(state.resolve("jdk")).as("the cache directory").isDirectory();
		try (Stream<Path> files = Files.list(state.resolve("jdk"))) {
			assertThat(files.toList()).as("one cache file").hasSize(1);
		}
	}

	@Test
	@DisplayName("answers a covered release from the bundled table")
	void answersCoveredReleaseFromTable() {
		JdkIndex index = new JdkIndex(null, null, List.of());
		int release = newestCoveredRelease();

		assertThat(index.existsIn("java.util.List", release)).as("documented in the table").isTrue();
		assertThat(index.existsIn("com.example.Nope", release)).as("absent from the table").isFalse();
		assertThat(index.packageExistsIn("java.util", release)).as("package in the table").isTrue();
	}

	@Test
	@DisplayName("keeps the JDK surface unconsulted for a release nothing covers")
	void keepsUncoveredReleaseUnconsulted() {
		JdkIndex index = new JdkIndex(null, null, List.of());
		int uncovered = newestCoveredRelease() + 5;

		assertThat(index.existsIn("java.util.List", uncovered)).as("nothing to consult").isFalse();
		assertThat(index.available()).as("the JDK surface could not be consulted").isFalse();
	}

	@Test
	@DisplayName("falls back to the archive for a release the table does not cover")
	void fallsBackToTheArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		JdkIndex index = new JdkIndex(archive, state, partialTable());

		assertThat(index.existsIn("com.example.Synthetic", 17)).as("read from the archive").isTrue();
		assertThat(index.existsIn("java.util.List", 8)).as("read from the table").isTrue();
		assertThat(index.existsIn("java.util.List", 17)).as("the table does not cover it").isFalse();
	}

	@Test
	@DisplayName("answers the archive when the table covers nothing")
	void answersFromTheArchiveWhenTheTableIsEmpty() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		JdkIndex index = archiveOnly(archive, state);

		assertThat(index.existsIn("com.example.Synthetic", 17)).as("read from the archive").isTrue();
		assertThat(index.existsIn("com.example.Other", 17)).as("absent from the archive").isFalse();
	}

	@Test
	@DisplayName("reports the JDK surface as consulted only for a covered release")
	void reportsAvailability() {
		JdkIndex index = new JdkIndex(null, null, List.of());
		int covered = newestCoveredRelease();

		assertThat(index.available()).as("nothing was consulted yet").isFalse();
		assertThat(index.existsIn("java.util.List", covered)).as("the table answers").isTrue();
		assertThat(index.available()).as("the table can be consulted").isTrue();
		assertThat(index.existsIn("java.util.List", covered + 5)).as("nothing answers").isFalse();
		assertThat(index.available()).as("nothing can be consulted").isFalse();
	}

	@Test
	@DisplayName("forgets the release when the question names none")
	void forgetsTheRelease() {
		JdkIndex index = new JdkIndex(null, null, List.of());

		assertThat(index.existsIn("java.util.List", newestCoveredRelease())).as("the table answers").isTrue();
		assertThat(index.available()).as("the table can be consulted").isTrue();
		index.exists("java.util.List");
		assertThat(index.available()).as("the question named no release").isFalse();
	}

	@Test
	@DisplayName("forgets the release when a package question names none")
	void forgetsTheReleaseForPackages() {
		JdkIndex index = new JdkIndex(null, null, List.of());

		assertThat(index.existsIn("java.util.List", newestCoveredRelease())).as("the table answers").isTrue();
		assertThat(index.available()).as("the table can be consulted").isTrue();
		index.packageExists("java.util");
		assertThat(index.available()).as("the question named no release").isFalse();
	}

	@Test
	@DisplayName("answers with an empty table when there is none")
	void answersWithNoTable() {
		JdkIndex index = new JdkIndex(null, null, List.of(), null);

		assertThat(index.existsIn("java.util.List", 17)).as("nothing to consult").isFalse();
		assertThat(index.available()).as("nothing can be consulted").isFalse();
	}

	@Test
	@DisplayName("answers a package from the archive")
	void answersPackageFromTheArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		JdkIndex index = archiveOnly(archive, state);

		assertThat(index.packageExistsIn("com.example", 17)).as("read from the archive").isTrue();
		assertThat(index.packageExistsIn("com.other", 17)).as("absent from the archive").isFalse();
	}

	@Test
	@DisplayName("answers a package from the running image for an uncovered release")
	void answersPackageFromTheRunningImage() {
		JdkIndex index = new JdkIndex(null, null);

		assertThat(index.packageExistsIn("java.util", newestCoveredRelease() + 5)).as("the running image")
				.isTrue();
	}

	@Test
	@DisplayName("has no documented API in an archive without that release")
	void emptyArchiveHasNoDocumentedApi() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "A/java.base/com/example/Other.sig");
		JdkIndex index = archiveOnly(archive, state);

		assertThat(index.existsIn("com.example.Other", 17)).as("no documented API").isFalse();
	}

	@Test
	@DisplayName("ignores an unreadable cache file")
	void ignoresUnreadableCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);

		Files.write(onlyCacheFile(), new byte[] { (byte) 0xff, (byte) 0xfe, (byte) 0xfd });
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("ignores a cache written for another archive")
	void ignoresCacheForAnotherArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), String.join("\n", List.of(
				"importtruth-jdk-index 1",
				"path " + state.resolve("other").toAbsolutePath().normalize(),
				"size 1",
				"modified 1",
				"release 17",
				"classes 1",
				"com/example/Fake.class",
				"packages 1",
				"com/example")) + "\n");
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Fake", 17)).as("the foreign cache is not used").isFalse();
		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("ignores a truncated cache file")
	void ignoresTruncatedCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), String.join("\n", cacheHeader(archive, 17))
				+ "\nclasses 3\nonly/one.class\n");
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("ignores a cache with a broken count")
	void ignoresCacheWithBrokenCount() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), String.join("\n", List.of(
				"importtruth-jdk-index 1",
				"path " + archive.toAbsolutePath().normalize(),
				"size not-a-number",
				"modified 1",
				"release 17")) + "\n");
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("ignores a cache without a header")
	void ignoresCacheWithoutHeader() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), "importtruth-jdk-index 1\n");
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("answers when the cache cannot be written")
	void answersWhenTheCacheCannotBeWritten() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		Files.writeString(state.resolve("jdk"), "not a directory");
		JdkIndex index = archiveOnly(archive, state);

		assertThat(index.existsIn("com.example.Synthetic", 17)).as("the archive was parsed").isTrue();
	}

	@Test
	@DisplayName("ignores every malformed cache file")
	void ignoresEveryMalformedCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);
		Path cache = onlyCacheFile();
		List<String> header = cacheHeader(archive, 17);
		List<List<String>> broken = List.of(
				List.of(),
				List.of("importtruth-jdk-index 1"),
				List.of("importtruth-jdk-index 2"),
				header.subList(0, 3),
				withHeader(header, "release", "18"),
				withHeader(header, "modified", "not-a-number"),
				withHeader(header, "classes", "99999999"),
				withHeader(header, "packages", "99999999"),
				List.of("importtruth-jdk-index 1", "sizex 1", "modified 1", "release 17"));
		for (List<String> content : broken) {
			Files.writeString(cache, String.join("\n", content) + "\n");
			JdkIndex second = archiveOnly(archive, state);

			assertThat(second.existsIn("com.example.Synthetic", 17))
					.as("the archive was parsed again for " + content)
					.isTrue();
		}
	}

	@Test
	@DisplayName("ignores a cache file that is too large")
	void ignoresOversizedCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		archiveOnly(archive, state).existsIn("com.example.Synthetic", 17);
		Path cache = onlyCacheFile();
		try (RandomAccessFile oversized = new RandomAccessFile(cache.toFile(), "rw")) {
			oversized.setLength(33L * 1024 * 1024);
		}
		JdkIndex second = archiveOnly(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("reads an archive with an entry that has no path")
	void readsEntryWithoutPath() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H", "H/java.base/com/example/Synthetic.sig");
		JdkIndex index = archiveOnly(archive, state);

		assertThat(index.existsIn("com.example.Synthetic", 17)).as("documented type").isTrue();
	}

	/**
	 * Creates an index that reads the archive only. The bundled table
	 * would answer first and hide the archive path these tests cover.
	 *
	 * @param archive the archive to read, or null for none
	 * @param state   the state directory, or null for no disk cache
	 * @return the index, never null
	 */
	private static JdkIndex archiveOnly(Path archive, Path state) {
		return new JdkIndex(archive, state, JdkApiTable.empty());
	}

	/**
	 * Returns the archive of the running JDK.
	 *
	 * @return the archive, never null
	 */
	private static Path defaultArchive() {
		return Path.of(System.getProperty("java.home"), "lib", "ct.sym");
	}

	/**
	 * Returns a table that covers one release only, so the archive
	 * answers for every other release.
	 *
	 * @return the table, never null
	 */
	private static JdkApiTable partialTable() {
		return JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8",
				"# classes 1",
				"java/util/List.class 8")) + "\n");
	}

	/**
	 * Returns the newest release the bundled table covers.
	 *
	 * @return the release, never negative
	 */
	private static int newestCoveredRelease() {
		return JdkApiTable.bundled().releases().stream()
				.mapToInt(Integer::intValue)
				.max()
				.orElseThrow();
	}

	/**
	 * Replaces one header line of a cache file.
	 *
	 * @param header the header lines, never null
	 * @param name   the header to replace, never null
	 * @param value  the new value, never null
	 * @return the changed header lines, never null
	 */
	private static List<String> withHeader(List<String> header, String name, String value) {
		List<String> changed = new ArrayList<>(header);
		for (int i = 0; i < changed.size(); i++) {
			if (changed.get(i).startsWith(name + " ")) {
				changed.set(i, name + " " + value);
			}
		}
		return changed;
	}

	/**
	 * Writes a {@code ct.sym} shaped archive. The entry names follow
	 * the real layout: the release marker, the module, then the class.
	 *
	 * @param file    the archive to write, never null
	 * @param entries the entry names, never null
	 * @throws IOException when the archive cannot be written
	 */
	private static void writeArchive(Path file, String... entries) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			for (String entry : entries) {
				zip.putNextEntry(new ZipEntry(entry));
				zip.closeEntry();
			}
		}
	}

	/**
	 * Renders the header of a cache file. The format is pinned here
	 * on purpose: a cache written by another version has to be
	 * ignored, never misread.
	 *
	 * @param archive the archive the cache describes, never null
	 * @param release the release the cache describes
	 * @return the header lines, never null
	 * @throws IOException when the archive cannot be measured
	 */
	private static List<String> cacheHeader(Path archive, int release) throws IOException {
		return List.of(
				"importtruth-jdk-index 1",
				"path " + archive.toAbsolutePath().normalize(),
				"size " + Files.size(archive),
				"modified " + Files.getLastModifiedTime(archive).toMillis(),
				"release " + release);
	}

	/**
	 * Returns the one cache file the state directory holds.
	 *
	 * @return the cache file, never null
	 * @throws IOException when the cache directory cannot be listed
	 */
	private Path onlyCacheFile() throws IOException {
		Path dir = state.resolve("jdk");
		assertThat(dir).as("the cache directory exists").isDirectory();
		try (Stream<Path> files = Files.list(dir)) {
			List<Path> cached = files.toList();
			assertThat(cached).as("one cache file").hasSize(1);
			return cached.get(0);
		}
	}
}

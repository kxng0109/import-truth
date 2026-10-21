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
		JdkIndex index = new JdkIndex(archive, state);

		assertThat(index.existsIn("java.util.List", 17)).as("falls back to the running image").isTrue();
		assertThat(index.existsIn("com.example.Nope", 17)).as("absent everywhere").isFalse();
		assertThat(index.packageExistsIn("java.util", 17)).as("package falls back").isTrue();
	}

	@Test
	@DisplayName("caches the missing archive instead of parsing it again")
	void cachesMissingArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		JdkIndex index = new JdkIndex(archive, state);

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
		JdkIndex first = new JdkIndex(archive, state);

		assertThat(first.existsIn("com.example.Synthetic", 17)).as("read from the archive").isTrue();

		Files.delete(archive);
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("read from the cache").isTrue();
	}

	@Test
	@DisplayName("ignores a corrupt cache file")
	void ignoresCorruptCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), "not a cache file at all");
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("parses the archive again when it changes")
	void reparsesChangedArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);

		writeArchive(archive, "H/java.base/com/example/Other.sig", "H/java.base/com/example/Third.sig");
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the stale cache is not used").isFalse();
		assertThat(second.existsIn("com.example.Other", 17)).as("the new archive is read").isTrue();
	}

	@Test
	@DisplayName("parses the archive again when it is touched")
	void reparsesTouchedArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);

		Files.setLastModifiedTime(archive, FileTime.fromMillis(1_000_000L));
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the stale cache is not used").isTrue();
	}

	@Test
	@DisplayName("caches the release index under the state directory")
	void cachesUnderTheStateDirectory() throws Exception {
		JdkIndex index = new JdkIndex(state);

		assertThat(index.existsIn("java.util.List", 17)).as("documented type").isTrue();
		assertThat(state.resolve("jdk")).as("the cache directory").isDirectory();
		try (Stream<Path> files = Files.list(state.resolve("jdk"))) {
			assertThat(files.toList()).as("one cache file").hasSize(1);
		}
	}

	@Test
	@DisplayName("has no documented API in an archive without that release")
	void emptyArchiveHasNoDocumentedApi() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "A/java.base/com/example/Other.sig");
		JdkIndex index = new JdkIndex(archive, state);

		assertThat(index.existsIn("com.example.Other", 17)).as("no documented API").isFalse();
	}

	@Test
	@DisplayName("ignores an unreadable cache file")
	void ignoresUnreadableCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);

		Files.write(onlyCacheFile(), new byte[] { (byte) 0xff, (byte) 0xfe, (byte) 0xfd });
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("ignores a cache written for another archive")
	void ignoresCacheForAnotherArchive() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);

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
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Fake", 17)).as("the foreign cache is not used").isFalse();
		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("ignores a truncated cache file")
	void ignoresTruncatedCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), String.join("\n", cacheHeader(archive, 17))
				+ "\nclasses 3\nonly/one.class\n");
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("ignores a cache with a broken count")
	void ignoresCacheWithBrokenCount() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), String.join("\n", List.of(
				"importtruth-jdk-index 1",
				"path " + archive.toAbsolutePath().normalize(),
				"size not-a-number",
				"modified 1",
				"release 17")) + "\n");
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("ignores a cache without a header")
	void ignoresCacheWithoutHeader() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);

		Files.writeString(onlyCacheFile(), "importtruth-jdk-index 1\n");
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("answers when the cache cannot be written")
	void answersWhenTheCacheCannotBeWritten() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		Files.writeString(state.resolve("jdk"), "not a directory");
		JdkIndex index = new JdkIndex(archive, state);

		assertThat(index.existsIn("com.example.Synthetic", 17)).as("the archive was parsed").isTrue();
	}

	@Test
	@DisplayName("ignores every malformed cache file")
	void ignoresEveryMalformedCache() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H/java.base/com/example/Synthetic.sig");
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);
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
			JdkIndex second = new JdkIndex(archive, state);

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
		new JdkIndex(archive, state).existsIn("com.example.Synthetic", 17);
		Path cache = onlyCacheFile();
		try (RandomAccessFile oversized = new RandomAccessFile(cache.toFile(), "rw")) {
			oversized.setLength(33L * 1024 * 1024);
		}
		JdkIndex second = new JdkIndex(archive, state);

		assertThat(second.existsIn("com.example.Synthetic", 17)).as("the archive was parsed again").isTrue();
	}

	@Test
	@DisplayName("reads an archive with an entry that has no path")
	void readsEntryWithoutPath() throws Exception {
		Path archive = state.resolve("ct.sym");
		writeArchive(archive, "H", "H/java.base/com/example/Synthetic.sig");
		JdkIndex index = new JdkIndex(archive, state);

		assertThat(index.existsIn("com.example.Synthetic", 17)).as("documented type").isTrue();
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

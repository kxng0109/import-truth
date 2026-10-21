package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the bundled JDK API table: the answers it gives, the answers
 * it refuses to give, the tables it ignores, and the reproducibility of
 * the generator that writes it.
 */
@DisplayName("JdkApiTable")
final class JdkApiTableTest {

	@TempDir
	private Path state;

	@Test
	@DisplayName("answers a documented class for a covered release")
	void answersDocumentedClass() {
		JdkApiTable table = JdkApiTable.bundled();

		assertThat(table.isEmpty()).as("the build ships a table").isFalse();
		int newest = newest(table);
		assertThat(table.answer("java/util/List.class", newest))
				.as("List is documented in the newest release")
				.isEqualTo(JdkApiTable.Answer.DOCUMENTED);
		if (table.covers(25)) {
			assertThat(table.answer("java/util/List.class", 25))
					.as("List is documented in release 25")
					.isEqualTo(JdkApiTable.Answer.DOCUMENTED);
		}
	}

	@Test
	@DisplayName("keeps an uncovered release unverified rather than answered")
	void keepsUncoveredReleaseUnverified() {
		JdkApiTable table = JdkApiTable.bundled();
		int uncovered = newest(table) + 5;

		assertThat(table.covers(uncovered)).as("the release is not covered").isFalse();
		assertThat(table.answer("java/util/List.class", uncovered))
				.as("unverified, not a verdict")
				.isEqualTo(JdkApiTable.Answer.UNVERIFIED);
		assertThat(table.packageAnswer("java/util", uncovered))
				.as("unverified, not a verdict")
				.isEqualTo(JdkApiTable.Answer.UNVERIFIED);
	}

	@Test
	@DisplayName("answers an undocumented class for a covered release")
	void answersUndocumentedClass() {
		JdkApiTable table = JdkApiTable.bundled();
		int newest = newest(table);

		assertThat(table.answer("com/example/Nope.class", newest))
				.as("a name from nowhere is not documented")
				.isEqualTo(JdkApiTable.Answer.NOT_DOCUMENTED);
		assertThat(table.documents("com/example/Nope.class", newest)).as("not documented").isFalse();
	}

	@Test
	@DisplayName("covers every release from eight to its newest")
	void coversEveryRelease() {
		JdkApiTable table = JdkApiTable.bundled();
		Set<Integer> releases = table.releases();
		int newest = newest(table);

		assertThat(releases).as("release eight is covered").contains(8);
		for (int release = 8; release <= newest; release++) {
			assertThat(table.covers(release)).as("release " + release + " is covered").isTrue();
		}
		assertThat(table.covers(newest + 1)).as("the next release is not covered").isFalse();
	}

	@Test
	@DisplayName("ignores a corrupt table")
	void ignoresCorruptTable() {
		JdkApiTable table = JdkApiTable.parse("this is not a table at all");

		assertThat(table.isEmpty()).as("the corrupt table is ignored").isTrue();
		assertThat(table.covers(25)).as("nothing is covered").isFalse();
		assertThat(table.answer("java/util/List.class", 25)).as("nothing is answered")
				.isEqualTo(JdkApiTable.Answer.UNVERIFIED);
	}

	@Test
	@DisplayName("ignores a truncated table")
	void ignoresTruncatedTable() {
		String whole = rendered();
		int cut = whole.indexOf('\n', whole.indexOf('\n', whole.indexOf('\n') + 1) + 1);

		JdkApiTable table = JdkApiTable.parse(whole.substring(0, cut));

		assertThat(table.isEmpty()).as("the truncated table is ignored").isTrue();
	}

	@Test
	@DisplayName("ignores a table without a header")
	void ignoresTableWithoutHeader() {
		JdkApiTable table = JdkApiTable.parse("java/util/List.class 8-26\n");

		assertThat(table.isEmpty()).as("the headerless table is ignored").isTrue();
	}

	@Test
	@DisplayName("ignores a table whose class count does not match")
	void ignoresTableWithWrongCount() {
		JdkApiTable table = JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8",
				"# classes 7",
				"java/util/List.class 8")) + "\n");

		assertThat(table.isEmpty()).as("the inconsistent table is ignored").isTrue();
	}

	@Test
	@DisplayName("ignores a table with a broken release list")
	void ignoresTableWithBrokenRelease() {
		JdkApiTable table = JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8",
				"# classes 1",
				"java/util/List.class eight")) + "\n");

		assertThat(table.isEmpty()).as("the broken table is ignored").isTrue();
	}

	@Test
	@DisplayName("reads a table from a classpath directory")
	void readsTableFromDirectory() throws Exception {
		Path root = state.resolve("classes");
		Path tableDir = root.resolve("jdk-api");
		Files.createDirectories(tableDir);
		Files.write(tableDir.resolve("jdk-api-21.txt"), rendered().getBytes(StandardCharsets.UTF_8));
		try (URLClassLoader loader = new URLClassLoader(new URL[] { root.toUri().toURL() }, null)) {
			JdkApiTable table = JdkApiTable.load(loader);

			assertThat(table.isEmpty()).as("the table was found").isFalse();
			assertThat(table.answer("java/util/List.class", newest(table)))
					.as("the table answers").isEqualTo(JdkApiTable.Answer.DOCUMENTED);
		}
	}

	@Test
	@DisplayName("reads a table from a jar")
	void readsTableFromJar() throws Exception {
		Path jar = state.resolve("tables.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("jdk-api/"));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("jdk-api/jdk-api-21.txt"));
			zip.write(rendered().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null)) {
			JdkApiTable table = JdkApiTable.load(loader);

			assertThat(table.isEmpty()).as("the table was found").isFalse();
			assertThat(table.answer("java/util/List.class", newest(table)))
					.as("the table answers").isEqualTo(JdkApiTable.Answer.DOCUMENTED);
		}
	}

	@Test
	@DisplayName("finds no table on a classpath without one")
	void findsNoTable() throws Exception {
		Path root = state.resolve("empty");
		Files.createDirectories(root);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { root.toUri().toURL() }, null)) {
			JdkApiTable table = JdkApiTable.load(loader);

			assertThat(table.isEmpty()).as("no table was found").isTrue();
			assertThat(table.covers(25)).as("nothing is covered").isFalse();
		}
	}

	@Test
	@DisplayName("generates the same table every time")
	void generatesDeterministically() throws Exception {
		byte[] first = JdkApiTableGenerator.render(ctSym()).content();
		byte[] second = JdkApiTableGenerator.render(ctSym()).content();

		assertThat(first).as("the table is byte identical").isEqualTo(second);
	}

	@Test
	@DisplayName("writes a table the runtime can read")
	void writesReadableTable() throws Exception {
		Path output = state.resolve("jdk-api");

		int code = JdkApiTableGenerator.generate(ctSym(), output);

		assertThat(code).as("the table was written").isEqualTo(JdkApiTableGenerator.WRITTEN);
		try (var files = Files.list(output)) {
			List<Path> written = files.toList();
			assertThat(written).as("one table file").hasSize(1);
			JdkApiTable table = JdkApiTable.parse(Files.readString(written.get(0)));
			assertThat(table.isEmpty()).as("the runtime reads it").isFalse();
			assertThat(table.answer("java/util/List.class", newest(table)))
					.as("the table answers").isEqualTo(JdkApiTable.Answer.DOCUMENTED);
		}
	}

	@Test
	@DisplayName("skips the generation when the archive is missing")
	void skipsWithoutArchive() throws Exception {
		Path output = state.resolve("jdk-api");

		int code = JdkApiTableGenerator.generate(state.resolve("nowhere").resolve("ct.sym"), output);

		assertThat(code).as("the generation was skipped").isEqualTo(JdkApiTableGenerator.SKIPPED);
		assertThat(output).as("nothing was written").doesNotExist();
	}

	@Test
	@DisplayName("answers like the archive it was built from")
	void answersLikeTheArchive() throws Exception {
		Path archive = ctSym();
		JdkApiTable table = JdkApiTable.bundled();
		List<String> samples = List.of(
				"java/util/List.class",
				"java/util/Map.class",
				"java/util/Map$Entry.class",
				"java/util/stream/Gatherers.class",
				"java/lang/String.class",
				"com/example/Nope.class");
		List<Integer> releases = new ArrayList<>();
		for (int release = 8; release <= newest(table); release++) {
			releases.add(release);
		}
		for (int release : releases) {
			Set<String> expected = archiveClasses(archive, release);
			for (String sample : samples) {
				assertThat(table.answer(sample, release))
						.as(sample + " in release " + release)
						.isEqualTo(expected.contains(sample)
								? JdkApiTable.Answer.DOCUMENTED
								: JdkApiTable.Answer.NOT_DOCUMENTED);
			}
		}
	}

	@Test
	@DisplayName("answers a class the table holds for another release")
	void answersClassOfAnotherRelease() {
		JdkApiTable table = JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8 21",
				"# classes 3",
				"java/util/List.class 8",
				"java/util/List.class 21",
				"java/util/Map.class 21")) + "\n");

		assertThat(table.answer("java/util/List.class", 8)).as("both releases")
				.isEqualTo(JdkApiTable.Answer.DOCUMENTED);
		assertThat(table.answer("java/util/List.class", 21)).as("both releases")
				.isEqualTo(JdkApiTable.Answer.DOCUMENTED);
		assertThat(table.answer("java/util/Map.class", 8)).as("documented in 21 only")
				.isEqualTo(JdkApiTable.Answer.NOT_DOCUMENTED);
		assertThat(table.hasPackage("java/util", 8)).as("package in eight").isTrue();
		assertThat(table.hasPackage("java/util", 21)).as("package in 21").isTrue();
		assertThat(table.hasPackage("java/nio", 8)).as("package from nowhere").isFalse();
	}

	@Test
	@DisplayName("ignores a table that is not there")
	void ignoresNoTable() {
		assertThat(JdkApiTable.parse(null).isEmpty()).as("nothing to read").isTrue();
	}

	@Test
	@DisplayName("ignores a class line without a release")
	void ignoresClassLineWithoutRelease() {
		JdkApiTable table = JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8",
				"# classes 1",
				"java/util/List.class")) + "\n");

		assertThat(table.isEmpty()).as("the broken table is ignored").isTrue();
	}

	@Test
	@DisplayName("ignores a table without the release header")
	void ignoresTableWithoutReleases() {
		JdkApiTable table = JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# classes 1",
				"java/util/List.class 8")) + "\n");

		assertThat(table.isEmpty()).as("the headerless table is ignored").isTrue();
	}

	@Test
	@DisplayName("ignores a table without the class count")
	void ignoresTableWithoutCount() {
		JdkApiTable table = JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8",
				"java/util/List.class 8")) + "\n");

		assertThat(table.isEmpty()).as("the countless table is ignored").isTrue();
	}

	@Test
	@DisplayName("ignores a release out of range")
	void ignoresReleaseOutOfRange() {
		JdkApiTable table = JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8 900",
				"# classes 1",
				"java/util/List.class 8")) + "\n");

		assertThat(table.isEmpty()).as("the table is ignored").isTrue();
	}

	@Test
	@DisplayName("ignores a class with a release out of range")
	void ignoresClassWithReleaseOutOfRange() {
		JdkApiTable table = JdkApiTable.parse(String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8",
				"# classes 1",
				"java/util/List.class 8-900")) + "\n");

		assertThat(table.isEmpty()).as("the table is ignored").isTrue();
	}

	@Test
	@DisplayName("keeps the table that covers the most releases")
	void keepsTheBiggestTable() throws Exception {
		Path root = state.resolve("tables");
		Path tableDir = root.resolve("jdk-api");
		Files.createDirectories(tableDir);
		Files.writeString(tableDir.resolve("jdk-api-21.txt"), String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8 21",
				"# classes 1",
				"java/util/List.class 8-21")) + "\n");
		Files.writeString(tableDir.resolve("jdk-api-8.txt"), String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8",
				"# classes 1",
				"java/util/Map.class 8")) + "\n");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { root.toUri().toURL() }, null)) {
			JdkApiTable table = JdkApiTable.load(loader);

			assertThat(table.releases()).as("the table with more releases").containsExactly(8, 21);		}
	}

	@Test
	@DisplayName("ignores files that are not tables")
	void ignoresFilesThatAreNotTables() throws Exception {
		Path root = state.resolve("files");
		Path tableDir = root.resolve("jdk-api");
		Files.createDirectories(tableDir);
		Files.writeString(tableDir.resolve("notes.txt"), "not a table");
		Files.writeString(tableDir.resolve("jdk-api-.txt"), "not a table");
		Files.writeString(tableDir.resolve("jdk-api-21.txt"), String.join("\n", List.of(
				"# importtruth-jdk-api 1",
				"# releases 8",
				"# classes 1",
				"java/util/List.class 8")) + "\n");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { root.toUri().toURL() }, null)) {
			JdkApiTable table = JdkApiTable.load(loader);

			assertThat(table.releases()).as("only the table was read").containsExactly(8);
		}
	}

	@Test
	@DisplayName("skips the generation without an output directory")
	void skipsWithoutOutputDirectory() {
		assertThat(JdkApiTableGenerator.run(new String[0])).as("skipped")
				.isEqualTo(JdkApiTableGenerator.SKIPPED);
		assertThat(JdkApiTableGenerator.run(new String[] { "  " })).as("skipped")
				.isEqualTo(JdkApiTableGenerator.SKIPPED);
	}

	@Test
	@DisplayName("skips the generation when the output path is broken")
	void skipsWithBrokenOutputPath() {
		assertThat(JdkApiTableGenerator.run(new String[] { "no\u0000path" })).as("skipped")
				.isEqualTo(JdkApiTableGenerator.SKIPPED);
	}

	@Test
	@DisplayName("writes the table from the command line")
	void writesTableFromTheCommandLine() throws Exception {
		Path output = state.resolve("cli").resolve("jdk-api");

		int code = JdkApiTableGenerator.run(new String[] { output.toString() });

		assertThat(code).as("the table was written").isEqualTo(JdkApiTableGenerator.WRITTEN);
		assertThat(output).as("the table directory").isDirectory();
	}

	@Test
	@DisplayName("finds no archive in a home without one")
	void findsNoArchive() {
		assertThat(JdkApiTableGenerator.ctSym("")).as("a blank home").isNull();
		assertThat(JdkApiTableGenerator.ctSym(null)).as("no home").isNull();
		assertThat(JdkApiTableGenerator.ctSym("somewhere")).as("a home").isNotNull();
	}

	@Test
	@DisplayName("reads an archive with entries that are not classes")
	void readsArchiveWithOddEntries() throws Exception {
		Path archive = state.resolve("odd.ct.sym");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			write(zip, "A");
			write(zip, "A/java.base/java/util/List.sig");
			write(zip, "C/java.base/java/util/List.sig");
			write(zip, "@/java.base/java/util/Preview.sig");
			write(zip, "A/java.base/java/util/List.class");
			write(zip, "A/java.base/java/util/List.class.sig");
		}
		Map<String, TreeSet<Integer>> classes = JdkApiTableGenerator.readArchive(archive);

		assertThat(classes).as("the classes of the archive")
				.containsOnlyKeys("java/util/List.class", "java/util/List.class.class");
		assertThat(classes.get("java/util/List.class")).as("two releases").containsExactly(10, 12);
	}

	@Test
	@DisplayName("renders a table of an archive with odd entries")
	void rendersTableWithOddEntries() throws Exception {
		Path archive = state.resolve("odd.ct.sym");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			write(zip, "A/java.base/java/util/List.sig");
			write(zip, "C/java.base/java/util/List.sig");
			write(zip, "A/java.base/java/util/ListLong.sig");
		}
		JdkApiTableGenerator.Table table = JdkApiTableGenerator.render(archive);
		String content = new String(table.content(), StandardCharsets.UTF_8);

		assertThat(table.newestRelease()).as("the newest release").isEqualTo(12);
		assertThat(content).as("the release list of the class").contains("java/util/List.class 10,12");
		assertThat(content).as("the classes are sorted by their bytes")
				.containsSubsequence("java/util/List.class 10,12", "java/util/ListLong.class 10");
	}

	@Test
	@DisplayName("writes a table and removes the table of an earlier build")
	void removesTheEarlierTable() throws Exception {
		Path output = state.resolve("jdk-api");
		Files.createDirectories(output);
		Files.writeString(output.resolve("jdk-api-21.txt"), "an earlier table");
		Files.writeString(output.resolve("notes.txt"), "not a table");
		String newest = "jdk-api-" + JdkApiTableGenerator.render(ctSym()).newestRelease() + ".txt";

		int code = JdkApiTableGenerator.generate(ctSym(), output);

		assertThat(code).as("the table was written").isEqualTo(JdkApiTableGenerator.WRITTEN);
		assertThat(output.resolve("notes.txt")).as("a file that is not a table stays").exists();
		try (var files = Files.list(output)) {
			assertThat(files.map(path -> path.getFileName().toString()).toList())
					.as("one table file")
					.containsExactlyInAnyOrder("notes.txt", newest);
		}
	}

	/**
	 * Writes an entry into a zip file.
	 *
	 * @param zip  the zip file, never null
	 * @param name the entry name, never null
	 * @throws IOException when the entry cannot be written
	 */
	private static void write(ZipOutputStream zip, String name) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.closeEntry();
	}

	/**
	 * Returns the archive of the build JDK.
	 *
	 * @return the archive, never null
	 */
	private static Path ctSym() {
		return JdkApiTableGenerator.ctSym();
	}

	/**
	 * Renders the table of the build JDK.
	 *
	 * @return the table file content, never null
	 */
	private static String rendered() {
		try {
			return new String(JdkApiTableGenerator.render(ctSym()).content(), StandardCharsets.UTF_8);
		} catch (IOException unreadable) {
			throw new IllegalStateException("the build JDK has no archive", unreadable);
		}
	}

	/**
	 * Returns the newest release a table covers.
	 *
	 * @param table the table to read, never null
	 * @return the newest covered release
	 */
	private static int newest(JdkApiTable table) {
		return table.releases().stream().mapToInt(Integer::intValue).max().orElseThrow();
	}

	/**
	 * Reads the documented API of a release from the archive. The rule
	 * is the one the archive itself uses: a release is the union of the
	 * segments whose name carries its marker.
	 *
	 * @param archive the archive to read, never null
	 * @param release the release to read
	 * @return the documented class names, never null
	 * @throws IOException when the archive cannot be read
	 */
	private static Set<String> archiveClasses(Path archive, int release) throws IOException {
		char marker = release == 8 ? '8' : release == 9 ? '9' : (char) ('A' + release - 10);
		Set<String> classes = new HashSet<>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				String entry = entries.nextElement().getName();
				int slash = entry.indexOf('/');
				if (slash < 0 || entry.indexOf(marker, 0, slash) < 0) {
					continue;
				}
				String rest = entry.substring(slash + 1);
				if (!rest.endsWith(".sig")) {
					continue;
				}
				String path = rest.substring(rest.indexOf('/') + 1);
				classes.add(path.substring(0, path.length() - ".sig".length()) + ".class");
			}
		}
		return classes;
	}
}

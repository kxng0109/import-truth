package io.github.kxng0109.importtruth.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Writes the table of the documented JDK API from the archive of the
 * build JDK.
 *
 * <p>The archive holds the documented API of every release the build
 * JDK knows. Its entries live in directories whose names carry the
 * releases they belong to, so the class set of a release is the union
 * of the directories that carry its marker. The generator turns that
 * into one line per class with the releases that document it, which is
 * small enough to ship inside the tool and needs no archive on disk.
 *
 * <p>The run never fails the build. A build JDK without an archive, or
 * a failure while reading it, skips the table: the tool then answers
 * from the archive on disk, or reports that the JDK surface could not
 * be consulted.
 */
public final class JdkApiTableGenerator {

	/** The exit code of a run that wrote the table. */
	public static final int WRITTEN = 0;

	/** The exit code of a run that skipped the table. */
	public static final int SKIPPED = 2;

	/** The property that names the JDK home the archive is read from. */
	static final String JDK_HOME_PROPERTY = "importtruth.jdk.home";

	/** The name of the archive inside a JDK home. */
	private static final String ARCHIVE_NAME = "ct.sym";

	/**
	 * Writes the table and reports the outcome through the exit code.
	 *
	 * @param args the output directory, and nothing else
	 */
	public static void main(String[] args) {
		System.exit(run(args));
	}

	/**
	 * Writes the table of the build JDK.
	 *
	 * @param args the output directory, and nothing else
	 * @return the exit code, never a failure
	 */
	static int run(String[] args) {
		if (args.length < 1 || args[0].isBlank()) {
			System.out.println("jdk-api table skipped: no output directory given");
			return SKIPPED;
		}
		try {
			return generate(ctSym(), Path.of(args[0]));
		} catch (Throwable failure) {
			System.out.println("jdk-api table skipped: " + failure);
			return SKIPPED;
		}
	}

	/**
	 * Writes the table of an archive.
	 *
	 * @param ctSym  the archive to read, or null when there is none
	 * @param output the directory the table is written to, never null
	 * @return the exit code, never a failure
	 * @throws IOException when the table cannot be written
	 */
	static int generate(Path ctSym, Path output) throws IOException {
		if (ctSym == null || !Files.isRegularFile(ctSym)) {
			System.out.println("jdk-api table skipped: no archive at " + ctSym);
			return SKIPPED;
		}
		Table table = render(ctSym);
		Files.createDirectories(output);
		removeOtherTables(output, table.newestRelease());
		Path file = output.resolve(
				JdkApiTable.FILE_PREFIX + table.newestRelease() + JdkApiTable.FILE_SUFFIX);
		Files.write(file, table.content());
		System.out.println("jdk-api table written: " + file + " (" + table.content().length + " bytes)");
		return WRITTEN;
	}

	/**
	 * Reads an archive and renders the table.
	 *
	 * @param ctSym the archive to read, never null
	 * @return the rendered table, never null
	 * @throws IOException when the archive cannot be read
	 */
	static Table render(Path ctSym) throws IOException {
		Map<String, TreeSet<Integer>> classes = readArchive(ctSym);
		Set<Integer> covered = new TreeSet<>();
		for (Set<Integer> releases : classes.values()) {
			covered.addAll(releases);
		}
		if (covered.isEmpty()) {
			return new Table(new byte[0], 0);
		}
		int newest = covered.stream().mapToInt(Integer::intValue).max().orElseThrow();
		return new Table(render(classes, covered, ctSym), newest);
	}

	/**
	 * Reads the documented classes of an archive with the releases that
	 * document them.
	 *
	 * @param ctSym the archive to read, never null
	 * @return the classes and their releases, never null
	 * @throws IOException when the archive cannot be read
	 */
	static Map<String, TreeSet<Integer>> readArchive(Path ctSym) throws IOException {
		Map<String, TreeSet<Integer>> classes = new TreeMap<>();
		try (ZipFile zip = new ZipFile(ctSym.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				String entry = entries.nextElement().getName();
				int slash = entry.indexOf('/');
				if (slash < 0) {
					continue;
				}
				String rest = entry.substring(slash + 1);
				if (!rest.endsWith(".sig")) {
					continue;
				}
				String path = rest.substring(rest.indexOf('/') + 1);
				String binary = path.substring(0, path.length() - ".sig".length()) + ".class";
				Set<Integer> releases = releases(entry.substring(0, slash));
				if (releases.isEmpty()) {
					// A segment of no release, such as a preview, documents
					// no release this table can answer about.
					continue;
				}
				classes.computeIfAbsent(binary, key -> new TreeSet<>()).addAll(releases);
			}
		}
		return classes;
	}

	/**
	 * Reads the releases a segment of the archive belongs to. A segment
	 * name is a set of release markers: {@code 8} and {@code 9} for the
	 * first two releases, and a letter per release from {@code A} for
	 * release ten onwards.
	 *
	 * @param segment the segment name, never null
	 * @return the releases, never null
	 */
	private static Set<Integer> releases(String segment) {
		Set<Integer> releases = new TreeSet<>();
		for (int i = 0; i < segment.length(); i++) {
			char marker = segment.charAt(i);
			if (marker == '8') {
				releases.add(8);
			} else if (marker == '9') {
				releases.add(9);
			} else if (marker >= 'A' && marker <= 'Z') {
				releases.add(marker - 'A' + 10);
			}
		}
		return releases;
	}

	/**
	 * Renders the table file.
	 *
	 * @param classes the classes and their releases, never null
	 * @param covered the covered releases, never null
	 * @param ctSym   the archive the table was read from, never null
	 * @return the file content, never null
	 * @throws IOException when the archive cannot be measured
	 */
	private static byte[] render(Map<String, TreeSet<Integer>> classes, Set<Integer> covered, Path ctSym)
			throws IOException {
		StringBuilder out = new StringBuilder();
		out.append(JdkApiTable.MAGIC).append('\n');
		out.append("# jdk ").append(System.getProperty("java.version", "unknown"))
				.append(' ').append(System.getProperty("java.vendor", "unknown")).append('\n');
		out.append("# ct.sym ").append(Files.size(ctSym)).append(" bytes\n");
		out.append(JdkApiTable.RELEASES_HEADER)
				.append(covered.stream().map(String::valueOf).collect(Collectors.joining(" ")))
				.append('\n');
		out.append(JdkApiTable.CLASSES_HEADER).append(classes.size()).append('\n');
		out.append("# columns <class> <releases>\n");
		List<String> names = new ArrayList<>(classes.keySet());
		names.sort(JdkApiTableGenerator::compareBytes);
		for (String name : names) {
			out.append(name).append(' ').append(spec(classes.get(name))).append('\n');
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Renders the releases of a class as a list of numbers and ranges.
	 *
	 * @param releases the releases, never null
	 * @return the list, never null
	 */
	private static String spec(Set<Integer> releases) {
		List<Integer> sorted = new ArrayList<>(releases);
		StringBuilder out = new StringBuilder();
		int at = 0;
		while (at < sorted.size()) {
			int from = sorted.get(at);
			int to = from;
			while (at + 1 < sorted.size() && sorted.get(at + 1) == to + 1) {
				to = sorted.get(++at);
			}
			if (out.length() > 0) {
				out.append(',');
			}
			out.append(from);
			if (to != from) {
				out.append('-').append(to);
			}
			at++;
		}
		return out.toString();
	}

	/**
	 * Compares two class names by their bytes, so the table is sorted
	 * the same way on every machine.
	 *
	 * @param left  the first name, never null
	 * @param right the second name, never null
	 * @return a negative number, zero, or a positive number
	 */
	private static int compareBytes(String left, String right) {
		byte[] first = left.getBytes(StandardCharsets.UTF_8);
		byte[] second = right.getBytes(StandardCharsets.UTF_8);
		int shared = Math.min(first.length, second.length);
		for (int i = 0; i < shared; i++) {
			int difference = (first[i] & 0xff) - (second[i] & 0xff);
			if (difference != 0) {
				return difference;
			}
		}
		return first.length - second.length;
	}

	/**
	 * Removes the tables of earlier builds, so the directory holds the
	 * table of this build only.
	 *
	 * @param output the directory the table is written to, never null
	 * @param newest the release the new table covers
	 * @throws IOException when a table cannot be removed
	 */
	private static void removeOtherTables(Path output, int newest) throws IOException {
		String keep = JdkApiTable.FILE_PREFIX + newest + JdkApiTable.FILE_SUFFIX;
		try (Stream<Path> files = Files.list(output)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (name.startsWith(JdkApiTable.FILE_PREFIX)
						&& name.endsWith(JdkApiTable.FILE_SUFFIX)
						&& !name.equals(keep)) {
					Files.deleteIfExists(file);
				}
			}
		}
	}

	/**
	 * Returns the archive of the build JDK.
	 *
	 * @return the archive, or null when the build JDK has none
	 */
	static Path ctSym() {
		return ctSym(System.getProperty(JDK_HOME_PROPERTY, System.getProperty("java.home")));
	}

	/**
	 * Returns the archive of a JDK home.
	 *
	 * @param home the JDK home, possibly null
	 * @return the archive, or null when the home has none
	 */
	static Path ctSym(String home) {
		if (home == null || home.isBlank()) {
			return null;
		}
		return Path.of(home, "lib", ARCHIVE_NAME);
	}

	/**
	 * A rendered table and the release it was built for.
	 *
	 * @param content       the table file content, never null
	 * @param newestRelease the newest release the table covers
	 */
	record Table(byte[] content, int newestRelease) {
	}
}

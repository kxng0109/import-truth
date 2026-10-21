package io.github.kxng0109.importtruth.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The documented API of the JDK releases, bundled as a generated table.
 *
 * <p>The table answers "is this class documented in release N" without
 * an archive on disk, so a build that carries no JDK, such as a native
 * image, can still answer. It is release aware: a release the table
 * does not cover is never answered, because an answer about a release
 * the table knows nothing of would be a guess.
 *
 * <p>The table file lists one class per line with the releases that
 * document it. The generator that writes it reads the archive of the
 * build JDK, so the table and the archive always agree.
 */
public final class JdkApiTable {

	/** The first line of a table file, with its format version. */
	static final String MAGIC = "# importtruth-jdk-api 1";

	/** The classpath directory the table files live in. */
	static final String RESOURCE_DIR = "jdk-api/";

	/** The prefix of a table file name. */
	static final String FILE_PREFIX = "jdk-api-";

	/** The suffix of a table file name. */
	static final String FILE_SUFFIX = ".txt";

	/** The lowest release the table can hold. */
	private static final int FIRST_RELEASE = 8;

	/** The highest release the table can hold, one bit per release. */
	private static final int LAST_RELEASE = FIRST_RELEASE + Long.SIZE - 1;

	/** The header that names the releases a table covers. */
	static final String RELEASES_HEADER = "# releases ";

	/** The header that names the number of classes a table holds. */
	static final String CLASSES_HEADER = "# classes ";

	/** A table that holds nothing. */
	private static final JdkApiTable EMPTY = new JdkApiTable(Map.of(), Set.of());

	/** The documented classes, each with the releases that document it. */
	private final Map<String, Long> classes;

	/** The releases the table covers. */
	private final Set<Integer> releases;

	/**
	 * Creates a table.
	 *
	 * @param classes  the documented classes and their releases, never null
	 * @param releases the covered releases, never null
	 */
	private JdkApiTable(Map<String, Long> classes, Set<Integer> releases) {
		this.classes = classes;
		this.releases = Collections.unmodifiableSet(new TreeSet<>(releases));
	}

	/**
	 * The answer a table gives about a class.
	 */
	public enum Answer {

		/** The class is documented in the release. */
		DOCUMENTED,

		/** The class is not documented in the release. */
		NOT_DOCUMENTED,

		/** The table does not cover the release, so it cannot tell. */
		UNVERIFIED
	}

	/**
	 * Returns the table that ships with the tool. It is read once and
	 * kept, so a session pays for the table at most once.
	 *
	 * @return the bundled table, never null
	 */
	public static JdkApiTable bundled() {
		return Holder.TABLE;
	}

	/**
	 * Returns a table that holds nothing. It answers no release.
	 *
	 * @return the empty table, never null
	 */
	public static JdkApiTable empty() {
		return EMPTY;
	}

	/**
	 * Reports whether the table covers a release.
	 *
	 * @param release target major release
	 * @return true when the table can answer about the release
	 */
	public boolean covers(int release) {
		return releases.contains(release);
	}

	/**
	 * Returns the releases the table covers.
	 *
	 * @return the covered releases, never null
	 */
	public Set<Integer> releases() {
		return releases;
	}

	/**
	 * Reports whether the table holds nothing.
	 *
	 * @return true when the table answers no release
	 */
	public boolean isEmpty() {
		return releases.isEmpty();
	}

	/**
	 * Answers whether a class is documented in a release.
	 *
	 * @param binaryName the class file name, such as {@code java/util/List.class}
	 * @param release    target major release
	 * @return the answer, never null
	 */
	public Answer answer(String binaryName, int release) {
		if (!covers(release)) {
			return Answer.UNVERIFIED;
		}
		Long mask = classes.get(binaryName);
		if (mask == null) {
			return Answer.NOT_DOCUMENTED;
		}
		return (mask & bit(release)) != 0 ? Answer.DOCUMENTED : Answer.NOT_DOCUMENTED;
	}

	/**
	 * Reports whether a class is documented in a release.
	 *
	 * @param binaryName the class file name, such as {@code java/util/List.class}
	 * @param release    target major release
	 * @return true when the class is documented in the release
	 */
	public boolean documents(String binaryName, int release) {
		return answer(binaryName, release) == Answer.DOCUMENTED;
	}

	/**
	 * Answers whether a package is documented in a release. A package
	 * is documented when it holds a documented class. The classes are
	 * looked up by their path, so a package of a class of a package of
	 * a class counts as well.
	 *
	 * @param packagePath the package path, such as {@code java/util}
	 * @param release     target major release
	 * @return the answer, never null
	 */
	public Answer packageAnswer(String packagePath, int release) {
		if (!covers(release)) {
			return Answer.UNVERIFIED;
		}
		String prefix = packagePath + "/";
		long bit = bit(release);
		for (Map.Entry<String, Long> entry : classes.entrySet()) {
			if (entry.getKey().startsWith(prefix) && (entry.getValue() & bit) != 0) {
				return Answer.DOCUMENTED;
			}
		}
		return Answer.NOT_DOCUMENTED;
	}

	/**
	 * Reports whether a package is documented in a release.
	 *
	 * @param packagePath the package path, such as {@code java/util}
	 * @param release     target major release
	 * @return true when the package is documented in the release
	 */
	public boolean hasPackage(String packagePath, int release) {
		return packageAnswer(packagePath, release) == Answer.DOCUMENTED;
	}

	/**
	 * Reads the table that ships with the tool.
	 *
	 * @return the table, or an empty table when there is none
	 */
	static JdkApiTable load() {
		return load(JdkApiTable.class.getClassLoader());
	}

	/**
	 * Reads the tables of a classpath and keeps the one that covers the
	 * most releases. A classpath without a table yields an empty table.
	 *
	 * @param loader the class loader to read from, never null
	 * @return the table, never null
	 */
	static JdkApiTable load(ClassLoader loader) {
		JdkApiTable best = EMPTY;
		for (String name : tableNames(loader)) {
			JdkApiTable table = read(loader, name);
			if (table != null && table.releases().size() > best.releases().size()) {
				best = table;
			}
		}
		return best;
	}

	/**
	 * Parses a table file. A file that is not a table, or that is cut
	 * short, is ignored: a half read table would answer wrongly.
	 *
	 * @param content the file content, possibly null
	 * @return the table, or an empty table when the content is not one
	 */
	static JdkApiTable parse(String content) {
		if (content == null) {
			return EMPTY;
		}
		String[] lines = content.split("\n", -1);
		if (lines.length == 0 || !MAGIC.equals(lines[0].trim())) {
			return EMPTY;
		}
		Set<Integer> releases = null;
		int expected = -1;
		int parsed = 0;
		Map<String, Long> classes = new HashMap<>();
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.startsWith(RELEASES_HEADER)) {
				releases = parseReleases(line.substring(RELEASES_HEADER.length()));
				continue;
			}
			if (line.startsWith(CLASSES_HEADER)) {
				expected = parseCount(line.substring(CLASSES_HEADER.length()));
				continue;
			}
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int space = line.indexOf(' ');
			if (space < 0) {
				return EMPTY;
			}
			String name = line.substring(0, space);
			Long mask = parseMask(line.substring(space + 1));
			if (mask == null) {
				return EMPTY;
			}
			parsed++;
			classes.merge(name, mask, (first, second) -> first | second);
		}
		if (releases == null || releases.isEmpty() || expected < 0 || expected != parsed) {
			return EMPTY;
		}
		return new JdkApiTable(Collections.unmodifiableMap(classes), Set.copyOf(releases));
	}

	/**
	 * Lists the table files of a classpath.
	 *
	 * @param loader the class loader to read from, never null
	 * @return the resource names, never null
	 */
	private static List<String> tableNames(ClassLoader loader) {
		List<String> names = new ArrayList<>();
		try {
			Enumeration<URL> directories = loader.getResources(RESOURCE_DIR);
			while (directories.hasMoreElements()) {
				collect(directories.nextElement(), names);
			}
		} catch (IOException unreadable) {
			// A classpath that cannot be listed holds no table.
		}
		Collections.sort(names);
		return names;
	}

	/**
	 * Adds the table files of one classpath entry.
	 *
	 * @param url   the directory the entry exposes, never null
	 * @param names the names to add to, never null
	 */
	private static void collect(URL url, List<String> names) {
		try {
			if ("jar".equals(url.getProtocol())) {
				collectFromJar(url, names);
			} else {
				collectFromDirectory(url, names);
			}
		} catch (Exception unreadable) {
			// A classpath entry that cannot be listed holds no table.
		}
	}

	/**
	 * Adds the table files of a directory of the classpath.
	 *
	 * @param url   the directory, never null
	 * @param names the names to add to, never null
	 * @throws Exception when the directory cannot be listed
	 */
	private static void collectFromDirectory(URL url, List<String> names) throws Exception {
		try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
			files.map(path -> path.getFileName().toString())
					.filter(JdkApiTable::isTableName)
					.forEach(name -> names.add(RESOURCE_DIR + name));
		}
	}

	/**
	 * Adds the table files of a jar of the classpath.
	 *
	 * @param url   the jar, never null
	 * @param names the names to add to, never null
	 * @throws Exception when the jar cannot be read
	 */
	private static void collectFromJar(URL url, List<String> names) throws Exception {
		JarURLConnection connection = (JarURLConnection) url.openConnection();
		try (JarFile jar = connection.getJarFile()) {
			jar.stream()
					.map(JarEntry::getName)
					.filter(name -> name.startsWith(RESOURCE_DIR)
							&& isTableName(name.substring(RESOURCE_DIR.length())))
					.forEach(names::add);
		}
	}

	/**
	 * Reads one table file.
	 *
	 * @param loader the class loader to read from, never null
	 * @param name   the resource name, never null
	 * @return the table, or null when the file is not a table
	 */
	private static JdkApiTable read(ClassLoader loader, String name) {
		try (InputStream in = loader.getResourceAsStream(name)) {
			if (in == null) {
				return null;
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	/**
	 * Reports whether a file name names a table.
	 *
	 * @param name the file name, never null
	 * @return true when the name names a table
	 */
	private static boolean isTableName(String name) {
		return name.length() > FILE_PREFIX.length() + FILE_SUFFIX.length()
				&& name.startsWith(FILE_PREFIX)
				&& name.endsWith(FILE_SUFFIX);
	}

	/**
	 * Reads the release list of a header.
	 *
	 * @param text the header value, never null
	 * @return the releases, or null when the header is broken
	 */
	private static Set<Integer> parseReleases(String text) {
		Set<Integer> releases = new TreeSet<>();
		for (String part : text.trim().split("\\s+")) {
			if (part.isEmpty()) {
				continue;
			}
			int release = parseNumber(part);
			if (release < FIRST_RELEASE || release > LAST_RELEASE) {
				return null;
			}
			releases.add(release);
		}
		return releases;
	}

	/**
	 * Reads the class count of a header.
	 *
	 * @param text the header value, never null
	 * @return the count, or a negative number when the header is broken
	 */
	private static int parseCount(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException invalid) {
			return -1;
		}
	}

	/**
	 * Reads a release list into a bit mask. A list holds single
	 * releases and inclusive ranges, such as {@code 8-26}.
	 *
	 * @param spec the release list, never null
	 * @return the bit mask, or null when the list is broken
	 */
	private static Long parseMask(String spec) {
		long mask = 0L;
		for (String part : spec.split(",")) {
			int dash = part.indexOf('-');
			int from;
			int to;
			if (dash < 0) {
				from = parseNumber(part);
				to = from;
			} else {
				from = parseNumber(part.substring(0, dash));
				to = parseNumber(part.substring(dash + 1));
			}
			if (from < FIRST_RELEASE || to > LAST_RELEASE || from > to) {
				return null;
			}
			for (int release = from; release <= to; release++) {
				mask |= bit(release);
			}
		}
		return mask;
	}

	/**
	 * Reads a release number.
	 *
	 * @param text the number, never null
	 * @return the release, or a negative number when the text is not one
	 */
	private static int parseNumber(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException invalid) {
			return -1;
		}
	}

	/**
	 * Returns the bit a release owns in a mask.
	 *
	 * @param release the release
	 * @return the bit
	 */
	private static long bit(int release) {
		return 1L << (release - FIRST_RELEASE);
	}

	/**
	 * Holds the table of the tool, so it is read at most once.
	 */
	private static final class Holder {

		/**
		 * The table that ships with the tool.
		 */
		private static final JdkApiTable TABLE = load();
	}
}

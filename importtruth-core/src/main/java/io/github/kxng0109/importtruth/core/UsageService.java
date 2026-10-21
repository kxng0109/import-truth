package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.model.ClassReferenceScanner;
import io.github.kxng0109.importtruth.model.DependencyUsage;
import io.github.kxng0109.importtruth.model.UsageStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Answers which dependency files the project's own compiled classes
 * reference.
 * <p>
 * The first half of the question is a fact about the bytecode: a class
 * file records only symbolic references, so the set of classes a
 * compilation names is exactly what the code reaches. The second half
 * is never a fact and is never answered as one. A scan of class files
 * cannot see reflection, a service loader, a class loaded by a
 * computed name, a method handle lookup, or a resource, so a
 * dependency that no reference reaches is a candidate for removal and
 * never a verdict. Every answer that holds such a candidate carries
 * the standing caveat, and the caveat cannot be turned off.
 * <p>
 * The project's own classes are the root of the answer. The classes of
 * a dependency the project uses directly are scanned as well, so a
 * dependency that only a used dependency needs is reported as used
 * indirectly instead of as a candidate for removal. The closure is
 * computed to a fixed point, so a chain of dependencies is followed to
 * its end.
 * <p>
 * An incomplete scan never produces a candidate for removal. When the
 * project's own classes cannot be found or read, or when no scanner
 * is registered, every dependency is unverified. An archive that
 * holds an entry that is not a class this tool may extract is never
 * unpacked, and the dependency it belongs to is unverified as well.
 */
public final class UsageService {

	/**
	 * The standing caveat. It rides with every answer that holds a
	 * dependency no reference reaches, and it is never optional.
	 */
	public static final String CAVEAT = "caveat: a class-file scan cannot see reflection, ServiceLoader, "
			+ "Class.forName with a computed name, MethodHandles, or resources; "
			+ "UNREFERENCED is a candidate, not a verdict";

	/** Prefix of a versioned entry in a multi-release archive. */
	private static final String VERSIONS_PREFIX = "META-INF/versions/";

	/** Lowest release a multi-release archive may hold. */
	private static final int FIRST_RELEASE = 9;

	/** Suffix every Java class file carries. */
	private static final String CLASS_SUFFIX = ".class";

	/** The directory of a project that holds scratch files. */
	private static final String SCRATCH_DIRECTORY = ".tmp";

	/** The directory of the tool that holds its caches. */
	private static final String STATE_DIRECTORY = ".importtruth";

	/** The directory of the tool that holds the scratch of a scan. */
	private static final String USAGE_DIRECTORY = "usage";

	/** The prefix of the directory a dependency is unpacked into. */
	private static final String SCRATCH_PREFIX = "importtruth-usage-";

	/** The output roots a module keeps its compiled classes in. */
	private static final List<String> OUTPUT_ROOTS = List.of("target/classes", "target/test-classes");

	/** The reason a run without the project's own classes carries. */
	private static final String NO_CLASSES =
			"no compiled classes found under target/classes or target/test-classes in any module of the project";

	/** The reason a run without a scanner carries. */
	private static final String NO_SCANNER = "no class reference scanner is registered on the classpath";

	/** The reason a dependency that cannot be read carries. */
	private static final String UNREADABLE_JAR = "the JAR could not be read";

	/** The reason a dependency whose archive holds a foreign entry carries. */
	private static final String REFUSED_ENTRY =
			"the archive holds an entry that is not a class this tool may extract";

	private final DependencyResolver resolver;
	private final ClassInventory inventory;

	/**
	 * Loads the reference scanner. Not final: a test replaces it to
	 * reach the path where no scanner is registered.
	 */
	Supplier<Optional<ClassReferenceScanner>> scannerLoader =
			() -> ServiceLoader.load(ClassReferenceScanner.class).findFirst();

	/**
	 * Creates the service with the built-in archive reader.
	 *
	 * @param resolver dependency resolver, never null
	 * @throws NullPointerException when {@code resolver} is {@code null}
	 */
	public UsageService(DependencyResolver resolver) {
		this(resolver, UsageService::classesIn);
	}

	/**
	 * Creates the service.
	 *
	 * @param resolver dependency resolver, never null
	 * @param inventory reader of the classes an archive holds, never null
	 * @throws NullPointerException when an argument is {@code null}
	 */
	public UsageService(DependencyResolver resolver, ClassInventory inventory) {
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.inventory = Objects.requireNonNull(inventory, "inventory");
	}

	/**
	 * Analyses the dependencies of a project.
	 *
	 * @param projectDir project root, never null
	 * @return the answer, never null
	 * @throws IOException when the dependencies cannot be resolved
	 * @throws NullPointerException when {@code projectDir} is {@code null}
	 */
	public Report analyze(Path projectDir) throws IOException {
		return analyze(projectDir, null);
	}

	/**
	 * Analyses the dependencies of a project.
	 *
	 * <p>The compiled classes of the project are the input. Without
	 * them no reference can be read, and every dependency is
	 * unverified: a run that cannot see the code must never name a
	 * dependency as a candidate for removal.</p>
	 *
	 * @param projectDir project root, never null
	 * @param classesPath directory holding the project's own compiled
	 *                    classes, or null to use the build output
	 * @return the answer, never null
	 * @throws IOException when the dependencies cannot be resolved
	 * @throws NullPointerException when {@code projectDir} is {@code null}
	 */
	public Report analyze(Path projectDir, Path classesPath) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Path project = projectDir.toAbsolutePath().normalize();
		List<Path> jars = resolver.resolve(project, false);
		List<Path> roots;
		String missing;
		if (classesPath == null) {
			roots = buildRoots(project);
			missing = NO_CLASSES;
		} else {
			Path root = classesPath.toAbsolutePath().normalize();
			roots = Files.isDirectory(root) ? List.of(root) : List.of();
			missing = "no compiled classes found under " + portable(root);
		}
		if (roots.isEmpty()) {
			return unverified(project, jars, missing);
		}
		Optional<ClassReferenceScanner> found = scannerLoader.get();
		if (found.isEmpty()) {
			return unverified(project, jars, NO_SCANNER);
		}
		ClassReferenceScanner scanner = found.get();
		Set<String> referenced = new LinkedHashSet<>();
		int classCount = 0;
		try {
			for (Path root : roots) {
				referenced.addAll(scanner.referencesOf(root));
				classCount += countClassFiles(root);
			}
		} catch (IOException | RuntimeException unreadable) {
			return unverified(project, jars, "the compiled classes could not be read: " + describe(unreadable));
		}
		if (classCount == 0) {
			// An output directory without a class file holds no code to
			// read, so the run saw nothing. A run that saw nothing must
			// never name a candidate for removal.
			return unverified(project, jars, emptyClasses(roots));
		}
		return new Report(project, true, classCount, roots, usages(project, jars, referenced, scanner), "");
	}

	/**
	 * Reads the classes of every dependency and names the usage of
	 * each one.
	 *
	 * @param project    project root, never null
	 * @param jars       resolved dependency files, never null
	 * @param referenced classes the project's own code names, never null
	 * @param scanner    reference scanner, never null
	 * @return the usage of every dependency, in the order they were resolved
	 */
	private List<DependencyUsage> usages(Path project, List<Path> jars, Set<String> referenced,
			ClassReferenceScanner scanner) {
		Map<Path, Set<String>> inventories = new LinkedHashMap<>();
		Map<Path, String> failures = new LinkedHashMap<>();
		for (Path jar : jars) {
			try {
				inventories.put(jar, inventory.classesIn(jar));
			} catch (IOException | RuntimeException broken) {
				failures.put(jar, UNREADABLE_JAR);
			}
		}
		Map<String, Path> provider = new HashMap<>();
		Map<Path, Set<String>> direct = new LinkedHashMap<>();
		for (Path jar : jars) {
			Set<String> names = inventories.get(jar);
			if (names == null) {
				continue;
			}
			Set<String> hit = new LinkedHashSet<>();
			for (String name : names) {
				provider.putIfAbsent(name, jar);
				if (referenced.contains(name)) {
					hit.add(name);
				}
			}
			if (!hit.isEmpty()) {
				direct.put(jar, hit);
			}
		}
		Closure closure = close(project, provider, direct, referenced, scanner);
		List<DependencyUsage> usages = new ArrayList<>(jars.size());
		for (Path jar : jars) {
			String name = jar.getFileName().toString();
			Set<String> names = inventories.get(jar);
			String failure = failures.get(jar);
			if (failure != null) {
				usages.add(new DependencyUsage(name, jar, UsageStatus.UNVERIFIED,
						List.of(), List.of(), 0, failure));
				continue;
			}
			int total = names.size();
			String refusal = closure.refusals().get(jar);
			if (refusal != null) {
				// The archive holds an entry this tool may not extract,
				// so the dependency cannot be fully analysed. It is
				// never named a candidate for removal.
				usages.add(new DependencyUsage(name, jar, UsageStatus.UNVERIFIED,
						List.of(), List.of(), total, refusal));
				continue;
			}
			List<String> classes = names.stream()
					.filter(closure.referenced()::contains)
					.sorted()
					.toList();
			if (direct.containsKey(jar)) {
				usages.add(new DependencyUsage(name, jar, UsageStatus.USED_DIRECT,
						classes, List.of(), total, ""));
			} else if (closure.via().containsKey(jar)) {
				List<String> via = closure.via().get(jar).stream()
						.map(Path::getFileName)
						.map(Path::toString)
						.toList();
				usages.add(new DependencyUsage(name, jar, UsageStatus.USED_INDIRECT,
						classes, via, total, ""));
			} else if (!closure.failure().isEmpty()) {
				usages.add(new DependencyUsage(name, jar, UsageStatus.UNVERIFIED,
						List.of(), List.of(), total, closure.failure()));
			} else {
				usages.add(new DependencyUsage(name, jar, UsageStatus.UNREFERENCED,
						List.of(), List.of(), total, ""));
			}
		}
		return usages;
	}

	/**
	 * Follows the references of the used dependencies to a fixed point.
	 * <p>
	 * Only a dependency that is already used is scanned, so the cost
	 * follows the used subset. A dependency that a scanned dependency
	 * names is used indirectly, and it is scanned as well, so a chain
	 * of dependencies is followed to its end.
	 * <p>
	 * A dependency whose archive holds an entry that is not a class
	 * this tool may extract is named in the refusals. Its classes that
	 * could be read still count, because a scan that stops at one
	 * hostile entry would name a used dependency a candidate for
	 * removal.
	 *
	 * @param project    project root, never null
	 * @param provider   the dependency that holds each class, never null
	 * @param direct     the dependencies the project's own code names, never null
	 * @param referenced the classes the project's own code names, never null
	 * @param scanner    reference scanner, never null
	 * @return the closure of the used dependencies
	 */
	private Closure close(
			Path project,
			Map<String, Path> provider,
			Map<Path, Set<String>> direct,
			Set<String> referenced,
			ClassReferenceScanner scanner) {
		Map<Path, LinkedHashSet<Path>> via = new LinkedHashMap<>();
		Map<Path, String> refusals = new LinkedHashMap<>();
		Set<String> all = new LinkedHashSet<>(referenced);
		Deque<Path> work = new ArrayDeque<>(direct.keySet());
		Set<Path> queued = new HashSet<>(direct.keySet());
		while (!work.isEmpty()) {
			Path jar = work.poll();
			Scanned scanned;
			try {
				scanned = referencesIn(scanner, jar, project);
			} catch (IOException | RuntimeException broken) {
				// The closure is incomplete, so no dependency can be
				// named a candidate for removal.
				return new Closure(via, all, "the classes of " + jar.getFileName() + " could not be read",
						refusals);
			}
			if (scanned.refused()) {
				refusals.put(jar, REFUSED_ENTRY);
			}
			all.addAll(scanned.names());
			for (String name : scanned.names()) {
				Path owner = provider.get(name);
				if (owner == null || owner.equals(jar)) {
					continue;
				}
				via.computeIfAbsent(owner, key -> new LinkedHashSet<>()).add(jar);
				if (!direct.containsKey(owner) && queued.add(owner)) {
					work.add(owner);
				}
			}
		}
		return new Closure(via, all, "", refusals);
	}

	/**
	 * Lists the classes an archive holds.
	 * <p>
	 * The list comes from the entry list of the archive, so no entry is
	 * opened and no class is read. Every class the archive holds is
	 * listed, the package-private ones included.
	 * <p>
	 * A multi-release archive holds the same class under several names.
	 * Only the entry the running runtime would load counts, and the
	 * prefix is stripped, so the base entry and the versioned entry
	 * collapse to the one name they share.
	 * <p>
	 * The core cannot depend on the adapter module, so the rule is
	 * mirrored here rather than shared. The MCP module holds a test
	 * that reads both rules from one archive and proves that they
	 * agree, so the two cannot drift apart unnoticed.
	 *
	 * @param jar archive file, never null
	 * @return binary names of the classes, never null
	 * @throws IOException when the archive cannot be read
	 * @throws NullPointerException when {@code jar} is {@code null}
	 */
	public static Set<String> classesIn(Path jar) throws IOException {
		Objects.requireNonNull(jar, "jar");
		Set<String> names = new LinkedHashSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ClassEntry entry : classEntries(zip)) {
				names.add(entry.binaryName());
			}
		}
		return names;
	}

	/**
	 * Reads the class entries of an archive, applying the
	 * multi-release rule. A class that the archive holds under several
	 * releases is taken from the highest release the running runtime
	 * would load.
	 *
	 * @param zip open archive, never null
	 * @return the class entries, never null
	 */
	private static List<ClassEntry> classEntries(ZipFile zip) {
		Map<String, ClassEntry> chosen = new LinkedHashMap<>();
		int runtime = Runtime.version().feature();
		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			if (entry.isDirectory()) {
				continue;
			}
			String name = entry.getName();
			if (!name.endsWith(CLASS_SUFFIX)) {
				continue;
			}
			int release = 0;
			String relative = name;
			if (name.startsWith(VERSIONS_PREFIX)) {
				int slash = name.indexOf('/', VERSIONS_PREFIX.length());
				if (slash < 0) {
					continue;
				}
				int parsed;
				try {
					parsed = Integer.parseInt(name.substring(VERSIONS_PREFIX.length(), slash));
				} catch (NumberFormatException notVersioned) {
					continue;
				}
				if (parsed < FIRST_RELEASE || parsed > runtime) {
					continue;
				}
				release = parsed;
				relative = name.substring(slash + 1);
			}
			String binary = relative.substring(0, relative.length() - CLASS_SUFFIX.length()).replace('/', '.');
			ClassEntry previous = chosen.get(binary);
			if (previous == null || release > previous.release()) {
				chosen.put(binary, new ClassEntry(binary, relative, name, release));
			}
		}
		return List.copyOf(chosen.values());
	}

	/**
	 * Reads the classes a dependency names.
	 * <p>
	 * The classes are unpacked into a scratch directory, because the
	 * scanner reads class files and not archives. The scratch lives
	 * under the {@code .tmp} directory of the project, and it is
	 * removed again, whether the scan succeeded or not.
	 *
	 * @param scanner reference scanner, never null
	 * @param jar     dependency file, never null
	 * @param project project root, never null
	 * @return the classes the dependency names, never null
	 * @throws IOException when the archive cannot be read or unpacked
	 */
	private static Scanned referencesIn(ClassReferenceScanner scanner, Path jar, Path project) throws IOException {
		Path work = scratchDirectory(project);
		try {
			int refused = extractClasses(jar, work);
			return new Scanned(scanner.referencesOf(work), refused > 0);
		} finally {
			deleteTree(work);
		}
	}

	/**
	 * Unpacks the classes of an archive into a directory.
	 * <p>
	 * The path of every class is built from the sanitised name of the
	 * class, never from the name of the entry. An entry that is not a
	 * class this tool may extract is skipped and counted, and the
	 * archive that holds it cannot be fully analysed.
	 *
	 * @param jar    dependency file, never null
	 * @param target directory to unpack into, never null
	 * @return the number of entries that were not extracted, never negative
	 * @throws IOException when the archive cannot be read or written
	 */
	private static int extractClasses(Path jar, Path target) throws IOException {
		int refused = 0;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ClassEntry entry : classEntries(zip)) {
				Optional<Path> file = scratchPath(target, entry);
				if (file.isEmpty()) {
					// The entry is not a class this tool may extract.
					// Skipping it is honest: the archive cannot be
					// fully analysed, and the run says so.
					refused++;
					continue;
				}
				Path path = file.get();
				Files.createDirectories(path.getParent());
				try (InputStream in = zip.getInputStream(zip.getEntry(entry.entryName()))) {
					Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		return refused;
	}

	/**
	 * Builds the path a class takes inside the scratch directory.
	 * <p>
	 * The path comes from the sanitised name of the class and never
	 * from the name of the entry: an entry may spell a path that
	 * leaves the scratch directory, and such an entry would write
	 * outside it with the privileges of the run. The name of the entry
	 * must agree with the name of the class, so an entry that does
	 * not hold the class it claims is not written either.
	 *
	 * @param target scratch directory, never null
	 * @param entry  class entry of the archive, never null
	 * @return the path of the class inside the scratch directory, or
	 *         empty when the entry is not a class this tool may extract
	 */
	private static Optional<Path> scratchPath(Path target, ClassEntry entry) {
		Path root = target.toAbsolutePath().normalize();
		String rebuilt = entry.binaryName().replace('.', '/') + CLASS_SUFFIX;
		Path file;
		try {
			file = root.resolve(rebuilt).normalize();
		} catch (InvalidPathException unreadable) {
			// A name the platform cannot read is not a class.
			return Optional.empty();
		}
		if (!file.toAbsolutePath().normalize().startsWith(root)) {
			// The path leaves the scratch directory.
			return Optional.empty();
		}
		if (!rebuilt.equals(entry.relativePath())) {
			// The name of the entry and the name of the class do not
			// agree, so the entry does not hold the class it claims.
			return Optional.empty();
		}
		return Optional.of(file);
	}

	/**
	 * Creates the directory a dependency is unpacked into.
	 * <p>
	 * The scratch lives under the {@code .tmp} directory of the
	 * project, because a scratch file belongs to the project and never
	 * to the system temp directory. A project that cannot hold it
	 * falls back to the state directory of the tool, and only then to
	 * the system temp directory. The name of the directory is unique
	 * to the run, so it can never collide with a path of the project.
	 *
	 * @param project project root, never null
	 * @return the scratch directory, never null
	 * @throws IOException when no scratch directory can be created
	 */
	private static Path scratchDirectory(Path project) throws IOException {
		return scratchDirectory(project, stateDirectory(), temporaryDirectory());
	}

	/**
	 * Creates the directory a dependency is unpacked into.
	 *
	 * @param project   project root, never null
	 * @param state     state directory of the tool, never null
	 * @param temporary system temp directory, never null
	 * @return the scratch directory, never null
	 * @throws IOException when no scratch directory can be created
	 */
	static Path scratchDirectory(Path project, Path state, Path temporary) throws IOException {
		Path base = project.resolve(SCRATCH_DIRECTORY);
		try {
			Files.createDirectories(base);
			return Files.createTempDirectory(base, SCRATCH_PREFIX);
		} catch (IOException | RuntimeException ignored) {
			// The project cannot hold the scratch. The state directory
			// of the tool is next.
		}
		Path fallback = state.resolve(USAGE_DIRECTORY);
		try {
			Files.createDirectories(fallback);
			return Files.createTempDirectory(fallback, SCRATCH_PREFIX);
		} catch (IOException | RuntimeException ignored) {
			// The state directory cannot hold the scratch. The system
			// temp directory is the last resort.
		}
		return Files.createTempDirectory(SCRATCH_PREFIX);
	}

	/**
	 * The state directory of the tool: the directory that holds the
	 * caches of the tool.
	 * <p>
	 * The composition root of the server names the same directory, so
	 * the scratch of a scan and the caches of the tool live together.
	 *
	 * @return the state directory, never null
	 */
	private static Path stateDirectory() {
		return Paths.get(System.getProperty("user.home"), STATE_DIRECTORY);
	}

	/**
	 * The system temp directory: the last place a scratch may live.
	 *
	 * @return the system temp directory, never null
	 */
	private static Path temporaryDirectory() {
		return Paths.get(System.getProperty("java.io.tmpdir"));
	}

	/**
	 * Removes a directory and everything in it. The removal is best
	 * effort: a scratch directory that stays behind is a nuisance, and
	 * it must never hide the failure that made the scan fail.
	 *
	 * @param directory directory to remove, never null
	 */
	private static void deleteTree(Path directory) {
		try {
			Files.walkFileTree(directory, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
			// Best effort: the scratch directory is not the answer.
		}
	}

	/**
	 * Lists the output roots of the project's modules that hold
	 * compiled classes.
	 *
	 * @param project project root, never null
	 * @return the roots that hold compiled classes, never null
	 * @throws IOException when the modules cannot be listed
	 */
	private static List<Path> buildRoots(Path project) throws IOException {
		List<Path> roots = new ArrayList<>();
		for (Path module : MavenResolver.modulesOf(project)) {
			for (String root : OUTPUT_ROOTS) {
				Path candidate = module.resolve(root);
				if (Files.isDirectory(candidate)) {
					roots.add(candidate.toAbsolutePath().normalize());
				}
			}
		}
		return List.copyOf(roots);
	}

	/**
	 * Counts the class files of a root.
	 *
	 * @param root directory of compiled classes, never null
	 * @return the number of class files, never negative
	 * @throws IOException when the directory cannot be walked
	 */
	private static int countClassFiles(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return (int) walk.filter(Files::isRegularFile)
					.filter(file -> file.getFileName().toString().endsWith(CLASS_SUFFIX))
					.count();
		}
	}

	/**
	 * Builds the answer of a run that could not read the project's own
	 * classes. Every dependency is unverified, because a run that
	 * cannot see the code must never name a candidate for removal.
	 *
	 * @param project project root, never null
	 * @param jars    resolved dependency files, never null
	 * @param reason  why the run could not read the code, never null
	 * @return the answer, never null
	 */
	private static Report unverified(Path project, List<Path> jars, String reason) {
		List<DependencyUsage> usages = new ArrayList<>(jars.size());
		for (Path jar : jars) {
			usages.add(new DependencyUsage(jar.getFileName().toString(), jar, UsageStatus.UNVERIFIED,
					List.of(), List.of(), 0, reason));
		}
		return new Report(project, false, 0, List.of(), usages, reason);
	}

	/**
	 * Renders the roots a run read no class file in.
	 *
	 * @param roots the roots that were read, never null
	 * @return the reason, never null
	 */
	private static String emptyClasses(List<Path> roots) {
		StringBuilder reason = new StringBuilder("no compiled class file was found under ");
		for (Path root : roots) {
			if (reason.length() > "no compiled class file was found under ".length()) {
				reason.append(", ");
			}
			reason.append(portable(root));
		}
		return reason.toString();
	}

	/**
	 * Renders a path the same way on every machine, so a reason reads
	 * the same everywhere.
	 *
	 * @param path path to render, never null
	 * @return the path, never null
	 */
	private static String portable(Path path) {
		return path.toString().replace('\\', '/');
	}

	/**
	 * Renders a failure as a reason.
	 *
	 * @param failure the failure, never null
	 * @return the reason, never null
	 */
	private static String describe(Throwable failure) {
		String message = failure.getMessage();
		if (message == null || message.isBlank()) {
			return failure.getClass().getSimpleName();
		}
		return message;
	}

	/**
	 * One class of an archive: the name it carries, the path it takes
	 * when it is unpacked, the entry it comes from, and the release
	 * the entry belongs to.
	 *
	 * @param binaryName  binary name of the class, never null
	 * @param relativePath path of the class inside the archive, never null
	 * @param entryName   name of the entry in the archive, never null
	 * @param release     release the entry belongs to, zero for the base entry
	 */
	private record ClassEntry(String binaryName, String relativePath, String entryName, int release) {
	}

	/**
	 * The classes a dependency names, and whether its archive holds an
	 * entry this tool may not extract.
	 *
	 * @param names   the classes the dependency names, never null
	 * @param refused true when the archive holds an entry that is not a class
	 */
	private record Scanned(Set<String> names, boolean refused) {
	}

	/**
	 * The closure of the used dependencies: the dependencies a used
	 * dependency names, every class the closure reached, why the
	 * closure could not be completed, and the dependencies whose
	 * archive holds an entry this tool may not extract.
	 *
	 * @param via       the dependencies that carry each reference, never null
	 * @param referenced every class the closure reached, never null
	 * @param failure   why the closure could not be completed, empty when it could
	 * @param refusals  the dependencies that hold an entry this tool may not
	 *                  extract, with the reason, never null
	 */
	private record Closure(Map<Path, LinkedHashSet<Path>> via, Set<String> referenced, String failure,
			Map<Path, String> refusals) {
	}

	/**
	 * Reads the classes an archive holds.
	 * <p>
	 * The seam exists so a caller that holds a richer archive reader
	 * can supply it. The core carries its own reader, because the core
	 * cannot depend on the adapter module.
	 */
	@FunctionalInterface
	public interface ClassInventory {

		/**
		 * Lists the classes an archive holds.
		 *
		 * @param jar archive file, never null
		 * @return binary names of the classes, never null
		 * @throws IOException when the archive cannot be read
		 */
		Set<String> classesIn(Path jar) throws IOException;
	}

	/**
	 * The answer of one analysis.
	 *
	 * @param project          project root, never null
	 * @param scanned          true when the project's own classes were read
	 * @param scannedClassCount number of class files that were read
	 * @param scannedClasspath the roots that were read, empty when nothing was
	 * @param dependencies     the usage of every dependency, never null
	 * @param reason           why the run could not read the code, empty when it could
	 */
	public record Report(
			Path project,
			boolean scanned,
			int scannedClassCount,
			List<Path> scannedClasspath,
			List<DependencyUsage> dependencies,
			String reason) {

		/**
		 * Creates the report.
		 *
		 * @throws NullPointerException when {@code project} or a list is {@code null}
		 * @throws IllegalArgumentException when an invariant is broken
		 */
		public Report {
			project = Objects.requireNonNull(project, "project");
			scannedClasspath = List.copyOf(scannedClasspath);
			dependencies = List.copyOf(dependencies);
			reason = reason == null ? "" : reason;
			if (scanned && !reason.isEmpty()) {
				throw new IllegalArgumentException("reason must be empty when the scan happened");
			}
			if (!scanned && reason.isEmpty()) {
				throw new IllegalArgumentException("reason must not be empty when the scan did not happen");
			}
			if (!scanned && !scannedClasspath.isEmpty()) {
				throw new IllegalArgumentException("scannedClasspath must be empty when the scan did not happen");
			}
		}
	}
}

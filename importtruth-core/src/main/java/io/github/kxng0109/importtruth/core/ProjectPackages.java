package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.model.Packages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Learns a project's own packages from source and output directories.
 * A directory counts as a package only when it holds compiled classes or
 * sources, never from bare directory existence.
 *
 * <p>The result is memoised per project directory, because the walk it
 * needs is repeated work on every tool call. The memo is dropped when the
 * project's build definition changes: every build file the project
 * declares, nested modules included, is watched by size and modification
 * time, and a change to any of them rebuilds the set.
 *
 * <p>The rule has a boundary, and it is deliberate: a change to the
 * source tree alone, a package directory added or removed without a
 * change to any build file, is not seen until the build definition
 * changes. The boundary is safe in the direction that matters, because
 * a package that no longer exists is verified on demand before it is
 * allowed to resolve an import. The other direction, a package that
 * appeared, can only cost a candidate verdict, never a wrong one.
 */
public final class ProjectPackages {

	/**
	 * The source and output roots a project's own packages come from.
	 */
	private static final List<String> ROOTS =
			List.of("src/main/java", "src/test/java", "target/classes", "target/test-classes");

	/**
	 * Maximum retained project contexts. A long session touches more
	 * projects than that only by accident, and the contexts are small.
	 */
	static final int MAX_CONTEXTS = 32;

	/**
	 * How long a caller waits for a context another thread is building
	 * before it gives up. Not final: a test shortens it.
	 */
	static Duration followerWait = Duration.ofMinutes(10);

	/**
	 * The memoised contexts, one per project directory. Least recently
	 * used retention keeps the map bounded.
	 */
	private static final Map<Path, Context> CACHE =
			Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Path, Context> eldest) {
					return size() > MAX_CONTEXTS;
				}
			});

	/**
	 * The contexts being built, one per project directory. One builder
	 * at a time, so a burst of cold requests walks the tree once.
	 */
	private static final Map<Path, CompletableFuture<Context>> IN_FLIGHT = new ConcurrentHashMap<>();

	/**
	 * Counts the source tree walks. Not final: a test reads it to prove
	 * that a repeated call walks the tree no more.
	 */
	static final AtomicInteger WALKS = new AtomicInteger();

	/**
	 * Runs before the builder reads the project. Not final: a test
	 * holds the builder so a follower can be tested.
	 */
	static BuildHook beforeBuild = () -> { };

	/**
	 * A hook that runs before a project is read.
	 */
	interface BuildHook {

		/**
		 * Runs before the project is read.
		 *
		 * @throws IOException when the project cannot be read
		 */
		void run() throws IOException;
	}

	/**
	 * The number of memoised contexts. Not final: a test reads it to
	 * prove the cache stays bounded.
	 *
	 * @return the number of retained contexts
	 */
	static int cachedContexts() {
		return CACHE.size();
	}

	private ProjectPackages() {
	}

	/**
	 * Collects own-project package names.
	 *
	 * <p>The answer is memoised per project. The memo holds while every
	 * build file the project declares keeps its size and modification
	 * time; a change to any of them rebuilds the set.
	 *
	 * @param projectDir project root, never null
	 * @return dotted package names, never null
	 * @throws IOException when directories cannot be walked
	 * @throws NullPointerException if {@code projectDir} is {@code null}
	 */
	public static Set<String> of(Path projectDir) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Path key = projectDir.toAbsolutePath().normalize();
		Context cached = CACHE.get(key);
		if (cached != null && cached.current()) {
			return cached.packages();
		}
		return build(key).packages();
	}

	/**
	 * Tests whether an own package covers a name, and whether the
	 * package still holds sources. A package that no longer exists
	 * never resolves an import, even when the memo still remembers it.
	 *
	 * @param own own-project packages, never null
	 * @param name qualified name, never null
	 * @return true when an own package covers the name
	 */
	static boolean covers(Set<String> own, String name) {
		Objects.requireNonNull(own, "own");
		Objects.requireNonNull(name, "name");
		for (String pkg : own) {
			if (!Packages.coveredBy(name, pkg)) {
				continue;
			}
			if (own instanceof OwnPackages packages && !packages.holds(pkg)) {
				continue;
			}
			return true;
		}
		return false;
	}

	/**
	 * The signature of a project's build definition: every build file
	 * the project declares, nested modules included, with its size and
	 * modification time. A set that did not come from
	 * {@link #of(Path)} carries no project and answers with an empty
	 * signature.
	 *
	 * @param own own-project packages, never null
	 * @return the signature, never null
	 */
	static String buildSignature(Set<String> own) {
		Objects.requireNonNull(own, "own");
		if (own instanceof OwnPackages packages) {
			return packages.signature();
		}
		return "";
	}

	/**
	 * Builds the context of one project, one builder at a time.
	 *
	 * @param key normalised project root, never null
	 * @return the context, never null
	 * @throws IOException when the project cannot be read
	 */
	private static Context build(Path key) throws IOException {
		CompletableFuture<Context> pending = new CompletableFuture<>();
		CompletableFuture<Context> started = IN_FLIGHT.putIfAbsent(key, pending);
		if (started != null) {
			return await(started, key);
		}
		try {
			beforeBuild.run();
			Context context = new Context(key);
			pending.complete(context);
			CACHE.put(key, context);
			return context;
		} catch (Throwable failure) {
			pending.completeExceptionally(failure);
			if (failure instanceof IOException io) {
				throw io;
			}
			if (failure instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IOException("Failed to read the packages of " + key, failure);
		} finally {
			IN_FLIGHT.remove(key, pending);
		}
	}

	/**
	 * Waits for the builder of a context another thread holds.
	 *
	 * @param started the shared future, never null
	 * @param key     normalised project root, never null
	 * @return the context, never null
	 * @throws IOException when the builder failed or the wait expired
	 */
	private static Context await(CompletableFuture<Context> started, Path key) throws IOException {
		try {
			return started.get(followerWait.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException expired) {
			throw new IOException("Timed out after " + followerWait.toMillis()
					+ " ms waiting for the packages of " + key, expired);
		} catch (ExecutionException failure) {
			Throwable cause = Objects.requireNonNullElse(failure.getCause(), failure);
			throw new IOException("Failed to read the packages of " + key, cause);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted waiting for the packages of " + key, interrupted);
		}
	}

	/**
	 * The own packages of one project, and the build files that decide
	 * whether they are still true.
	 */
	private static final class Context {

		private final Path projectDir;
		private final Set<String> packages;
		private final List<Path> poms;
		private final String build;

		/**
		 * Reads the project.
		 *
		 * @param projectDir normalised project root, never null
		 * @throws IOException when the project cannot be read
		 */
		private Context(Path projectDir) throws IOException {
			this.projectDir = projectDir;
			Set<String> found = new HashSet<>();
			for (String root : List.of("src/main/java", "src/test/java")) {
				collectByExtension(projectDir.resolve(root), found, ".java");
			}
			for (String root : List.of("target/classes", "target/test-classes")) {
				collectByExtension(projectDir.resolve(root), found, ".class");
			}
			this.packages = new OwnPackages(Set.copyOf(found), this);
			this.poms = declaredPoms(projectDir);
			this.build = signature();
		}

		/**
		 * The own package names.
		 *
		 * @return dotted package names, never null
		 */
		private Set<String> packages() {
			return packages;
		}

		/**
		 * The signature of the build definition, read now. A build
		 * file that vanished reads as changed, never as unchanged.
		 *
		 * @return the signature, never null
		 */
		private String signature() {
			StringBuilder key = new StringBuilder();
			for (Path pom : poms) {
				key.append(pom.toAbsolutePath()).append(':');
				Stamp stamp = Stamp.of(pom);
				if (stamp == null) {
					key.append("gone");
				} else {
					key.append(stamp.size()).append('@').append(stamp.modified().to(TimeUnit.NANOSECONDS));
				}
				key.append('\n');
			}
			return key.toString();
		}

		/**
		 * Tests whether the build definition still reads the same.
		 *
		 * @return true when no build file changed
		 */
		private boolean current() {
			return build.equals(signature());
		}
	}

	/**
	 * The own package names of a project, able to prove that a package
	 * still holds sources.
	 */
	private static final class OwnPackages extends AbstractSet<String> {

		private final Set<String> names;
		private final Context context;

		/**
		 * Creates the set.
		 *
		 * @param names   package names, never null
		 * @param context the project they came from, never null
		 */
		private OwnPackages(Set<String> names, Context context) {
			this.names = names;
			this.context = context;
		}

		@Override
		public Iterator<String> iterator() {
			return names.iterator();
		}

		@Override
		public int size() {
			return names.size();
		}

		@Override
		public boolean contains(Object candidate) {
			return names.contains(candidate);
		}

		/**
		 * The signature of the project's build definition, read now.
		 *
		 * @return the signature, never null
		 */
		private String signature() {
			return context.signature();
		}

		/**
		 * Tests whether a package still holds sources.
		 *
		 * @param pkg dotted package name, never null
		 * @return true when a root still holds sources for the package
		 */
		private boolean holds(String pkg) {
			String relative = pkg.replace('.', '/');
			for (String root : ROOTS) {
				if (holdsSources(context.projectDir.resolve(root).resolve(relative))) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Tests whether a directory holds sources or classes.
		 *
		 * @param dir candidate package directory, never null
		 * @return true when the directory holds a source or a class
		 */
		private static boolean holdsSources(Path dir) {
			if (!Files.isDirectory(dir)) {
				return false;
			}
			try (Stream<Path> entries = Files.list(dir)) {
				return entries.anyMatch(entry -> {
					String name = entry.getFileName().toString();
					return name.endsWith(".java") || name.endsWith(".class");
				});
			} catch (IOException unreadable) {
				return false;
			}
		}
	}

	/**
	 * The size and modification time of one build file.
	 *
	 * @param size     file size in bytes
	 * @param modified file modification time
	 */
	private record Stamp(long size, FileTime modified) {

		/**
		 * Reads the stamp of a file.
		 *
		 * @param file build file, never null
		 * @return the stamp, or null when the file is gone
		 */
		private static Stamp of(Path file) {
			try {
				return new Stamp(Files.size(file), Files.getLastModifiedTime(file));
			} catch (IOException unreadable) {
				return null;
			}
		}
	}

	/**
	 * The build files a project declares, nested modules included.
	 *
	 * @param projectDir project root, never null
	 * @return the build files, never null
	 */
	private static List<Path> declaredPoms(Path projectDir) {
		List<Path> poms = new ArrayList<>();
		collectPoms(projectDir, poms, 0);
		return List.copyOf(poms);
	}

	/**
	 * Collects the build files of a module and of the modules it
	 * declares. The module list follows the resolver's own reading of
	 * a project, so the token sees at least what the resolver sees.
	 *
	 * @param module module directory, never null
	 * @param poms   collected build files, never null
	 * @param depth  current depth, never negative
	 */
	private static void collectPoms(Path module, List<Path> poms, int depth) {
		if (depth > MavenResolver.MAX_POM_DEPTH) {
			return;
		}
		Path pom = module.resolve("pom.xml");
		if (Files.isRegularFile(pom) && !poms.contains(pom)) {
			poms.add(pom);
		}
		List<Path> modules;
		try {
			modules = MavenResolver.modulesOf(module);
		} catch (IOException unreadable) {
			return;
		}
		for (Path child : modules) {
			if (child.equals(module)) {
				continue;
			}
			collectPoms(child, poms, depth + 1);
		}
	}

	/**
	 * Collects the packages of one root.
	 *
	 * @param root      source or output root, never null
	 * @param packages  collected packages, never null
	 * @param extension file extension to look for, never null
	 * @throws IOException when directories cannot be walked
	 */
	private static void collectByExtension(Path root, Set<String> packages, String extension)
			throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		WALKS.incrementAndGet();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(p -> p.toString().endsWith(extension))
					.map(p -> root.relativize(p.getParent()))
					.map(ProjectPackages::dotted)
					.forEach(packages::add);
		}
	}

	/**
	 * Renders a relative path as a dotted package name.
	 *
	 * @param relative relative path, never null
	 * @return dotted name, never null
	 */
	private static String dotted(Path relative) {
		StringBuilder dotted = new StringBuilder();
		for (Path part : relative) {
			if (!dotted.isEmpty()) {
				dotted.append('.');
			}
			dotted.append(part);
		}
		return dotted.toString();
	}
}

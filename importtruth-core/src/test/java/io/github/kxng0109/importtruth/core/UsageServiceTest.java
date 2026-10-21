package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.ClassReferenceScanner;
import io.github.kxng0109.importtruth.model.DependencyUsage;
import io.github.kxng0109.importtruth.model.UsageStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Verifies the usage analysis: the three verdicts, the closure, and
 * the paths where nothing may be called a candidate for removal.
 * <p>
 * The scanner is a double, so the fixtures hold no real bytecode. The
 * real scanner is exercised by the MCP module, which holds the adapter
 * on its test classpath.
 */
@DisplayName("UsageService")
@Execution(ExecutionMode.SAME_THREAD)
final class UsageServiceTest {

	/** The build file of a one module project. */
	private static final String POM = "<project><modelVersion>4.0.0</modelVersion>"
			+ "<groupId>t</groupId><artifactId>t</artifactId><version>1</version></project>";

	/** The name of the file a hostile archive tries to write outside the scratch. */
	private static final String PROBE = "importtruth-zip-slip-probe.class";

	/** The entry a hostile archive carries to leave the scratch directory. */
	private static final String TRAVERSAL = "../../" + PROBE;

	/** The prefix of the directory a dependency is unpacked into. */
	private static final String SCRATCH_PREFIX = "importtruth-usage-";

	@TempDir
	private Path work;

	private Path project;
	private Path alpha;
	private Path beta;
	private Path gamma;

	@BeforeEach
	void setUp() throws Exception {
		FakeClassReferenceScanner.reset();
		project = work.resolve("project");
		Files.createDirectories(project.resolve("target/classes"));
		Files.write(project.resolve("pom.xml"), POM.getBytes(StandardCharsets.UTF_8));
		Files.write(project.resolve("target/classes/App.class"), "app".getBytes(StandardCharsets.UTF_8));
		alpha = jar("alpha-1.0.jar", "dep/Widget.class");
		beta = jar("beta-1.0.jar", "dep/Marker.class");
		gamma = jar("gamma-1.0.jar", "dep/Orphan.class");
		FakeClassReferenceScanner.carries("App", "dep.Widget");
		FakeClassReferenceScanner.carries("Widget", "dep.Marker");
	}

	@Test
	@DisplayName("reports a direct, an indirect, and an unreferenced dependency")
	void reportsTheThreeVerdicts() throws Exception {
		UsageService service = service(alpha, beta, gamma);

		UsageService.Report report = service.analyze(project);

		assertThat(report.scanned()).as("the scan happened").isTrue();
		assertThat(report.scannedClassCount()).as("scanned class files").isEqualTo(1);
		assertThat(report.scannedClasspath()).as("scanned root")
				.containsExactly(project.resolve("target/classes").toAbsolutePath().normalize());
		assertThat(statuses(report)).as("verdicts")
				.containsExactly(UsageStatus.USED_DIRECT, UsageStatus.USED_INDIRECT, UsageStatus.UNREFERENCED);
		DependencyUsage direct = report.dependencies().get(0);
		assertThat(direct.name()).as("direct name").isEqualTo("alpha-1.0.jar");
		assertThat(direct.referencedClasses()).as("direct classes").containsExactly("dep.Widget");
		assertThat(direct.totalClassCount()).as("direct class count").isEqualTo(1);
		assertThat(direct.via()).as("direct via").isEmpty();
		DependencyUsage indirect = report.dependencies().get(1);
		assertThat(indirect.name()).as("indirect name").isEqualTo("beta-1.0.jar");
		assertThat(indirect.via()).as("indirect via").containsExactly("alpha-1.0.jar");
		assertThat(indirect.referencedClasses()).as("indirect classes").containsExactly("dep.Marker");
		assertThat(indirect.totalClassCount()).as("indirect class count").isEqualTo(1);
		DependencyUsage unreferenced = report.dependencies().get(2);
		assertThat(unreferenced.name()).as("unreferenced name").isEqualTo("gamma-1.0.jar");
		assertThat(unreferenced.referencedClasses()).as("unreferenced classes").isEmpty();
		assertThat(unreferenced.totalClassCount()).as("unreferenced class count").isEqualTo(1);
		assertThat(unreferenced.reason()).as("unreferenced reason").isEmpty();
	}

	@Test
	@DisplayName("follows a chain of dependencies to its end")
	void followsAChainOfDependencies() throws Exception {
		FakeClassReferenceScanner.carries("Marker", "dep.Orphan");

		UsageService.Report report = service(alpha, beta, gamma).analyze(project);

		assertThat(statuses(report)).as("verdicts")
				.containsExactly(UsageStatus.USED_DIRECT, UsageStatus.USED_INDIRECT, UsageStatus.USED_INDIRECT);
		assertThat(report.dependencies().get(1).via()).as("second hop via")
				.containsExactly("alpha-1.0.jar");
		assertThat(report.dependencies().get(2).via()).as("third hop via")
				.containsExactly("beta-1.0.jar");
	}

	@Test
	@DisplayName("never names a dependency a candidate when the project has no compiled classes")
	void answersUnverifiedWithoutCompiledClasses() throws Exception {
		Path empty = work.resolve("empty");
		Files.createDirectories(empty);
		Files.write(empty.resolve("pom.xml"), POM.getBytes(StandardCharsets.UTF_8));

		UsageService.Report report = service(alpha, beta, gamma).analyze(empty);

		assertThat(report.scanned()).as("no scan happened").isFalse();
		assertThat(report.scannedClasspath()).as("no scanned root").isEmpty();
		assertThat(report.scannedClassCount()).as("no scanned class").isZero();
		assertThat(report.reason()).as("the reason names what was not found")
				.contains("target/classes")
				.contains("target/test-classes");
		assertThat(report.reason()).as("the reason names no candidate").doesNotContain("UNREFERENCED");
		assertThat(statuses(report)).as("every dependency is unverified")
				.containsOnly(UsageStatus.UNVERIFIED);
	}

	@Test
	@DisplayName("never names a dependency a candidate when the compiled classes are empty")
	void answersUnverifiedWhenTheCompiledClassesAreEmpty() throws Exception {
		Files.delete(project.resolve("target/classes/App.class"));
		Files.createDirectories(project.resolve("target/test-classes"));

		UsageService.Report report = service(alpha, beta, gamma).analyze(project);

		assertThat(report.scanned()).as("no scan happened").isFalse();
		assertThat(report.reason()).as("the reason names what was not found")
				.contains("no compiled class file")
				.contains("target/classes")
				.contains("target/test-classes");
		assertThat(statuses(report)).as("every dependency is unverified")
				.containsOnly(UsageStatus.UNVERIFIED);
	}

	@Test
	@DisplayName("names the kind of failure when the failure names nothing")
	void namesTheKindOfFailureWhenTheFailureNamesNothing() throws Exception {
		FakeClassReferenceScanner.failOn("App", null);

		UsageService.Report report = service(alpha, beta, gamma).analyze(project);

		assertThat(report.reason()).as("the reason names the kind of failure")
				.contains("IOException");
		assertThat(statuses(report)).as("every dependency is unverified")
				.containsOnly(UsageStatus.UNVERIFIED);
	}

	@Test
	@DisplayName("ignores a reference no dependency holds and a reference to a used dependency")
	void ignoresReferencesThatChangeNothing() throws Exception {
		FakeClassReferenceScanner.carries("App", "dep.Widget", "dep.Marker");
		FakeClassReferenceScanner.carries("Widget", "dep.Marker", "com.example.Absent");

		UsageService.Report report = service(alpha, beta, gamma).analyze(project);

		assertThat(statuses(report)).as("verdicts")
				.containsExactly(UsageStatus.USED_DIRECT, UsageStatus.USED_DIRECT, UsageStatus.UNREFERENCED);
	}

	@Test
	@DisplayName("keeps the report honest about what it knows")
	void keepsTheReportHonest() {
		Path root = work.resolve("root");

		assertThatThrownBy(() -> new UsageService.Report(root, true, 1, List.of(root), List.of(), "a reason"))
				.as("a scanned report carries no reason")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UsageService.Report(root, false, 0, List.of(), List.of(), ""))
				.as("a report without a scan carries a reason")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UsageService.Report(root, false, 0, List.of(), List.of(), null))
				.as("a reason that is absent is a reason that is empty")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UsageService.Report(root, false, 0, List.of(root), List.of(), "no scan"))
				.as("a report without a scan carries no root")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("never names a dependency a candidate when the own classes cannot be read")
	void answersUnverifiedWhenTheOwnClassesCannotBeRead() throws Exception {
		FakeClassReferenceScanner.failOn("App");

		UsageService.Report report = service(alpha, beta, gamma).analyze(project);

		assertThat(report.scanned()).as("no scan happened").isFalse();
		assertThat(report.reason()).as("the reason").contains("could not be read");
		assertThat(statuses(report)).as("every dependency is unverified")
				.containsOnly(UsageStatus.UNVERIFIED);
	}

	@Test
	@DisplayName("never names a dependency a candidate when no scanner is registered")
	void answersUnverifiedWithoutAScanner() throws Exception {
		UsageService service = service(alpha, beta, gamma);
		service.scannerLoader = () -> Optional.empty();

		UsageService.Report report = service.analyze(project);

		assertThat(report.scanned()).as("no scan happened").isFalse();
		assertThat(report.reason()).as("the reason")
				.isEqualTo("no class reference scanner is registered on the classpath");
		assertThat(statuses(report)).as("every dependency is unverified")
				.containsOnly(UsageStatus.UNVERIFIED);
	}

	@Test
	@DisplayName("never names a dependency a candidate when the closure cannot be completed")
	void neverNamesACandidateWhenTheClosureFails() throws Exception {
		FakeClassReferenceScanner.failOn("Widget");

		UsageService.Report report = service(alpha, beta, gamma).analyze(project);

		assertThat(statuses(report)).as("verdicts")
				.containsExactly(UsageStatus.USED_DIRECT, UsageStatus.UNVERIFIED, UsageStatus.UNVERIFIED);
		assertThat(report.dependencies().get(1).reason()).as("the reason").contains("could not be read");
	}

	@Test
	@DisplayName("answers unverified for a dependency that cannot be read")
	void answersUnverifiedForAnUnreadableJar() throws Exception {
		Path broken = work.resolve("broken-1.0.jar");
		Files.write(broken, "not an archive".getBytes(StandardCharsets.UTF_8));

		UsageService.Report report = service(alpha, broken, gamma).analyze(project);

		assertThat(statuses(report)).as("verdicts")
				.containsExactly(UsageStatus.USED_DIRECT, UsageStatus.UNVERIFIED, UsageStatus.UNREFERENCED);
		DependencyUsage unreadable = report.dependencies().get(1);
		assertThat(unreadable.name()).as("unreadable name").isEqualTo("broken-1.0.jar");
		assertThat(unreadable.reason()).as("unreadable reason").isEqualTo("the JAR could not be read");
		assertThat(unreadable.totalClassCount()).as("no class count").isZero();
		assertThat(report.dependencies().get(2).status()).as("the other dependencies are unaffected")
				.isEqualTo(UsageStatus.UNREFERENCED);
	}

	@Test
	@DisplayName("reports a dependency that holds only resources")
	void reportsADependencyThatHoldsOnlyResources() throws Exception {
		Path resources = jar("resources-1.0.jar", "META-INF/services/com.example.Service");

		UsageService.Report report = service(resources).analyze(project);

		DependencyUsage usage = report.dependencies().get(0);
		assertThat(usage.status()).as("status").isEqualTo(UsageStatus.UNREFERENCED);
		assertThat(usage.totalClassCount()).as("class count").isZero();
		assertThat(usage.referencedClasses()).as("referenced classes").isEmpty();
	}

	@Test
	@DisplayName("collapses a multi release archive to one name per class")
	void collapsesAMultiReleaseArchive() throws Exception {
		Path multi = jar("multi-1.0.jar",
				"META-INF/versions/17/dep/Widget.class",
				"dep/Widget.class",
				"META-INF/versions/9/dep/Widget.class",
				"META-INF/versions/8/dep/TooOld.class",
				"META-INF/versions/99/dep/TooNew.class",
				"META-INF/versions/x/dep/Broken.class",
				"META-INF/versions/17.class",
				"dep/notes.txt",
				"dep/");

		Set<String> names = UsageService.classesIn(multi);

		assertThat(names).as("inventory").containsExactly("dep.Widget");
	}

	@Test
	@DisplayName("scans the classes of a multi release archive")
	void scansTheClassesOfAMultiReleaseArchive() throws Exception {
		Path multi = jar("multi-1.0.jar",
				"dep/Widget.class",
				"META-INF/versions/17/dep/Widget.class");

		UsageService.Report report = service(multi, beta).analyze(project);

		assertThat(statuses(report)).as("verdicts")
				.containsExactly(UsageStatus.USED_DIRECT, UsageStatus.USED_INDIRECT);
	}

	@Test
	@DisplayName("scans the classes the caller names")
	void scansTheClassesTheCallerNames() throws Exception {
		Path classes = work.resolve("classes");
		Files.createDirectories(classes);
		Files.write(classes.resolve("Other.class"), "other".getBytes(StandardCharsets.UTF_8));
		FakeClassReferenceScanner.carries("Other", "dep.Marker");

		UsageService.Report report = service(alpha, beta, gamma).analyze(project, classes);

		assertThat(report.scannedClasspath()).as("scanned root")
				.containsExactly(classes.toAbsolutePath().normalize());
		assertThat(statuses(report)).as("the named classes decide")
				.containsExactly(UsageStatus.UNREFERENCED, UsageStatus.USED_DIRECT, UsageStatus.UNREFERENCED);
	}

	@Test
	@DisplayName("answers unverified when the named classes are not there")
	void answersUnverifiedWhenTheNamedClassesAreNotThere() throws Exception {
		UsageService.Report report = service(alpha, beta, gamma)
				.analyze(project, work.resolve("absent"));

		assertThat(report.scanned()).as("no scan happened").isFalse();
		assertThat(report.reason()).as("the reason names the path")
				.contains("absent");
		assertThat(statuses(report)).as("every dependency is unverified")
				.containsOnly(UsageStatus.UNVERIFIED);
	}

	@Test
	@DisplayName("uses the archive reader the caller supplies")
	void usesTheSuppliedArchiveReader() throws Exception {
		UsageService service = new UsageService(
				(projectDir, allowNetwork) -> List.of(alpha, beta, gamma),
				jar -> {
					String name = jar.getFileName().toString();
					if (name.startsWith("beta")) {
						throw new IOException("the archive is sealed");
					}
					return name.startsWith("alpha") ? Set.of("dep.Widget") : Set.of();
				});

		UsageService.Report report = service.analyze(project);

		assertThat(statuses(report)).as("verdicts")
				.containsExactly(UsageStatus.USED_DIRECT, UsageStatus.UNVERIFIED, UsageStatus.UNREFERENCED);
		assertThat(report.dependencies().get(1).reason()).as("the reason").isEqualTo("the JAR could not be read");
	}

	@Test
	@DisplayName("carries the standing caveat")
	void carriesTheStandingCaveat() {
		assertThat(UsageService.CAVEAT).as("the caveat")
				.isEqualTo("caveat: a class-file scan cannot see reflection, ServiceLoader, "
						+ "Class.forName with a computed name, MethodHandles, or resources; "
						+ "UNREFERENCED is a candidate, not a verdict");
	}

	@Test
	@DisplayName("never writes a class outside the scratch directory and never names such an archive a candidate")
	void neverWritesOutsideTheScratchDirectory() throws Exception {
		Path hostile = jar("hostile-1.0.jar", "dep/Widget.class", TRAVERSAL, "." + PROBE,
				"dep/My.Class.class", "dep/\0evil.class");
		RecordingScanner scanner = new RecordingScanner();
		UsageService service = service(hostile, beta);
		service.scannerLoader = () -> Optional.of(scanner);

		try {
			UsageService.Report report = service.analyze(project);

			for (Path location : probeLocations(scanner)) {
				assertThat(Files.exists(location)).as("the probe file at " + location).isFalse();
			}
			try (Stream<Path> walk = Files.walk(project)) {
				assertThat(walk.filter(file -> PROBE.equals(file.getFileName().toString())))
						.as("no probe file under the project").isEmpty();
			}
			DependencyUsage usage = report.dependencies().get(0);
			assertThat(usage.status()).as("the hostile archive").isEqualTo(UsageStatus.UNVERIFIED);
			assertThat(usage.reason()).as("the reason").contains("not a class");
			assertThat(usage.referencedClasses()).as("no class is named").isEmpty();
			assertThat(report.dependencies().get(1).status()).as("the other dependency is unaffected")
					.isEqualTo(UsageStatus.USED_INDIRECT);
		} finally {
			// The hostile entries try to write outside the scratch
			// directory. Nothing may stay behind, wherever they landed.
			deleteProbes(scanner);
		}
	}

	@Test
	@DisplayName("still analyses an archive with ordinary class entries")
	void stillAnalysesAnOrdinaryArchive() throws Exception {
		Path ordinary = jar("ordinary-1.0.jar", "dep/Widget.class", "dep/Gadget.class");

		UsageService.Report report = service(ordinary, beta).analyze(project);

		DependencyUsage usage = report.dependencies().get(0);
		assertThat(usage.status()).as("status").isEqualTo(UsageStatus.USED_DIRECT);
		assertThat(usage.referencedClasses()).as("the classes the code reaches").containsExactly("dep.Widget");
		assertThat(usage.totalClassCount()).as("the classes the archive holds").isEqualTo(2);
	}

	@Test
	@DisplayName("keeps the scratch under the project and removes it")
	void keepsTheScratchUnderTheProject() throws Exception {
		RecordingScanner scanner = new RecordingScanner();
		UsageService service = service(alpha, beta);
		service.scannerLoader = () -> Optional.of(scanner);

		service.analyze(project);

		List<Path> scratches = scanner.scratches();
		assertThat(scratches).as("one scratch per used dependency").hasSize(2);
		Path temporary = Paths.get(System.getProperty("java.io.tmpdir"));
		for (Path scratch : scratches) {
			assertThat(scratch.startsWith(project.resolve(".tmp"))).as("the scratch is under the project").isTrue();
			assertThat(scratch.getParent().equals(temporary)).as("the scratch is not in the system temp").isFalse();
			assertThat(Files.exists(scratch)).as("the scratch is removed").isFalse();
		}
	}

	@Test
	@DisplayName("falls back to the state directory when the project cannot hold the scratch")
	void fallsBackToTheStateDirectory() throws Exception {
		Path home = work.resolve("home");
		Files.createDirectories(home);
		Path blocked = blockedProject();
		RecordingScanner scanner = new RecordingScanner();
		UsageService service = service(alpha);
		service.scannerLoader = () -> Optional.of(scanner);
		String userHome = System.getProperty("user.home");

		try {
			System.setProperty("user.home", home.toString());
			service.analyze(blocked);
		} finally {
			System.setProperty("user.home", userHome);
		}

		List<Path> scratches = scanner.scratches();
		assertThat(scratches).as("the scratch").hasSize(1);
		assertThat(scratches.get(0).startsWith(home.resolve(".importtruth")))
				.as("the scratch is in the state directory").isTrue();
		assertThat(Files.exists(scratches.get(0))).as("the scratch is removed").isFalse();
	}

	@Test
	@DisplayName("falls back to the system temp when the state directory cannot hold the scratch")
	void fallsBackToTheSystemTemp() throws Exception {
		Path file = work.resolve("file");
		Files.write(file, "not a directory".getBytes(StandardCharsets.UTF_8));
		Path blocked = blockedProject();
		RecordingScanner scanner = new RecordingScanner();
		UsageService service = service(alpha);
		service.scannerLoader = () -> Optional.of(scanner);
		String userHome = System.getProperty("user.home");

		try {
			System.setProperty("user.home", file.resolve("home").toString());
			service.analyze(blocked);
		} finally {
			System.setProperty("user.home", userHome);
		}

		List<Path> scratches = scanner.scratches();
		assertThat(scratches).as("the scratch").hasSize(1);
		assertThat(scratches.get(0).startsWith(Paths.get(System.getProperty("java.io.tmpdir"))))
				.as("the scratch is in the system temp").isTrue();
		assertThat(Files.exists(scratches.get(0))).as("the scratch is removed").isFalse();
	}

	private UsageService service(Path... jars) {
		List<Path> resolved = List.of(jars);
		return new UsageService((projectDir, allowNetwork) -> resolved);
	}

	private static List<UsageStatus> statuses(UsageService.Report report) {
		return report.dependencies().stream().map(DependencyUsage::status).toList();
	}

	/**
	 * Builds a project whose scratch directory cannot be created: the
	 * {@code .tmp} entry of the project is a file, not a directory.
	 *
	 * @return the project, never null
	 * @throws IOException when the project cannot be written
	 */
	private Path blockedProject() throws IOException {
		Path blocked = work.resolve("blocked");
		Files.createDirectories(blocked.resolve("target/classes"));
		Files.write(blocked.resolve("pom.xml"), POM.getBytes(StandardCharsets.UTF_8));
		Files.write(blocked.resolve("target/classes/App.class"), "app".getBytes(StandardCharsets.UTF_8));
		Files.write(blocked.resolve(".tmp"), "not a directory".getBytes(StandardCharsets.UTF_8));
		return blocked;
	}

	/**
	 * Lists the places a hostile archive tries to write its probe
	 * file: the project, the directory that holds the system scratch,
	 * and every scratch the run recorded.
	 *
	 * @param scanner scanner that recorded the scratch directories
	 * @return the places, never null
	 */
	private List<Path> probeLocations(RecordingScanner scanner) {
		List<Path> locations = new ArrayList<>();
		locations.add(project.resolve(PROBE));
		Path temporary = Paths.get(System.getProperty("java.io.tmpdir")).getParent();
		if (temporary != null) {
			locations.add(temporary.resolve(PROBE));
		}
		for (Path scratch : scanner.scratches()) {
			Path parent = scratch.getParent();
			if (parent != null && parent.getParent() != null) {
				locations.add(parent.getParent().resolve(PROBE));
			}
		}
		return locations;
	}

	/**
	 * Removes the probe files a hostile archive may have written.
	 *
	 * @param scanner scanner that recorded the scratch directories
	 */
	private void deleteProbes(RecordingScanner scanner) {
		for (Path location : probeLocations(scanner)) {
			try {
				Files.deleteIfExists(location);
			} catch (IOException ignored) {
				// A probe file is not worth failing a test over.
			}
		}
	}

	private Path jar(String name, String... entries) throws IOException {
		Path file = work.resolve(name);
		try (OutputStream out = Files.newOutputStream(file);
				JarOutputStream zip = new JarOutputStream(out)) {
			for (String entry : entries) {
				zip.putNextEntry(new JarEntry(entry));
				zip.write(("class " + entry).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return file;
	}

	/**
	 * A scanner that remembers every directory it was asked to read.
	 * <p>
	 * The double of the service loader answers by the simple names of
	 * the class files it is given. The recording wrapper keeps the
	 * directories, so a test can see where the scratch of a scan
	 * lived.
	 */
	private static final class RecordingScanner implements ClassReferenceScanner {

		private final FakeClassReferenceScanner delegate = new FakeClassReferenceScanner();
		private final List<Path> read = new ArrayList<>();

		@Override
		public Set<String> referencesOf(Path classFileOrDirectory) throws IOException {
			read.add(classFileOrDirectory);
			return delegate.referencesOf(classFileOrDirectory);
		}

		private List<Path> scratches() {
			return read.stream()
					.filter(path -> path.getFileName().toString().startsWith(SCRATCH_PREFIX))
					.toList();
		}
	}
}

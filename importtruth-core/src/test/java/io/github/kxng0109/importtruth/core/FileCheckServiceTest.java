package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies file checking: clean files silent, misses flagged with lines,
 * wildcards package-checked, broken files skipped.
 */
@DisplayName("FileCheckService")
final class FileCheckServiceTest {

	@TempDir
	private Path project;

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

	private JarIndexStore openStore(Path dir) throws IOException {
		JarIndexStore created = new JarIndexStore(dir);
		openStores.add(created);
		return created;
	}

	@Test
	@DisplayName("silent on clean imports")
	void silentOnClean() throws Exception {
		FileCheckService check = service();
		Path file = source("com/example/Clean.java",
				"package com.example; import java.util.List; import com.example.Widget;"
						+ " public class Clean { List<Widget> items; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("no findings when clean").isEmpty();
	}

	@Test
	@DisplayName("flags a missing import with its line")
	void flagsMissingWithLine() throws Exception {
		FileCheckService check = service();
		Path file = source("com/other/Missing.java",
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("missing kind").isEqualTo(FindingKind.MISSING);
		assertThat(result.findings().get(0).line()).as("finding line").isEqualTo(2);
		assertThat(result.findings().get(0).detail()).as("detail names target").contains("org.example.Nope");
	}

	@Test
	@DisplayName("checks wildcards at package granularity")
	void checksWildcardPackages() throws Exception {
		FileCheckService check = service();
		Path file = source("com/example/Wild.java",
				"package com.example; import java.util.*; import com.nothing.*;"
						+ " public class Wild { java.util.List<String> items; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("only the absent package flagged").hasSize(1);
		assertThat(result.findings().get(0).detail()).as("detail names package").contains("com.nothing");
	}

	@Test
	@DisplayName("rejects mid-string package matches")
	void rejectsMidStringPackages() throws Exception {
		FileCheckService check = service();
		Path file = source("com/other/Partial.java",
				"package com.other; import example.*; public class Partial { }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("partial package flagged").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("missing kind").isEqualTo(FindingKind.MISSING);
	}

	@Test
	@DisplayName("answers JDK names with empty indexes")
	void answersJdkWithEmptyIndexes() throws Exception {
		JarIndexStore store = openStore(state.resolve("jdk-only"));
		LibraryIndexer indexer = jarFile -> List.of();
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of();
		FileCheckService check =
				new FileCheckService(store, indexer, resolver, new JdkIndex());
		Path clean = source("com/other/JdkOnly.java",
				"package com.other; import java.util.List; public class JdkOnly { List<String> items; }");
		Path missing = source("com/other/JdkMiss.java",
				"package com.other; import java.bogus.Nope; public class JdkMiss { Nope nope; }");

		assertThat(check.check(project, clean).findings()).as("JDK hit clean").isEmpty();
		assertThat(check.check(project, missing).findings()).as("JDK miss flagged").hasSize(1);
	}

	@Test
	@DisplayName("covers targeted JDK checks")
	void checksTargetedJdk() throws Exception {
		Files.write(project.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>targeted</artifactId><version>1</version><properties>"
						+ "<maven.compiler.release>21</maven.compiler.release></properties></project>")
						.getBytes(StandardCharsets.UTF_8));
		FileCheckService check = service();
		Path swing = source("com/other/Swing.java",
				"package com.other; import javax.swing.JButton; public class Swing { JButton button; }");
		Path miss = source("com/other/JdkMiss.java",
				"package com.other; import java.bogus.Nope; public class JdkMiss { Nope nope; }");

		assertThat(check.check(project, swing).findings()).as("javax hit clean").isEmpty();
		assertThat(check.check(project, miss).findings()).as("targeted miss flagged").hasSize(1);
	}

	@Test
	@DisplayName("treats unresolved own-package names as candidates")
	void treatsOwnPackageAsCandidate() throws Exception {
		FileCheckService check = service();
		Path file = source("com/acme/Service.java",
				"package com.acme; import com.acme.Other; public class Service { Other other; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("candidate kind").isEqualTo(FindingKind.CANDIDATE);
	}

	@Test
	@DisplayName("skips unparseable files silently")
	void skipsUnparseable() throws Exception {
		FileCheckService check = service();
		Path file = source("com/example/Broken.java",
				"package com.example; import java.util.List; public class Broken { void x( { } }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("broken file unhealthy").isFalse();
		assertThat(result.findings()).as("no findings when unhealthy").isEmpty();
	}

	@Test
	@DisplayName("resolves own and dependency wildcards silently")
	void resolvesOwnWildcards() throws Exception {
		FileCheckService check = service();
		Path file = source("com/acme/OwnWild.java",
				"package com.acme; import com.acme.*; import com.example.Widget.*; import static com.example.Widget.*;"
						+ " public class OwnWild { }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("wildcards resolved").isEmpty();
	}

	@Test
	@DisplayName("resolves a wildcard import of a type with no public members")
	void resolvesMemberlessTypeWildcard() throws Exception {
		FileCheckService check = service();
		Path file = source("com/other/Memberless.java",
				"package com.other; import com.example.Empty.*; public class Memberless { }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("wildcard resolved").isEmpty();
	}

	@Test
	@DisplayName("rejects a class file spelling in an import")
	void rejectsBinarySpellingInImport() throws Exception {
		FileCheckService check = service();
		Path file = source("com/other/Binary.java",
				"package com.other; import com.example.Outer$Inner; public class Binary { }");

		CheckResult result = check.check(project, file);

		assertThat(result.findings()).as("binary spelling rejected").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("missing kind").isEqualTo(FindingKind.MISSING);
	}

	@Test
	@DisplayName("treats bare own-package names as candidates")
	void treatsBareOwnNamesAsCandidates() throws Exception {
		FileCheckService check = service();
		source("com/acme/Service.java",
				"package com.acme; import com.acme.Other; public class Service { Other other; }");
		Path file = source("com/other/SelfRef.java",
				"package com.other; import com.acme; public class SelfRef { }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("candidate kind").isEqualTo(FindingKind.CANDIDATE);
	}

	@Test
	@DisplayName("caps suggestions at three names")
	void capsSuggestions() throws Exception {
		FileCheckService check = service();
		Path file = source("com/other/Sugg.java",
				"package com.other;\nimport org.example.Widget;\npublic class Sugg { Widget w; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("missing kind").isEqualTo(FindingKind.MISSING);
		assertThat(result.findings().get(0).suggestion()).as("type suggestions only")
				.isEqualTo("maybe: com.example.Widget");
	}

	@Test
	@DisplayName("a package that no longer exists resolves nothing")
	void deletedPackageResolvesNothing() throws Exception {
		FileCheckService check = service();
		Path widget = source("com/acme/Widget.java",
				"package com.acme; public class Widget { }");
		Path file = source("com/other/Wild.java",
				"package com.other; import com.acme.*; public class Wild { }");

		assertThat(check.check(project, file).findings())
				.as("wildcard resolved while the package exists")
				.isEmpty();

		Files.delete(widget);

		CheckResult after = check.check(project, file);
		assertThat(after.findings()).as("wildcard no longer resolves").hasSize(1);
		assertThat(after.verdicts()).as("one verdict").hasSize(1);
		assertThat(after.verdicts().get(0).resolved()).as("the import does not resolve").isFalse();
	}

	@Test
	@DisplayName("rejects files outside the project")
	void rejectsOutsideProject() throws Exception {
		FileCheckService check = service();
		Path outside = Files.createTempFile("Elsewhere", ".java");
		try {
			Files.write(outside,
					"import org.example.Nope; public class Elsewhere { Nope n; }".getBytes(StandardCharsets.UTF_8));

			assertThatThrownBy(() -> check.check(Paths.get("."), outside))
					.as("outside file rejected")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("outside the project");
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	@DisplayName("rejects a sibling directory that shares the project prefix")
	void rejectsSiblingWithSharedPrefix() throws Exception {
		FileCheckService check = service();
		Path sibling = project.resolveSibling(project.getFileName().toString() + "-sibling");
		Path file = sibling.resolve("com/example/Sibling.java");
		Files.createDirectories(file.getParent());
		Files.write(file, "package com.example; public class Sibling { }".getBytes(StandardCharsets.UTF_8));
		try {
			assertThatThrownBy(() -> check.check(project, file))
					.as("the sibling file is rejected")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("outside the project");
		} finally {
			deleteTree(sibling);
		}
	}

	@Test
	@DisplayName("rejects a file that is not there")
	void rejectsMissingFile() throws Exception {
		FileCheckService check = service();
		Path missing = project.resolve("src/main/java/com/example/Missing.java");

		assertThatThrownBy(() -> check.check(project, missing))
				.as("a missing file is an error")
				.isInstanceOf(IOException.class)
				.hasMessageContaining("does not exist");
	}

	@Test
	@DisplayName("rejects a project root that is not there")
	void rejectsMissingProjectRoot() throws Exception {
		FileCheckService check = service();
		Path root = project.resolveSibling(project.getFileName().toString() + "-absent");
		Path file = root.resolve("com/example/Absent.java");

		assertThatThrownBy(() -> check.check(root, file))
				.as("a missing project root is an error")
				.isInstanceOf(IOException.class)
				.hasMessageContaining("does not exist");
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@DisplayName("rejects a file reached through a junction inside the project")
	void rejectsFileReachedThroughJunction() throws Exception {
		Path staging = staging("file-check-junction");
		Path root = Files.createDirectories(staging.resolve("project"));
		Path outside = Files.createDirectories(staging.resolve("outside"));
		Path secret = outside.resolve("Secret.java");
		Files.write(secret, "package secret; import org.example.Nope; public class Secret { }"
				.getBytes(StandardCharsets.UTF_8));
		Path inside = root.resolve("src/main/java/com/example/Secret.java");
		Files.createDirectories(inside.getParent());
		Files.write(inside, "package com.example; import java.util.List; public class Secret { }"
				.getBytes(StandardCharsets.UTF_8));
		Path link = root.resolve("link");
		boolean survived = false;
		try {
			junction(link, outside);
			FileCheckService check = service();

			assertThatThrownBy(() -> check.check(root, link.resolve("Secret.java")))
					.as("the file reached through the junction is rejected")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("outside the project");

			assertThat(check.check(root, inside).healthy())
					.as("the same file by its real path inside the project is accepted")
					.isTrue();
			assertThat(check.check(root, inside.toAbsolutePath()).healthy())
					.as("the same file by its absolute real path is accepted")
					.isTrue();
		} finally {
			removeJunction(link);
			survived = Files.exists(secret);
			deleteTree(staging);
		}
		assertThat(survived).as("the junction target survived the cleanup").isTrue();
	}

	@Test
	@DisplayName("checks a batch with the answers of the single file path")
	void checksBatchLikeSingleFiles() throws Exception {
		FileCheckService check = service();
		Path clean = source("com/example/BatchClean.java",
				"package com.example; import java.util.List; public class BatchClean { List<String> a; }");
		Path missing = source("com/other/BatchMissing.java",
				"package com.other;\nimport org.example.Nope;\npublic class BatchMissing { Nope n; }");
		Path broken = source("com/other/BatchBroken.java",
				"package com.other; import java.util.List; public class BatchBroken { void x( { } }");
		Path file = source("com/other/BatchOther.java",
				"package com.other; import java.util.Map; public class BatchOther { Map<String,String> m; }");
		List<Path> files = List.of(clean, missing, broken, file);

		Map<Path, CheckResult> answers = check.checkAll(project, files, List.of(), Set.of());

		for (Path each : files) {
			CheckResult batch = answers.get(each);
			CheckResult single = check.checkWith(project, each, List.of(), Set.of());
			assertThat(batch.healthy()).as("health of " + each.getFileName())
					.isEqualTo(single.healthy());
			assertThat(batch.findings()).as("findings of " + each.getFileName())
					.isEqualTo(single.findings());
			assertThat(batch.verdicts()).as("verdicts of " + each.getFileName())
					.isEqualTo(single.verdicts());
		}
		assertThat(answers.get(missing).findings()).as("missing import flagged").hasSize(1);
		assertThat(answers.get(broken).findings()).as("broken file silent").isEmpty();
	}

	@Test
	@DisplayName("rejects a batch that leaves the project")
	void rejectsBatchOutsideProject() throws Exception {
		FileCheckService check = service();
		Path outside = Files.createTempFile("Elsewhere", ".java");
		try {
			Files.write(outside,
					"import org.example.Nope; public class Elsewhere { Nope n; }".getBytes(StandardCharsets.UTF_8));

			assertThatThrownBy(() -> check.checkAll(project, List.of(outside), List.of(), Set.of()))
					.as("outside file rejected")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("outside the project");
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	@DisplayName("leaves a JDK import unverified when the JDK API cannot be consulted")
	void leavesJdkImportUnverified() throws Exception {
		FileCheckService check = serviceWithoutJdk();
		Path file = source("com/example/Use.java",
				"package com.example; import java.util.List; public class Use { List<String> items; }");

		CheckResult result = check.check(project, file);

		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("unverified, not missing")
				.isEqualTo(FindingKind.CANDIDATE);
		assertThat(result.findings().get(0).detail()).as("says why")
				.contains("JDK API index is unavailable");
	}

	private FileCheckService service() throws IOException {
		return serviceWith(new JdkIndex());
	}

	private FileCheckService serviceWithoutJdk() throws IOException {
		return serviceWith(new JdkIndex(null, null, List.of()));
	}

	private FileCheckService serviceWith(JdkIndex jdk) throws IOException {
		Path jar = project.resolve("dep.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
		}
		FakeIndexer indexer = new FakeIndexer();
		JarIndexStore store = openStore(state.resolve("index"));
		DependencyResolver resolver = new DependencyResolver() {
			@Override
			public List<Path> resolve(Path projectDir, boolean allowNetwork) {
				return List.of(jar);
			}
		};
		return new FileCheckService(store, indexer, resolver, jdk);
	}

	private Path source(String relative, String content) throws IOException {
		Path file = project.resolve("src/main/java").resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	/**
	 * Creates a directory junction. A junction needs no privilege, and
	 * the JDK resolves one to its target, so a path spelled inside the
	 * project can name a file outside it.
	 *
	 * @param link   junction to create, never null
	 * @param target directory the junction points at, never null
	 * @throws IOException when the junction cannot be created
	 * @throws InterruptedException when the command is interrupted
	 */
	static void junction(Path link, Path target) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("cmd", "/d", "/c", "mklink", "/J",
				link.toString(), target.toString())
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IOException("mklink /J failed: " + output.trim());
		}
	}

	/**
	 * Removes a junction without touching its target. A recursive
	 * delete would follow the junction into the target, so the junction
	 * is removed the way Windows removes it.
	 *
	 * @param link junction to remove, never null
	 * @throws IOException when the junction cannot be removed
	 * @throws InterruptedException when the command is interrupted
	 */
	static void removeJunction(Path link) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("cmd", "/d", "/c", "rmdir", link.toString())
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IOException("rmdir failed: " + output.trim());
		}
	}

	/**
	 * The directory a junction test stages its files in. It sits under
	 * the .tmp folder of the repository, so a test that fails half way
	 * leaves its files where they can be found and removed. The target
	 * of the junction is a sibling of the project inside that folder,
	 * so even a mistaken recursive delete can only reach scratch
	 * files.
	 *
	 * @param name name of the staging directory, never null
	 * @return the staging directory, never null
	 * @throws IOException when the staging directory cannot be created
	 */
	static Path staging(String name) throws IOException {
		Path repository = Paths.get(System.getProperty("user.dir")).getParent();
		Path staging = repository.resolve(".tmp").resolve(name);
		Files.createDirectories(staging);
		return staging;
	}

	/**
	 * Removes a directory tree, if it is there.
	 *
	 * @param root tree to remove, never null
	 */
	static void deleteTree(Path root) {
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// Best effort: the staging area is disposable.
		}
	}

	/** Canned dependency symbols, no bytecode involved. */
	private static final class FakeIndexer implements LibraryIndexer {

		@Override
		public List<Symbol> index(Path jar) {
			return List.of(
					new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false),
					new Symbol("com.example.Outer.Inner", SymbolKind.CLASS, null, null, false, "", false),
					new Symbol("com.example.Empty", SymbolKind.CLASS, null, null, false, "", false),
					new Symbol("com.example.Widget.build", SymbolKind.METHOD, "()V",
							"com.example.Widget", false, "", false),
					new Symbol("com.example.Widget.help", SymbolKind.METHOD, "()V",
							"com.example.Widget", false, "", false),
					new Symbol("com.example.Widget.version", SymbolKind.FIELD, "Ljava/lang/String;",
							"com.example.Widget", false, "", false));
		}
	}
}

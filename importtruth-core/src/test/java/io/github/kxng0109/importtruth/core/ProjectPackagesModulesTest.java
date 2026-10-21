package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.LibraryIndexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the own packages of a multi module project: every module
 * contributes its packages, so an import of a sibling module's class is
 * a candidate and never a miss, and the memo still sees a module that
 * was added or removed.
 */
@DisplayName("Own packages of a multi module project")
final class ProjectPackagesModulesTest {

	@TempDir
	private Path state;

	private Path project;

	private JarIndexStore store;

	@BeforeEach
	void createProject() throws Exception {
		store = new JarIndexStore(state.resolve("index"));
		project = staging("multimodule-" + UUID.randomUUID());
		Files.write(project.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>root</artifactId><version>1</version><packaging>pom</packaging>"
						+ "<modules><module>alpha</module><module>beta</module></modules></project>")
								.getBytes(StandardCharsets.UTF_8));
		module("alpha");
		module("beta");
		source(project, "com/root/Root.java", "package com.root; public class Root { }");
		source(project.resolve("alpha"), "com/alpha/Use.java",
				"package com.alpha; import com.beta.Beta; import com.root.Root;"
						+ " import org.example.Nope; public class Use {"
						+ " public Beta beta; public Root root; public Nope nope; }");
		source(project.resolve("beta"), "com/beta/Beta.java", "package com.beta; public class Beta { }");
	}

	@AfterEach
	void removeProject() throws Exception {
		if (project != null) {
			deleteTree(project);
		}
		if (store != null) {
			store.close();
		}
	}

	@Test
	@DisplayName("covers the package of every module")
	void coversEveryModule() throws Exception {
		Set<String> own = ProjectPackages.of(project);

		assertThat(own).as("own packages").contains("com.alpha", "com.beta", "com.root");
	}

	@Test
	@DisplayName("answers the import of a sibling module as a candidate")
	void answersSiblingImportAsCandidate() throws Exception {
		Set<String> own = ProjectPackages.of(project);
		Path file = project.resolve("alpha/src/main/java/com/alpha/Use.java");

		CheckResult result = check().checkWith(project, file, List.of(), own);

		assertThat(result.findings()).as("one finding per import").hasSize(3);
		assertThat(finding(result, "com.beta.Beta").kind()).as("the sibling import is a candidate")
				.isEqualTo(FindingKind.CANDIDATE);
		assertThat(finding(result, "com.beta.Beta").detail()).as("the candidate says why")
				.contains("project-owned or generated, unverified");
		assertThat(finding(result, "com.root.Root").kind()).as("the root import is a candidate")
				.isEqualTo(FindingKind.CANDIDATE);
		assertThat(finding(result, "org.example.Nope").kind()).as("an unrelated import is missing")
				.isEqualTo(FindingKind.MISSING);
	}

	@Test
	@DisplayName("walks every module root once per project")
	void walksEveryModuleRootOnce() throws Exception {
		int before = ProjectPackages.WALKS.get();
		Set<String> first = ProjectPackages.of(project);
		int afterFirst = ProjectPackages.WALKS.get();
		Set<String> second = ProjectPackages.of(project);
		int afterSecond = ProjectPackages.WALKS.get();

		assertThat(first).as("own packages").contains("com.beta");
		assertThat(second).as("the same answer").isEqualTo(first);
		assertThat(afterFirst - before).as("the module roots are walked too")
				.isGreaterThanOrEqualTo(3);
		assertThat(afterSecond - afterFirst).as("the second call walks no more").isZero();
	}

	@Test
	@DisplayName("stops covering a module package whose sources are gone")
	void stopsCoveringVanishedModulePackage() throws Exception {
		Set<String> own = ProjectPackages.of(project);
		Path file = project.resolve("alpha/src/main/java/com/alpha/Use.java");
		assertThat(ProjectPackages.covers(own, "com.beta")).as("the module package holds sources").isTrue();
		assertThat(ProjectPackages.covers(own, "com.root")).as("the root package holds sources").isTrue();

		deleteTree(project.resolve("beta/src"));
		deleteTree(project.resolve("src"));

		assertThat(ProjectPackages.covers(own, "com.beta")).as("the module package holds no sources")
				.isFalse();
		assertThat(ProjectPackages.covers(own, "com.root")).as("the root package holds no sources")
				.isFalse();
		CheckResult result = check().checkWith(project, file, List.of(), own);
		assertThat(finding(result, "com.beta.Beta").kind()).as("the candidate survives the vanished sources")
				.isEqualTo(FindingKind.CANDIDATE);
		assertThat(finding(result, "com.root.Root").kind()).as("the root candidate survives too")
				.isEqualTo(FindingKind.CANDIDATE);
	}

	@Test
	@DisplayName("sees a module that was added and a module that was removed")
	void seesAddedAndRemovedModule() throws Exception {
		Path scan = staging("scan-" + UUID.randomUUID());
		try {
			module(scan, "alpha");
			module(scan, "beta");
			source(scan.resolve("alpha"), "com/alpha/Alpha.java",
					"package com.alpha; public class Alpha { }");
			source(scan.resolve("beta"), "com/beta/Beta.java", "package com.beta; public class Beta { }");
			Set<String> own = ProjectPackages.of(scan);
			String before = ProjectPackages.buildSignature(own);

			module(scan, "gamma");
			source(scan.resolve("gamma"), "com/gamma/Gamma.java", "package com.gamma; public class Gamma { }");
			Set<String> added = ProjectPackages.of(scan);
			String afterAdd = ProjectPackages.buildSignature(added);

			assertThat(added).as("the added module is own").contains("com.gamma");
			assertThat(afterAdd).as("the signature follows the added module").isNotEqualTo(before);

			deleteTree(scan.resolve("beta"));
			Set<String> removed = ProjectPackages.of(scan);
			String afterRemove = ProjectPackages.buildSignature(removed);

			assertThat(removed).as("the removed module is gone").doesNotContain("com.beta");
			assertThat(afterRemove).as("the signature follows the removed module").isNotEqualTo(afterAdd);
		} finally {
			deleteTree(scan);
		}
	}

	private FileCheckService check() throws IOException {
		LibraryIndexer indexer = jarFile -> List.of();
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of();
		return new FileCheckService(store, indexer, resolver, new JdkIndex());
	}

	private static Finding finding(CheckResult result, String name) {
		return result.findings().stream()
				.filter(finding -> finding.detail().contains(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no finding names " + name));
	}

	private void module(String name) throws IOException {
		module(project, name);
	}

	private static void module(Path root, String name) throws IOException {
		Path dir = root.resolve(name);
		Files.createDirectories(dir);
		Files.write(dir.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId>"
						+ "<artifactId>" + name + "</artifactId><version>1</version></project>")
								.getBytes(StandardCharsets.UTF_8));
	}

	private static void source(Path root, String relative, String content) throws IOException {
		Path file = root.resolve("src/main/java").resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * The directory a fixture is staged in. It sits under the .tmp
	 * folder of the repository, so a test that fails half way leaves its
	 * files where they can be found and removed.
	 *
	 * @param name name of the staging directory, never null
	 * @return the staging directory, never null
	 * @throws IOException when the staging directory cannot be created
	 */
	private static Path staging(String name) throws IOException {
		Path repository = Paths.get(System.getProperty("user.dir")).getParent();
		Path staging = repository.resolve(".tmp").resolve(name);
		Files.createDirectories(staging);
		return staging;
	}

	/**
	 * Removes a directory tree.
	 *
	 * @param root tree to remove, never null
	 */
	private static void deleteTree(Path root) {
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// The fixture is scratch; the next run starts from a new one.
		}
	}
}

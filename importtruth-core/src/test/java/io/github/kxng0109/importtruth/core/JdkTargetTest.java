package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies target resolution from root poms.
 */
@DisplayName("JdkTarget")
final class JdkTargetTest {

	@TempDir
	private Path projects;

	@Test
	@DisplayName("reads release, source, and plugin configuration")
	@SuppressWarnings("DataFlowIssue")
	void readsReleaseAndSource() throws Exception {
		Path release = project("release",
				"<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>r</artifactId>"
						+ "<version>1</version><properties><maven.compiler.release>21</maven.compiler.release>"
						+ "</properties></project>");
		Path source = project("source",
				"<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>s</artifactId>"
						+ "<version>1</version><properties><maven.compiler.source>1.8</maven.compiler.source>"
						+ "</properties></project>");
		Path plugin = project("plugin",
				"<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>p</artifactId>"
						+ "<version>1</version><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
						+ "<artifactId>maven-compiler-plugin</artifactId>"
						+ "<configuration><release>17</release></configuration>"
						+ "</plugin></plugins></build></project>");
		Path bare = Files.createTempDirectory(projects, "bare");

		assertThat(JdkTarget.of(release)).as("release property").hasValue(21);
		assertThat(JdkTarget.of(source)).as("legacy source").hasValue(8);
		assertThat(JdkTarget.of(plugin)).as("plugin configuration").hasValue(17);
		assertThat(JdkTarget.of(bare)).as("no pom").isEmpty();
		assertThat(JdkTarget.of(bare)).as("cached miss repeats").isEmpty();
		assertThat(JdkTarget.of(release)).as("cached repeat").hasValue(21);
		assertThatThrownBy(() -> JdkTarget.of(null)).as("null project")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("tolerates malformed and noisy poms")
	void toleratesBadPoms() throws Exception {
		Path broken = Files.createTempDirectory(projects, "broken");
		Files.write(broken.resolve("pom.xml"), "not xml <".getBytes(StandardCharsets.UTF_8));
		Path noisy = Files.createTempDirectory(projects, "noisy");
		Files.write(noisy.resolve("pom.xml"),
				("<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>n</artifactId>"
						+ "<version>1</version><properties><other>1</other>"
						+ "<maven.compiler.release>oops</maven.compiler.release>"
						+ "<maven.compiler.release>21</maven.compiler.release>"
						+ "</properties></project>").getBytes(StandardCharsets.UTF_8));

		assertThat(JdkTarget.of(broken)).as("malformed pom").isEmpty();
		assertThat(JdkTarget.of(noisy)).as("noisy properties").hasValue(21);
	}

	@Test
	@DisplayName("parses major versions leniently")
	void parsesMajors() {
		assertThat(JdkTarget.parseMajor("21")).as("plain").isEqualTo(21);
		assertThat(JdkTarget.parseMajor("  17  ")).as("trimmed").isEqualTo(17);
		assertThat(JdkTarget.parseMajor("1.8")).as("legacy").isEqualTo(8);
		assertThat(JdkTarget.parseMajor("21-ea")).as("suffixed").isEqualTo(21);
		assertThat(JdkTarget.parseMajor("oops")).as("garbage").isEqualTo(-1);
		assertThat(JdkTarget.parseMajor("")).as("blank").isEqualTo(-1);
		assertThat(JdkTarget.parseMajor(null)).as("null").isEqualTo(-1);
	}

	private Path project(String name, String pom) throws Exception {
		Path dir = Files.createTempDirectory(projects, name);
		Files.write(dir.resolve("pom.xml"), pom.getBytes(StandardCharsets.UTF_8));
		return dir;
	}
}

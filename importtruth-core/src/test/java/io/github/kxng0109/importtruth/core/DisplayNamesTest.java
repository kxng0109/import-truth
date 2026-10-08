package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies display names with and without a common root.
 */
@DisplayName("DisplayNames")
final class DisplayNamesTest {

	@Test
	@DisplayName("relativizes inside the project and falls back outside")
	@SuppressWarnings("DataFlowIssue")
	void relativizesAndFallsBack() {
		Path project = Paths.get("target", "display-proj").toAbsolutePath();
		Path inside = project.resolve("src/Main.java");

		assertThat(DisplayNames.relativizeOrFileName(project, inside))
				.as("relative display")
				.isEqualTo("src/Main.java");
		assertThat(DisplayNames.relativizeOrFileName(Paths.get("rel-proj"), inside.toAbsolutePath()))
				.as("bare file fallback")
				.isEqualTo("Main.java");
		assertThatThrownBy(() -> DisplayNames.relativizeOrFileName(null, inside)).as("null project")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> DisplayNames.relativizeOrFileName(project, null)).as("null file")
				.isInstanceOf(NullPointerException.class);
	}
}

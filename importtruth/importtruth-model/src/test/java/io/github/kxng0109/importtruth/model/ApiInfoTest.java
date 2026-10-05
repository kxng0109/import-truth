package io.github.kxng0109.importtruth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the server identity contract: happy path and rejections.
 */
@DisplayName("ApiInfo")
final class ApiInfoTest {

	@Test
	@DisplayName("carries name and version")
	void carriesNameAndVersion() {
		ApiInfo info = new ApiInfo("importtruth", "0.1.0-SNAPSHOT");

		assertThat(info.name()).as("server name").isEqualTo("importtruth");
		assertThat(info.version()).as("server version").isEqualTo("0.1.0-SNAPSHOT");
	}

	@Test
	@DisplayName("rejects null name")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullName() {
		assertThatThrownBy(() -> new ApiInfo(null, "0.1.0-SNAPSHOT"))
				.as("null name rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("rejects null version")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullVersion() {
		assertThatThrownBy(() -> new ApiInfo("importtruth", null))
				.as("null version rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("rejects blank name")
	void rejectsBlankName() {
		assertThatThrownBy(() -> new ApiInfo("  ", "0.1.0-SNAPSHOT"))
				.as("blank name rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects blank version")
	void rejectsBlankVersion() {
		assertThatThrownBy(() -> new ApiInfo("importtruth", ""))
				.as("blank version rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}
}

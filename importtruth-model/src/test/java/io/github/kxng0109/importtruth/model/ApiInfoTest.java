package io.github.kxng0109.importtruth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

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
		ApiInfo info = new ApiInfo("importtruth", projectVersion());

		assertThat(info.name()).as("server name").isEqualTo("importtruth");
		assertThat(info.version()).as("server version").isEqualTo(projectVersion());
	}

	@Test
	@DisplayName("rejects null name")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullName() {
		assertThatThrownBy(() -> new ApiInfo(null, projectVersion()))
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
		assertThatThrownBy(() -> new ApiInfo("  ", projectVersion()))
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

	private static String projectVersion() {
		Properties props = new Properties();
		try (InputStream in = ApiInfoTest.class.getResourceAsStream("/version.properties")) {
			assertThat(in).as("version resource present").isNotNull();
			props.load(in);
		} catch (IOException failure) {
			throw new AssertionError("Cannot read version", failure);
		}
		return props.getProperty("version");
	}
}

package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.ApiInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ping service identity contract.
 */
@DisplayName("PingService")
final class PingServiceTest {

	@Test
	@DisplayName("returns configured name and version")
	void returnsConfiguredNameAndVersion() {
		ApiInfo info = new PingService("importtruth", projectVersion()).ping();

		assertThat(info.name()).as("server name").isEqualTo("importtruth");
		assertThat(info.version()).as("server version").isEqualTo(projectVersion());
	}

	@Test
	@DisplayName("rejects null name")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullName() {
		assertThatThrownBy(() -> new PingService(null, projectVersion()))
				.as("null name rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("rejects blank version")
	void rejectsBlankVersion() {
		assertThatThrownBy(() -> new PingService("importtruth", ""))
				.as("blank version rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects blank name")
	void rejectsBlankName() {
		assertThatThrownBy(() -> new PingService("  ", projectVersion()))
				.as("blank name rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects null version")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullVersion() {
		assertThatThrownBy(() -> new PingService("importtruth", null))
				.as("null version rejection")
				.isInstanceOf(NullPointerException.class);
	}

	private static String projectVersion() {
		Properties props = new Properties();
		try (InputStream in = PingServiceTest.class.getResourceAsStream("/version.properties")) {
			assertThat(in).as("version resource present").isNotNull();
			props.load(in);
		} catch (IOException failure) {
			throw new AssertionError("Cannot read version", failure);
		}
		return props.getProperty("version");
	}
}

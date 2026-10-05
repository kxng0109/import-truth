package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.ApiInfo;

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
		ApiInfo info = new PingService("importtruth", "0.1.0-SNAPSHOT").ping();

		assertThat(info.name()).as("server name").isEqualTo("importtruth");
		assertThat(info.version()).as("server version").isEqualTo("0.1.0-SNAPSHOT");
	}

	@Test
	@DisplayName("rejects null name")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullName() {
		assertThatThrownBy(() -> new PingService(null, "0.1.0-SNAPSHOT"))
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
}

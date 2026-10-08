package io.github.kxng0109.importtruth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies finding rendering with and without suggestions.
 */
@DisplayName("Findings")
final class FindingsTest {

	@Test
	@DisplayName("formats lines with optional suggestions")
	@SuppressWarnings("DataFlowIssue")
	void formatsLines() {
		Finding plain = new Finding("A.java", 1, FindingKind.MISSING, "import a.B resolves nowhere", "");
		Finding hinted = new Finding("A.java", 2, FindingKind.POLICY, "renamed", "use c.D");

		assertThat(Findings.format(plain)).as("plain line")
				.isEqualTo("A.java:1 MISSING import a.B resolves nowhere");
		assertThat(Findings.format(hinted)).as("hinted line")
				.isEqualTo("A.java:2 POLICY renamed (use c.D)");
		assertThatThrownBy(() -> Findings.format(null)).as("null finding")
				.isInstanceOf(NullPointerException.class);
	}
}

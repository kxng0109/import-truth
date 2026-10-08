package io.github.kxng0109.importtruth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies shared record guards.
 */
@DisplayName("Preconditions")
final class PreconditionsTest {

	@Test
	@DisplayName("guards blank text and non-positive lines")
	@SuppressWarnings("DataFlowIssue")
	void guardsValues() {
		assertThat(Preconditions.requireNonBlank("x", "field")).as("kept value").isEqualTo("x");
		assertThat(Preconditions.requirePositive(3L, "line")).as("kept line").isEqualTo(3L);
		assertThatThrownBy(() -> Preconditions.requireNonBlank(null, "field")).as("null text")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Preconditions.requireNonBlank("  ", "field")).as("blank text")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("field");
		assertThatThrownBy(() -> Preconditions.requirePositive(0L, "line")).as("zero line")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("line");
	}
}

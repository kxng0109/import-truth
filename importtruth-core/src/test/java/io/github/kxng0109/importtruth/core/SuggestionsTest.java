package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies suggestion tiers, distances, validation, and caps.
 */
@DisplayName("Suggestions")
final class SuggestionsTest {

	@Test
	@DisplayName("orders exact, package, typo, rest")
	void ordersTiers() {
		List<String> ranked = Suggestions.rank(
				"com.example.Widget",
				List.of(
						"org.other.WidgetXtra",
						"com.example.Helper",
						"com.example.Widgte",
						"com.example.Widget",
						"com.example.Widget",
						"org.other.Zed"),
				10);

		assertThat(ranked).as("tier order deduped").containsExactly(
				"com.example.Widget",
				"com.example.Helper",
				"com.example.Widgte",
				"org.other.WidgetXtra",
				"org.other.Zed");
	}

	@Test
	@DisplayName("sorts typos by distance then name and caps output")
	void sortsTyposAndCaps() {
		List<String> ranked = Suggestions.rank(
				"com.example.Widget",
				List.of("org.a.Wodget", "org.a.Widgx", "org.a.Widget"),
				2);

		assertThat(ranked).as("distance order capped").containsExactly("org.a.Widget", "org.a.Wodget");
	}

	@Test
	@DisplayName("scores transpositions as one edit with early exit")
	void scoresDistances() {
		assertThat(Suggestions.distance("Widget", "Widgte", 2)).as("transposition").isEqualTo(1);
		assertThat(Suggestions.distance("Widget", "Widget", 2)).as("identical").isEqualTo(0);
		assertThat(Suggestions.distance("Widget", "Wid", 2)).as("deletions").isEqualTo(3);
		assertThat(Suggestions.distance("A", "completely-different", 2)).as("early exit").isEqualTo(3);
		assertThat(Suggestions.distance("", "", 2)).as("empty").isEqualTo(0);
	}

	@Test
	@DisplayName("rejects nulls and non-positive limits")
	@SuppressWarnings("DataFlowIssue")
	void rejectsBadInput() {
		assertThatThrownBy(() -> Suggestions.rank(null, List.of("a.B"), 3)).as("null want")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.rank("a.B", null, 3)).as("null candidates")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Suggestions.rank("a.B", List.of("a.B"), 0)).as("zero limit")
				.isInstanceOf(IllegalArgumentException.class);
	}
}

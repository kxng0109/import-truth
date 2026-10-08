package io.github.kxng0109.importtruth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies package predicates and name splits.
 */
@DisplayName("Packages")
final class PackagesTest {

	@Test
	@DisplayName("tests containment both ways and splits names")
	@SuppressWarnings("DataFlowIssue")
	void testsPredicates() {
		assertThat(Packages.contains("com.example", "com.example.Widget")).as("under").isTrue();
		assertThat(Packages.contains("com.example", "com.example")).as("equal").isTrue();
		assertThat(Packages.contains("com.example", "com.other.Widget")).as("outside").isFalse();
		assertThat(Packages.coveredBy("com.example", "com.example.impl")).as("covered").isTrue();
		assertThat(Packages.coveredBy("com.example", "com.other")).as("not covered").isFalse();
		assertThat(Packages.simpleName("com.example.Widget")).as("simple").isEqualTo("Widget");
		assertThat(Packages.simpleName("Widget")).as("bare").isEqualTo("Widget");
		assertThat(Packages.packageName("com.example.Widget")).as("package").isEqualTo("com.example");
		assertThat(Packages.packageName("Widget")).as("empty package").isEmpty();
		assertThatThrownBy(() -> Packages.contains(null, "a.B")).as("null container")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Packages.contains("a", null)).as("null name")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Packages.simpleName(null)).as("null fqn")
				.isInstanceOf(NullPointerException.class);
	}
}

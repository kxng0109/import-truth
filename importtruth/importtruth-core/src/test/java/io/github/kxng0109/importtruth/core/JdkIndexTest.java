package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies JDK answers: plain types, nested types, and absent names.
 */
@DisplayName("JdkIndex")
final class JdkIndexTest {

	private final JdkIndex jdk = new JdkIndex();

	@Test
	@DisplayName("finds a JDK type")
	void findsJdkType() {
		assertThat(jdk.exists("java.util.ArrayList")).as("JDK type present").isTrue();
	}

	@Test
	@DisplayName("finds a nested JDK type by dotted name")
	void findsNestedJdkType() {
		assertThat(jdk.exists("java.util.Map.Entry")).as("nested JDK type present").isTrue();
	}

	@Test
	@DisplayName("misses a name from nowhere")
	void missesAbsentName() {
		assertThat(jdk.exists("com.example.Nope")).as("absent name missing").isFalse();
	}

	@Test
	@DisplayName("reports its own version")
	void reportsVersion() {
		assertThat(jdk.jdkVersion()).as("JDK version recorded").isNotBlank();
	}
}

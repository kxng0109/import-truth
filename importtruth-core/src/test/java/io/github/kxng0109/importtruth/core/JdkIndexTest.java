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
		assertThat(jdk.runningRelease()).as("running release sane").isGreaterThanOrEqualTo(21);
	}

	@Test
	@DisplayName("answers per target release from ct.sym")
	void answersPerRelease() {
		int running = jdk.runningRelease();

		assertThat(jdk.existsIn("java.util.List", 8)).as("ancient type in 8").isTrue();
		assertThat(jdk.existsIn("java.util.List", running)).as("current type now").isTrue();
		assertThat(jdk.packageExistsIn("java.util", 8)).as("ancient package in 8").isTrue();
		if (running >= 22) {
			assertThat(jdk.existsIn("java.util.stream.Gatherers", 21))
					.as("newer type absent in 21").isFalse();
			assertThat(jdk.existsIn("java.util.stream.Gatherers", running))
					.as("newer type present now").isTrue();
		}
		assertThat(jdk.existsIn("com.example.Nope", 8)).as("absent everywhere").isFalse();
		assertThat(jdk.existsIn("java.util.List", running + 1))
				.as("future release falls back").isEqualTo(jdk.exists("java.util.List"));
		assertThat(jdk.existsIn("java.util.Map.Entry", 8)).as("nested type in 8").isTrue();
		assertThat(jdk.existsIn("java.util.List", 9)).as("type in 9").isTrue();
		assertThat(jdk.existsIn("java.util.List", 7)).as("old release falls back").isTrue();
		assertThat(jdk.packageExistsIn("java.util", running + 1))
				.as("future package falls back")
				.isEqualTo(jdk.packageExists("java.util"));
	}
}

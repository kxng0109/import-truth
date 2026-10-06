package io.github.kxng0109.importtruth.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.PolicyHit;
import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;
import io.github.kxng0109.importtruth.model.PolicyRuleKind;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies rule matching: preference, retention, renames, existence gating.
 */
@DisplayName("PolicyEngine")
final class PolicyEngineTest {

	private static final PolicyPack PACK = new PolicyPack("test", List.of(
			new PolicyRule(PolicyRuleKind.PREFER, "com.fasterxml.jackson.databind", "tools.jackson.databind",
					"Jackson 3 project."),
			new PolicyRule(PolicyRuleKind.ALLOW, "com.fasterxml.jackson.annotation", "", ""),
			new PolicyRule(PolicyRuleKind.RENAME, "com.fasterxml.jackson.databind.ObjectMapper",
					"tools.jackson.databind.ObjectMapper", "")));

	private final PolicyEngine engine = new PolicyEngine(PACK);

	@Test
	@DisplayName("prefers the new package with a rebased suggestion")
	void prefersNewPackage() {
		Optional<PolicyHit> hit = engine.evaluate("com.fasterxml.jackson.databind.JsonNode", true);

		assertThat(hit).as("preference fires").isPresent();
		assertThat(hit.get().detail()).as("preference reason").isEqualTo("Jackson 3 project.");
		assertThat(hit.get().suggestion()).as("rebased suggestion")
				.isEqualTo("use tools.jackson.databind.JsonNode");
	}

	@Test
	@DisplayName("never fires on retained annotations")
	void allowsRetainedAnnotations() {
		assertThat(engine.evaluate("com.fasterxml.jackson.annotation.JsonProperty", true))
				.as("annotation allowed")
				.isEmpty();
	}

	@Test
	@DisplayName("renames exact types with member suffix preserved")
	void renamesExactTypes() {
		Optional<PolicyHit> hit = engine.evaluate("com.fasterxml.jackson.databind.ObjectMapper", true);

		assertThat(hit).as("rename fires").isPresent();
		assertThat(hit.get().suggestion()).as("rename suggestion")
				.isEqualTo("use tools.jackson.databind.ObjectMapper");
	}

	@Test
	@DisplayName("never fires on unresolved names")
	void gatesOnExistence() {
		assertThat(engine.evaluate("com.fasterxml.jackson.databind.ObjectMapper", false))
				.as("unresolved never flagged")
				.isEmpty();
	}

	@Test
	@DisplayName("ignores unrelated names")
	void ignoresUnrelated() {
		assertThat(engine.evaluate("java.util.List", true)).as("unrelated silent").isEmpty();
	}

	@Test
	@DisplayName("matches exact package preference")
	void matchesExactPackage() {
		assertThat(engine.evaluate("com.fasterxml.jackson.databind", true))
				.as("exact package fires")
				.isPresent();
	}

	@Test
	@DisplayName("preserves member suffix on renames")
	void preservesMemberSuffix() {
		assertThat(engine.evaluate("com.fasterxml.jackson.databind.ObjectMapper.Writer", true))
				.as("member rename fires")
				.hasValueSatisfying(hit -> assertThat(hit.suggestion())
						.as("suffixed suggestion")
						.isEqualTo("use tools.jackson.databind.ObjectMapper.Writer"));
	}

	@Test
	@DisplayName("rejects null targets and skips blanks")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullAndBlank() {
		assertThatThrownBy(() -> engine.evaluate(null, true))
				.as("null rejection")
				.isInstanceOf(NullPointerException.class);
		assertThat(engine.evaluate("  ", true)).as("blank silent").isEmpty();
	}
}

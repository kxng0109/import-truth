package io.github.kxng0109.importtruth.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;
import io.github.kxng0109.importtruth.model.PolicyRuleKind;
import io.github.kxng0109.importtruth.policy.PolicyValidator.RuleIssue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies self-validation: bogus targets disable rules with reasons.
 */
@DisplayName("PolicyValidator")
final class PolicyValidatorTest {

	private static final Set<String> PACKAGES = Set.of("tools.jackson.databind", "com.fasterxml.jackson.databind");
	private static final Set<String> TYPES = Set.of(
			"tools.jackson.databind.ObjectMapper", "com.fasterxml.jackson.databind.ObjectMapper");

	@Test
	@DisplayName("keeps rules whose targets resolve")
	void keepsResolvingRules() {
		PolicyPack pack = packOf(
				new PolicyRule(PolicyRuleKind.PREFER, "com.fasterxml.jackson.databind", "tools.jackson.databind", ""),
				new PolicyRule(PolicyRuleKind.RENAME, "com.fasterxml.jackson.databind.ObjectMapper",
						"tools.jackson.databind.ObjectMapper", ""));

		assertThat(PolicyValidator.validate(pack, PACKAGES::contains, TYPES::contains))
				.as("no disabled rules")
				.isEmpty();
	}

	@Test
	@DisplayName("disables rules with missing targets")
	void disablesMissingTargets() {
		PolicyPack pack = packOf(
				new PolicyRule(PolicyRuleKind.PREFER, "com.example.old", "com.example.missing", ""),
				new PolicyRule(PolicyRuleKind.RENAME, "com.example.Old", "com.example.Missing", ""));

		List<RuleIssue> disabled = PolicyValidator.validate(pack, PACKAGES::contains, TYPES::contains);

		assertThat(disabled).as("both disabled").hasSize(2);
		assertThat(disabled.stream().allMatch(i -> !i.reason().isBlank())).as("reasons given").isTrue();
	}

	private static PolicyPack packOf(PolicyRule... rules) {
		return new PolicyPack("test", List.of(rules));
	}
}

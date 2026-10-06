package io.github.kxng0109.importtruth.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	@Test
	@DisplayName("keeps allow rules and flags absent sources")
	void keepsAllowsAndFlagsSources() {
		PolicyPack pack = packOf(
				new PolicyRule(PolicyRuleKind.ALLOW, "com.example.keep", "", ""),
				new PolicyRule(PolicyRuleKind.PREFER, "com.example.gone", "tools.jackson.databind", ""),
				new PolicyRule(PolicyRuleKind.RENAME, "com.example.Gone", "tools.jackson.databind.ObjectMapper", ""));

		List<RuleIssue> disabled = PolicyValidator.validate(pack, PACKAGES::contains, TYPES::contains);

		assertThat(disabled).as("two source-absent disabled").hasSize(2);
		assertThat(PolicyValidator.activeRules(pack, PACKAGES::contains, TYPES::contains))
				.as("allow rule stays active")
				.hasSize(1);
	}

	@Test
	@DisplayName("rejects bad issues")
	void rejectsBadIssues() {
		assertThatThrownBy(() -> new RuleIssue(" ", "reason"))
				.as("blank rule rejection")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RuleIssue("rule", " "))
				.as("blank reason rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static PolicyPack packOf(PolicyRule... rules) {
		return new PolicyPack("test", List.of(rules));
	}
}

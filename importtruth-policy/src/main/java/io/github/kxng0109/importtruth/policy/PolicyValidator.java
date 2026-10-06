package io.github.kxng0109.importtruth.policy;

import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Disables rules whose targets do not resolve, so packs can never demand
 * migrations to names that do not exist.
 */
public final class PolicyValidator {

	private PolicyValidator() {
	}

	/**
	 * Checks every rule against live resolvers.
	 *
	 * @param pack             pack under test, never null
	 * @param packageResolves  true when a package exists, never null
	 * @param typeResolves     true when a type exists, never null
	 * @return disabled rules with reasons, empty when all active
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public static List<RuleIssue> validate(
			PolicyPack pack, Predicate<String> packageResolves, Predicate<String> typeResolves) {
		Objects.requireNonNull(pack, "pack");
		Objects.requireNonNull(packageResolves, "packageResolves");
		Objects.requireNonNull(typeResolves, "typeResolves");
		List<RuleIssue> disabled = new ArrayList<>();
		for (PolicyRule rule : pack.rules()) {
			check(rule, packageResolves, typeResolves).ifPresent(disabled::add);
		}
		return disabled;
	}

	private static Optional<RuleIssue> check(
			PolicyRule rule, Predicate<String> packageResolves, Predicate<String> typeResolves) {
		return switch (rule.kind()) {
			case ALLOW -> Optional.empty();
			case PREFER -> {
				if (!packageResolves.test(rule.target())) {
					yield Optional.of(new RuleIssue(describe(rule), "target package missing"));
				}
				if (!packageResolves.test(rule.subject()) && !typeResolves.test(rule.subject())) {
					yield Optional.of(new RuleIssue(describe(rule), "source absent"));
				}
				yield Optional.empty();
			}
			case RENAME -> {
				if (!typeResolves.test(rule.target())) {
					yield Optional.of(new RuleIssue(describe(rule), "target type missing"));
				}
				if (!typeResolves.test(rule.subject())) {
					yield Optional.of(new RuleIssue(describe(rule), "source absent"));
				}
				yield Optional.empty();
			}
		};
	}

	private static String describe(PolicyRule rule) {
		return rule.kind().name().toLowerCase() + " " + rule.subject() + " -> " + rule.target();
	}

	/**
	 * One disabled rule.
	 *
	 * @param rule   rule description, never blank
	 * @param reason why it is disabled, never blank
	 */
	public record RuleIssue(String rule, String reason) {

		/**
		 * Creates the issue.
		 *
		 * @throws NullPointerException if any part is {@code null}
		 * @throws IllegalArgumentException if any part is blank
		 */
		public RuleIssue {
			Objects.requireNonNull(rule, "rule");
			Objects.requireNonNull(reason, "reason");
			if (rule.isBlank() || reason.isBlank()) {
				throw new IllegalArgumentException("rule and reason must not be blank");
			}
		}
	}
}

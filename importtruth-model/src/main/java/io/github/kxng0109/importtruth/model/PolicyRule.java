package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * One migration rule.
 *
 * @param kind     rule shape, never null
 * @param subject  source package or name, never blank
 * @param target   replacement package or name, empty for allow rules
 * @param message  human reason, empty when unset
 */
public record PolicyRule(PolicyRuleKind kind, String subject, String target, String message) {

	/**
	 * Creates the rule.
	 *
	 * @throws NullPointerException if any part is {@code null}
	 * @throws IllegalArgumentException if {@code subject} is blank
	 */
	public PolicyRule {
		Objects.requireNonNull(kind, "kind");
		subject = Preconditions.requireNonBlank(subject, "subject");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(message, "message");
	}
}

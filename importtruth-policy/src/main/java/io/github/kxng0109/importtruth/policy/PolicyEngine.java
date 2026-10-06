package io.github.kxng0109.importtruth.policy;

import io.github.kxng0109.importtruth.model.PolicyHit;
import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;

import java.util.Objects;
import java.util.Optional;

/**
 * Matches resolved imports against migration rules. Never fires on names
 * that failed existence checks: callers pass only resolved imports.
 */
public final class PolicyEngine {

	private final PolicyPack pack;

	/**
	 * Creates the engine.
	 *
	 * @param pack rule pack, never null
	 * @throws NullPointerException if {@code pack} is {@code null}
	 */
	public PolicyEngine(PolicyPack pack) {
		this.pack = Objects.requireNonNull(pack, "pack");
	}

	/**
	 * Evaluates one resolved import.
	 *
	 * @param target dotted name without trailing wildcard, never null
	 * @param exists true when the name resolved somewhere
	 * @return the hit, empty when no rule fires
	 * @throws NullPointerException if {@code target} is {@code null}
	 */
	public Optional<PolicyHit> evaluate(String target, boolean exists) {
		Objects.requireNonNull(target, "target");
		if (!exists || target.isBlank()) {
			return Optional.empty();
		}
		for (PolicyRule rule : pack.rules()) {
			Optional<PolicyHit> hit = match(rule, target);
			if (hit.isPresent()) {
				return hit;
			}
		}
		return Optional.empty();
	}

	private static Optional<PolicyHit> match(PolicyRule rule, String target) {
		return switch (rule.kind()) {
			case ALLOW -> Optional.empty();
			case PREFER -> {
				if (under(rule.subject(), target)) {
					String detail = rule.message().isEmpty()
							? "prefer " + rule.target() + " over " + rule.subject()
							: rule.message();
					yield Optional.of(new PolicyHit(detail, "use " + rebased(rule.subject(), rule.target(), target)));
				}
				yield Optional.empty();
			}
			case RENAME -> {
				if (target.equals(rule.subject()) || target.startsWith(rule.subject() + ".")) {
					String replacement = rule.target() + target.substring(rule.subject().length());
					yield Optional.of(new PolicyHit("renamed to " + replacement, "use " + replacement));
				}
				yield Optional.empty();
			}
		};
	}

	private static boolean under(String pkg, String target) {
		return target.equals(pkg) || target.startsWith(pkg + ".");
	}

	private static String rebased(String from, String to, String target) {
		return to + target.substring(from.length());
	}
}

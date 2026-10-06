package io.github.kxng0109.importtruth.model;

import java.util.List;
import java.util.Objects;

/**
 * A named set of migration rules.
 *
 * @param name  pack name, never blank
 * @param rules rules in evaluation order, never null
 */
public record PolicyPack(String name, List<PolicyRule> rules) {

	/**
	 * Creates the pack.
	 *
	 * @throws NullPointerException if any part is {@code null}
	 * @throws IllegalArgumentException if {@code name} is blank
	 */
	public PolicyPack {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(rules, "rules");
		if (name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		rules = List.copyOf(rules);
	}
}

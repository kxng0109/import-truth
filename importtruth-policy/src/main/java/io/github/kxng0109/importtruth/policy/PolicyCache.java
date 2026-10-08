package io.github.kxng0109.importtruth.policy;

import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoizes validated policy engines per resolution. Validation walks
 * every rule against live indexes, so repeating it for unchanged
 * inputs is pure waste at agent-loop scale.
 */
public final class PolicyCache {

	private final Map<Key, PolicyEngine> engines = new ConcurrentHashMap<>();

	/**
	 * Returns the engine for these inputs, validating only on a miss.
	 *
	 * @param pack      pack under test, never null
	 * @param jarIndex  content key of the resolved indexes, never null
	 * @param validator validates rules, never null
	 * @return active engine, never null
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public PolicyEngine engine(
			PolicyPack pack, String jarIndex, Validator validator) {
		Objects.requireNonNull(pack, "pack");
		Objects.requireNonNull(jarIndex, "jarIndex");
		Objects.requireNonNull(validator, "validator");
		Key key = new Key(pack.name(), pack.rules(), jarIndex);
		if (engines.size() >= 128) {
			engines.clear();
		}
		return engines.computeIfAbsent(
				key, missing -> new PolicyEngine(new PolicyPack(pack.name(), validator.activeRules(pack))));
	}

	/**
	 * Validates rules against live indexes.
	 */
	public interface Validator {

		/**
		 * Returns the rules that survive validation.
		 *
		 * @param pack pack under test, never null
		 * @return active rules, never null
		 */
		List<PolicyRule> activeRules(PolicyPack pack);
	}

	private record Key(String name, List<PolicyRule> rules, String jarIndex) {
	}
}

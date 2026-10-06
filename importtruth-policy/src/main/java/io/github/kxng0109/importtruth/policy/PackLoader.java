package io.github.kxng0109.importtruth.policy;

import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;
import io.github.kxng0109.importtruth.model.PolicyRuleKind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads rule packs from YAML with a safe constructor and bounded input.
 * Policy files are untrusted input like everything else.
 */
public final class PackLoader {

	/** Largest accepted pack file. */
	static final int MAX_BYTES = 1024 * 1024;

	private PackLoader() {
	}

	/**
	 * Parses the pack.
	 *
	 * @param name pack name, never blank
	 * @param in   YAML bytes, never null
	 * @return the pack, never null
	 * @throws IOException when the input is too large or malformed
	 */
	@SuppressWarnings("unchecked")
	public static PolicyPack load(String name, InputStream in) throws IOException {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(in, "in");
		if (name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		byte[] bytes = in.readAllBytes();
		if (bytes.length > MAX_BYTES) {
			throw new IOException("Policy pack too large");
		}
		LoaderOptions options = new LoaderOptions();
		options.setCodePointLimit(bytes.length + 1024);
		options.setMaxAliasesForCollections(32);
		Object parsed = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
		if (!(parsed instanceof Map<?, ?> root)) {
			throw new IOException("Policy pack must be a mapping");
		}
		List<PolicyRule> rules = new ArrayList<>();
		rules.addAll(prefers(castList(root.get("prefer"))));
		rules.addAll(allows(castList(root.get("allow"))));
		rules.addAll(renames(castList(root.get("renames"))));
		return new PolicyPack(name, rules);
	}

	private static List<Object> castList(Object value) throws IOException {
		if (value == null) {
			return List.of();
		}
		if (value instanceof List<?> list) {
			return (List<Object>) list;
		}
		throw new IOException("Policy section must be a list");
	}

	private static List<PolicyRule> prefers(List<Object> entries) throws IOException {
		List<PolicyRule> rules = new ArrayList<>();
		for (Object entry : entries) {
			if (!(entry instanceof Map<?, ?> item)) {
				throw new IOException("prefer entry must be a mapping");
			}
			rules.add(new PolicyRule(PolicyRuleKind.PREFER, text(item, "over"), text(item, "package"), text(item, "message")));
		}
		return rules;
	}

	private static List<PolicyRule> allows(List<Object> entries) throws IOException {
		List<PolicyRule> rules = new ArrayList<>();
		for (Object entry : entries) {
			if (entry instanceof String name) {
				rules.add(new PolicyRule(PolicyRuleKind.ALLOW, name, "", ""));
			} else if (entry instanceof Map<?, ?> item) {
				rules.add(new PolicyRule(PolicyRuleKind.ALLOW, text(item, "package"), "", ""));
			} else {
				throw new IOException("allow entry must be a name or mapping");
			}
		}
		return rules;
	}

	private static List<PolicyRule> renames(List<Object> entries) throws IOException {
		List<PolicyRule> rules = new ArrayList<>();
		for (Object entry : entries) {
			if (!(entry instanceof Map<?, ?> item)) {
				throw new IOException("rename entry must be a mapping");
			}
			rules.add(new PolicyRule(PolicyRuleKind.RENAME, text(item, "from"), text(item, "to"), ""));
		}
		return rules;
	}

	private static String text(Map<?, ?> item, String key) throws IOException {
		Object value = item.get(key);
		if (value == null) {
			return "";
		}
		if (value instanceof String text) {
			return text;
		}
		throw new IOException("Policy value for " + key + " must be text");
	}
}

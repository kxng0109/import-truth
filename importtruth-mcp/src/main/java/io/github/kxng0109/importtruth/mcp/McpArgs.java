package io.github.kxng0109.importtruth.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates tool arguments in one consistent shape.
 */
final class McpArgs {

	private McpArgs() {
	}

	/**
	 * Reads a required non-blank string argument.
	 *
	 * @param arguments tool arguments, never null
	 * @param name      argument name, never null
	 * @return the value, or empty when missing, blank, or mistyped
	 */
	static Optional<String> string(Map<String, Object> arguments, String name) {
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(name, "name");
		Object value = arguments.get(name);
		if (!(value instanceof String text) || text.isBlank()
				|| text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
			return Optional.empty();
		}
		return Optional.of(text);
	}

	/**
	 * Reads a required non-empty string array argument.
	 *
	 * @param arguments tool arguments, never null
	 * @param name      argument name, never null
	 * @return the items, or empty when missing, empty, or mistyped
	 */
	static Optional<List<?>> strings(Map<String, Object> arguments, String name) {
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(name, "name");
		Object value = arguments.get(name);
		if (!(value instanceof List<?> items) || items.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(items);
	}

	/**
	 * Tests whether a batch item is a usable value. A batch item must
	 * obey the same rules as a single argument: non-blank and on one
	 * line, so a value can never forge extra lines in a result.
	 *
	 * @param item raw item, possibly null
	 * @return true when the item is a non-blank single-line string
	 */
	static boolean usableItem(Object item) {
		return item instanceof String text && !text.isBlank()
				&& text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
	}

	/**
	 * Clamps a limit argument into range, defaulting when absent.
	 *
	 * @param raw     argument value, possibly null
	 * @param fallback default when missing or mistyped
	 * @param max     upper bound, at least one
	 * @return clamped limit
	 */
	static int boundedLimit(Object raw, int fallback, int max) {
		if (raw instanceof Number number) {
			return Math.min(Math.max(number.intValue(), 1), max);
		}
		return fallback;
	}
}

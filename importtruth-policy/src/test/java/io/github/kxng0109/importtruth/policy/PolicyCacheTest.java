package io.github.kxng0109.importtruth.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;
import io.github.kxng0109.importtruth.model.PolicyRuleKind;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies engines are validated once per distinct inputs.
 */
@DisplayName("PolicyCache")
final class PolicyCacheTest {

	@Test
	@DisplayName("validates once per inputs and rejects nulls")
	@SuppressWarnings("DataFlowIssue")
	void validatesOnce() {
		PolicyCache cache = new PolicyCache();
		AtomicInteger validations = new AtomicInteger();
		PolicyCache.Validator validator = pack -> {
			validations.incrementAndGet();
			return List.copyOf(pack.rules());
		};
		PolicyPack pack = new PolicyPack("test", List.of(
				new PolicyRule(PolicyRuleKind.ALLOW, "com.example.keep", "", "")));

		PolicyEngine first = cache.engine(pack, "jars-1", validator);
		PolicyEngine second = cache.engine(pack, "jars-1", validator);
		PolicyEngine other = cache.engine(pack, "jars-2", validator);

		assertThat(second).as("cache hit returns same engine").isSameAs(first);
		assertThat(other).as("new jar key revalidates").isNotSameAs(first);
		assertThat(validations.get()).as("two validations total").isEqualTo(2);
		assertThatThrownBy(() -> cache.engine(null, "jars-1", validator)).as("null pack")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> cache.engine(pack, null, validator)).as("null key")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> cache.engine(pack, "jars-1", null)).as("null validator")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("evicts when full")
	void evictsWhenFull() {
		PolicyCache cache = new PolicyCache();
		AtomicInteger validations = new AtomicInteger();
		PolicyCache.Validator validator = pack -> {
			validations.incrementAndGet();
			return List.copyOf(pack.rules());
		};
		PolicyPack pack = new PolicyPack("test", List.of(
				new PolicyRule(PolicyRuleKind.ALLOW, "com.example.keep", "", "")));

		for (int i = 0; i < 130; i++) {
			cache.engine(pack, "jars-" + i, validator);
		}

		assertThat(validations.get()).as("every key validated").isEqualTo(130);
		cache.engine(pack, "jars-0", validator);

		assertThat(validations.get()).as("evicted key revalidated").isEqualTo(131);
	}
}

package io.github.kxng0109.importtruth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the dependency usage record: every invariant, one test each.
 */
@DisplayName("Dependency usage")
final class DependencyUsageTest {

	private static final Path JAR = Path.of("libs", "guava.jar");

	@Test
	@DisplayName("carries a direct usage")
	void carriesDirectUsage() {
		DependencyUsage usage = new DependencyUsage("guava", JAR, UsageStatus.USED_DIRECT,
				List.of("com.google.common.collect.ImmutableList"), List.of(), 120, "");

		assertThat(usage.name()).as("name").isEqualTo("guava");
		assertThat(usage.path()).as("path").isEqualTo(JAR);
		assertThat(usage.status()).as("status").isEqualTo(UsageStatus.USED_DIRECT);
		assertThat(usage.referencedClasses()).as("referenced classes")
				.containsExactly("com.google.common.collect.ImmutableList");
		assertThat(usage.via()).as("via").isEmpty();
		assertThat(usage.totalClassCount()).as("class count").isEqualTo(120);
		assertThat(usage.reason()).as("reason").isEmpty();
	}

	@Test
	@DisplayName("carries an indirect usage and the dependency that carries it")
	void carriesIndirectUsage() {
		DependencyUsage usage = new DependencyUsage("guava", JAR, UsageStatus.USED_INDIRECT,
				List.of(), List.of("com.google.gson.Gson"), 120, "");

		assertThat(usage.status()).as("status").isEqualTo(UsageStatus.USED_INDIRECT);
		assertThat(usage.via()).as("via").containsExactly("com.google.gson.Gson");
		assertThat(usage.referencedClasses()).as("referenced classes").isEmpty();
	}

	@Test
	@DisplayName("carries an unreferenced usage")
	void carriesUnreferencedUsage() {
		DependencyUsage usage = new DependencyUsage("guava", JAR, UsageStatus.UNREFERENCED,
				List.of(), List.of(), 120, "");

		assertThat(usage.status()).as("status").isEqualTo(UsageStatus.UNREFERENCED);
		assertThat(usage.via()).as("via").isEmpty();
	}

	@Test
	@DisplayName("carries an unverified usage and its reason")
	void carriesUnverifiedUsage() {
		DependencyUsage usage = new DependencyUsage("guava", JAR, UsageStatus.UNVERIFIED,
				List.of(), List.of(), 120, "class file could not be parsed");

		assertThat(usage.status()).as("status").isEqualTo(UsageStatus.UNVERIFIED);
		assertThat(usage.reason()).as("reason").isEqualTo("class file could not be parsed");
	}

	@Test
	@DisplayName("copies the reference lists so later edits cannot change the record")
	void copiesReferenceLists() {
		List<String> referenced = new ArrayList<>(List.of("com.example.Used"));
		List<String> via = new ArrayList<>(List.of("com.example.Caller"));

		DependencyUsage usage = new DependencyUsage("demo", JAR, UsageStatus.USED_INDIRECT,
				referenced, via, 3, "");
		referenced.add("com.example.Injected");
		via.add("com.example.Injected");

		assertThat(usage.referencedClasses()).as("referenced classes")
				.containsExactly("com.example.Used");
		assertThat(usage.via()).as("via").containsExactly("com.example.Caller");
	}

	@Test
	@DisplayName("rejects a null name")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullName() {
		assertThatThrownBy(() -> new DependencyUsage(null, JAR, UsageStatus.USED_DIRECT,
				List.of(), List.of(), 0, ""))
				.as("null name rejection")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("name");
	}

	@Test
	@DisplayName("rejects a null path")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullPath() {
		assertThatThrownBy(() -> new DependencyUsage("guava", null, UsageStatus.USED_DIRECT,
				List.of(), List.of(), 0, ""))
				.as("null path rejection")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("path");
	}

	@Test
	@DisplayName("rejects a null status")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullStatus() {
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, null,
				List.of(), List.of(), 0, ""))
				.as("null status rejection")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("status");
	}

	@Test
	@DisplayName("rejects null reference lists")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullLists() {
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, UsageStatus.USED_DIRECT,
				null, List.of(), 0, ""))
				.as("null referenced classes rejection")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("referencedClasses");
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, UsageStatus.USED_DIRECT,
				List.of(), null, 0, ""))
				.as("null via rejection")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("via");
	}

	@Test
	@DisplayName("rejects an indirect usage that names no carrier")
	void rejectsEmptyViaForIndirect() {
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, UsageStatus.USED_INDIRECT,
				List.of(), List.of(), 0, ""))
				.as("empty via rejection")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("via");
	}

	@Test
	@DisplayName("rejects an unverified usage that names no reason")
	void rejectsEmptyReasonForUnverified() {
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, UsageStatus.UNVERIFIED,
				List.of(), List.of(), 0, ""))
				.as("empty reason rejection")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("reason");
	}

	@Test
	@DisplayName("rejects an unverified usage that has no reason at all")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullReasonForUnverified() {
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, UsageStatus.UNVERIFIED,
				List.of(), List.of(), 0, null))
				.as("null reason rejection")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("reason");
	}

	@Test
	@DisplayName("rejects a carrier on any status but indirect")
	void rejectsViaOnOtherStatuses() {
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, UsageStatus.USED_DIRECT,
				List.of(), List.of("com.example.Caller"), 0, ""))
				.as("via on a direct usage")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("via");
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, UsageStatus.UNREFERENCED,
				List.of(), List.of("com.example.Caller"), 0, ""))
				.as("via on an unreferenced usage")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("via");
		assertThatThrownBy(() -> new DependencyUsage("guava", JAR, UsageStatus.UNVERIFIED,
				List.of(), List.of("com.example.Caller"), 0, "why not"))
				.as("via on an unverified usage")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("via");
	}
}

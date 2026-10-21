package io.github.kxng0109.importtruth.model;

import java.nio.file.Path;
import java.util.List;

/**
 * How the scanned code uses one dependency file.
 * <p>
 * The verdict rests on the bytecode, so it is a fact about the
 * references the code makes and not about what the code looks like.
 *
 * @param name              dependency name, never null
 * @param path              dependency file, never null
 * @param status            usage verdict, never null
 * @param referencedClasses binary names of the classes the code names, never null
 * @param via               dependencies that carry the reference, empty unless the status is indirect
 * @param totalClassCount   number of classes the dependency holds
 * @param reason            why the verdict holds, empty when there is nothing to explain
 */
public record DependencyUsage(
		String name,
		Path path,
		UsageStatus status,
		List<String> referencedClasses,
		List<String> via,
		int totalClassCount,
		String reason) {

	/**
	 * Creates the usage record.
	 *
	 * @throws IllegalArgumentException when an invariant is broken
	 */
	public DependencyUsage {
		name = require(name, "name");
		path = require(path, "path");
		status = require(status, "status");
		referencedClasses = requireList(referencedClasses, "referencedClasses");
		via = requireList(via, "via");
		if (status == UsageStatus.USED_INDIRECT && via.isEmpty()) {
			throw new IllegalArgumentException(
					"via must not be empty when the status is USED_INDIRECT");
		}
		if (status == UsageStatus.UNVERIFIED && (reason == null || reason.isEmpty())) {
			throw new IllegalArgumentException(
					"reason must not be empty when the status is UNVERIFIED");
		}
		if (status != UsageStatus.USED_INDIRECT && !via.isEmpty()) {
			throw new IllegalArgumentException(
					"via must be empty unless the status is USED_INDIRECT");
		}
	}

	private static <T> T require(T value, String field) {
		if (value == null) {
			throw new IllegalArgumentException(field + " must not be null");
		}
		return value;
	}

	private static List<String> requireList(List<String> value, String field) {
		if (value == null) {
			throw new IllegalArgumentException(field + " must not be null");
		}
		return List.copyOf(value);
	}
}

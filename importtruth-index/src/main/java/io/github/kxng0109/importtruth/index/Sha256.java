package io.github.kxng0109.importtruth.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Content fingerprints for library files.
 */
public final class Sha256 {

	private Sha256() {
	}

	/**
	 * Hashes the file contents.
	 *
	 * @param file file to hash, never null
	 * @return lowercase hex digest, never null
	 * @throws IOException when the file cannot be read
	 * @throws NullPointerException when {@code file} is {@code null}
	 */
	public static String ofFile(Path file) throws IOException {
		Objects.requireNonNull(file, "file");
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
		try (InputStream in = Files.newInputStream(file);
				DigestInputStream hashed = new DigestInputStream(in, digest)) {
			hashed.readAllBytes();
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}

package io.github.kxng0109.importtruth.core;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Confines a path to a project root, judged on the paths the operating
 * system really has.
 *
 * <p>A lexical comparison cannot see a link. A directory inside the
 * project can be a junction, and a junction points wherever it was made
 * to point, so a path spelled inside the project can name a file
 * outside it. The JDK resolves those links when a path is asked for its
 * real path, and it tests the reparse point attribute rather than the
 * reparse point tag, so a junction and a symbolic link are resolved
 * alike. The root is resolved once, the file is resolved once, and the
 * file lies inside the project only when the resolved file starts with
 * the resolved root.</p>
 *
 * <p>The resolved path is the answer and not only the test. The caller
 * reads the resolved path, so the file that is read is the file that
 * was checked, and a swap between the check and the read cannot
 * change that.</p>
 */
public final class PathConfinement {

	private PathConfinement() {
	}

	/**
	 * Resolves a file inside the project root and returns the path the
	 * file really has.
	 *
	 * <p>The root is resolved first, because a root that is not there
	 * cannot hold a file: such a check fails with the root missing and
	 * never passes. A path that plainly leaves the project keeps the
	 * message it has always had, so a caller that asked for a file
	 * outside still hears that the file is outside, whether or not the
	 * file is there. A file that is not there is reported as missing,
	 * never as inside.</p>
	 *
	 * @param projectDir project root, never null
	 * @param file       file to read, never null
	 * @return the resolved file, never null
	 * @throws IOException when the root or the file is missing, or when
	 *                     the file lies outside the project
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public static Path resolve(Path projectDir, Path file) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(file, "file");
		Path realRoot;
		try {
			realRoot = projectDir.toRealPath();
		} catch (NoSuchFileException missing) {
			throw missing(projectDir, file, "Project root does not exist: " + projectDir, missing);
		}
		Path realFile;
		try {
			realFile = file.toRealPath();
		} catch (NoSuchFileException missing) {
			throw missing(projectDir, file, "File does not exist: " + file, missing);
		}
		if (!realFile.startsWith(realRoot)) {
			throw new IOException("File is outside the project: " + file);
		}
		return realFile;
	}

	/**
	 * Tells whether a path lies inside the project root, judged on the
	 * resolved paths.
	 *
	 * <p>A path that cannot be resolved cannot be shown to be inside
	 * the project, so it is not. The answer is never a guess and never
	 * a pass on a failure.</p>
	 *
	 * @param projectRoot project root, never null
	 * @param candidate   path to judge, never null
	 * @return true when the resolved candidate starts with the resolved
	 *         root
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public static boolean contains(Path projectRoot, Path candidate) {
		Objects.requireNonNull(projectRoot, "projectRoot");
		Objects.requireNonNull(candidate, "candidate");
		try {
			return candidate.toRealPath().startsWith(projectRoot.toRealPath());
		} catch (IOException unreadable) {
			return false;
		}
	}

	/**
	 * Builds the error of a path that cannot be resolved. A path that
	 * plainly leaves the project is reported as outside, the way it has
	 * always been reported, and every other path is reported as the
	 * thing that is missing.
	 *
	 * @param projectDir project root, never null
	 * @param file       file to read, never null
	 * @param message    message of the thing that is missing, never null
	 * @param cause      the failure the JDK reported, never null
	 * @return the error to throw, never null
	 */
	private static IOException missing(Path projectDir, Path file, String message, IOException cause) {
		if (!spelledInside(projectDir, file)) {
			return new IOException("File is outside the project: " + file);
		}
		return new IOException(message, cause);
	}

	/**
	 * Tells whether a path is spelled inside the project root, judged on
	 * the spelling alone. The spelling cannot see a link, so it only
	 * keeps the message of a path that plainly leaves the project and
	 * never decides that a file may be read.
	 *
	 * @param projectDir project root, never null
	 * @param file       file to read, never null
	 * @return true when the spelled file starts with the spelled root
	 */
	private static boolean spelledInside(Path projectDir, Path file) {
		Path root = projectDir.toAbsolutePath().normalize();
		return file.toAbsolutePath().normalize().startsWith(root);
	}
}

package io.github.kxng0109.importtruth.cli;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.UsageService;
import io.github.kxng0109.importtruth.mcp.AnalyzeDependenciesTool;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reports which dependency jars the project's own compiled classes
 * reference.
 * <p>
 * The answer is the answer of the MCP tool, line for line: the
 * rendering is the one the tool uses, so the two can never drift
 * apart. The lines go to the findings sink, the diagnostics to the
 * diagnostics sink, and the exit code says whether the analysis
 * succeeded.
 */
public final class DepsCommand {

	private final UsageService usage;

	/**
	 * Creates the command.
	 *
	 * @param resolver dependency resolver, never null
	 * @throws NullPointerException when {@code resolver} is {@code null}
	 */
	public DepsCommand(DependencyResolver resolver) {
		this.usage = new UsageService(Objects.requireNonNull(resolver, "resolver"));
	}

	/**
	 * Runs the analysis.
	 *
	 * <p>A failed analysis answers with a diagnostic and the exit code
	 * 1. A run that could not read the code must never name a
	 * dependency as a candidate for removal, so it names none.</p>
	 *
	 * @param out         findings sink, never null
	 * @param err         diagnostics sink, never null
	 * @param projectDir  project root, never null
	 * @param classesPath directory holding the project's own compiled
	 *                    classes, or null to use the build output
	 * @return 0 when the analysis succeeded, else 1
	 * @throws NullPointerException when {@code out}, {@code err}, or
	 *                              {@code projectDir} is {@code null}
	 */
	public int run(PrintStream out, PrintStream err, Path projectDir, Path classesPath) {
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(projectDir, "projectDir");
		UsageService.Report report;
		try {
			report = usage.analyze(projectDir, classesPath);
		} catch (Exception failure) {
			err.println("analyze failed: " + failure);
			return 1;
		}
		for (String line : AnalyzeDependenciesTool.lines(report)) {
			out.println(line);
		}
		return 0;
	}
}

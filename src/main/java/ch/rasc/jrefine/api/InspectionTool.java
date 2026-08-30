package ch.rasc.jrefine.api;

/**
 * A single, independently selectable source inspection.
 *
 * <p>
 * Implementations are discovered with {@link java.util.ServiceLoader}. A tool may mutate
 * the supplied compilation unit and queue matching edits through
 * {@link InspectionContext#editor()} only when {@code applyFixes} is {@code true}.
 * </p>
 */
public interface InspectionTool {

	/** A stable command-line identifier, for example {@code remove-unused-imports}. */
	String id();

	/** A short human-readable explanation shown by {@code --list-tools}. */
	String description();

	/**
	 * Classification used by CLI profiles. Probable bugs and safe rewrites are the
	 * default.
	 */
	default ToolConfidence confidence() {
		return ToolConfidence.HIGH_CONFIDENCE;
	}

	/** Default severity; callers may override it for a project. */
	default Severity defaultSeverity() {
		return Severity.WARNING;
	}

	/**
	 * Oldest Java release that can compile every transformation produced by this tool.
	 */
	default int minimumJavaVersion() {
		return 8;
	}

	/** Inspects one source file and optionally applies every reported fix to its AST. */
	ToolResult inspect(InspectionContext context, boolean applyFixes);

}

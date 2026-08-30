package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.Severity;

import java.nio.file.Path;
import java.util.Objects;

/** A finding associated with its file and inspection tool. */
public record ReportedFinding(Path path, String toolId, Finding finding, Severity severity, boolean fixed) {

	public ReportedFinding {
		path = Objects.requireNonNull(path, "path");
		toolId = Objects.requireNonNull(toolId, "toolId");
		finding = Objects.requireNonNull(finding, "finding");
		severity = Objects.requireNonNull(severity, "severity");
	}

	public ReportedFinding(Path path, String toolId, Finding finding, boolean fixed) {
		this(path, toolId, finding, Severity.WARNING, fixed);
	}
}

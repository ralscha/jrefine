package ch.rasc.jrefine.engine;

import java.util.List;
import java.util.Objects;

/** Aggregate result of a jrefine run. */
public record ModernizationReport(int scannedFiles, int changedFiles, List<ReportedFinding> findings,
		List<ProcessingError> errors, List<ToolTiming> timings, long durationNanos) {

	public ModernizationReport {
		if (scannedFiles < 0 || changedFiles < 0 || changedFiles > scannedFiles) {
			throw new IllegalArgumentException("Invalid file counts");
		}
		findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
		errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
		timings = List.copyOf(Objects.requireNonNull(timings, "timings"));
		if (durationNanos < 0) {
			throw new IllegalArgumentException("durationNanos must not be negative");
		}
	}

	public ModernizationReport(int scannedFiles, int changedFiles, List<ReportedFinding> findings,
			List<ProcessingError> errors) {
		this(scannedFiles, changedFiles, findings, errors, List.of(), 0);
	}
}

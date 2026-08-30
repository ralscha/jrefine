package ch.rasc.jrefine.api;

import java.util.List;
import java.util.Objects;

/** The findings produced by one tool for one source file. */
public record ToolResult(List<Finding> findings, boolean changed) {

	public ToolResult {
		findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
		if (changed && findings.isEmpty()) {
			throw new IllegalArgumentException("A changed result must contain at least one finding");
		}
	}

	public static ToolResult of(List<Finding> findings, boolean applyFixes) {
		return new ToolResult(findings, applyFixes && !findings.isEmpty());
	}
}

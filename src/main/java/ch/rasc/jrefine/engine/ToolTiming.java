package ch.rasc.jrefine.engine;

import java.util.Objects;

/** Aggregate execution time and output counts for one inspection tool. */
public record ToolTiming(String toolId, long invocations, long findings, long durationNanos) {

	public ToolTiming {
		toolId = Objects.requireNonNull(toolId, "toolId");
		if (invocations < 0 || findings < 0 || durationNanos < 0) {
			throw new IllegalArgumentException("Timing counters must not be negative");
		}
	}
}

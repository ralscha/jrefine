package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.JavaVersion;
import ch.rasc.jrefine.api.Severity;

import java.util.Map;
import java.util.Objects;

/** Execution controls independent of tool selection. */
public record EngineOptions(int parallelism, boolean collectTimings, Map<String, Severity> severityOverrides,
		JavaVersion targetJava) {

	public EngineOptions {
		if (parallelism < 1) {
			throw new IllegalArgumentException("parallelism must be positive");
		}
		severityOverrides = Map.copyOf(Objects.requireNonNull(severityOverrides, "severityOverrides"));
		targetJava = Objects.requireNonNull(targetJava, "targetJava");
	}

	public EngineOptions(int parallelism, boolean collectTimings, Map<String, Severity> severityOverrides) {
		this(parallelism, collectTimings, severityOverrides, JavaVersion.latest());
	}

	public static EngineOptions defaults() {
		int processors = Runtime.getRuntime().availableProcessors();
		return new EngineOptions(Math.max(1, Math.min(16, processors)), false, Map.of(), JavaVersion.latest());
	}

	public Severity severity(InspectionTool tool) {
		return severityOverrides.getOrDefault(tool.id(), tool.defaultSeverity());
	}
}

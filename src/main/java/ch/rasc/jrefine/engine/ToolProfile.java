package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolConfidence;

import java.util.Locale;

/** Named CLI selection profiles for inspection confidence. */
public enum ToolProfile {

	HIGH_CONFIDENCE, POLICY, ALL;

	public boolean includes(InspectionTool tool) {
		return switch (this) {
			case HIGH_CONFIDENCE -> tool.confidence() == ToolConfidence.HIGH_CONFIDENCE;
			case POLICY -> tool.confidence() == ToolConfidence.POLICY;
			case ALL -> true;
		};
	}

	public static ToolProfile parse(String value) {
		if (value == null || value.isBlank()) {
			return HIGH_CONFIDENCE;
		}
		try {
			return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
					"Unknown profile '" + value + "'; expected high-confidence, policy, or all");
		}
	}

}

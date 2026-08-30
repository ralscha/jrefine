package ch.rasc.jrefine.api;

/**
 * An opt-in style, complexity, or performance policy rather than a probable correctness
 * check.
 */
public interface PolicyInspectionTool extends InspectionTool {

	@Override
	default ToolConfidence confidence() {
		return ToolConfidence.POLICY;
	}

	@Override
	default Severity defaultSeverity() {
		return Severity.INFO;
	}

}

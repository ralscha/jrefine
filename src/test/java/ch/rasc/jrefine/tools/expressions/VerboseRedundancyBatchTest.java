package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import java.util.List;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.tools.controlflow.UseArrayFillTool;
import ch.rasc.jrefine.tools.declarations.ReportDuplicateCodeTool;
import ch.rasc.jrefine.tools.declarations.ReportLombokAccessorTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ch.rasc.jrefine.api.ToolResult;

class VerboseRedundancyBatchTest {

	@Test
	void simplifiesCoveredConditionsAndSingleValueRanges() {
		String output = apply(new SimplifyCoveredConditionsTool(), """
				class Sample {
				    boolean broad(int value) { return value > 10 || value > 5; }
				    boolean narrow(int value) { return value > 5 && value > 10; }
				    boolean exact(int value) { return value >= 7 && value <= 7; }
				    boolean adjacent(int value) { return value >= 7 && value < 8; }
				}
				""");

		assertTrue(output.contains("return value > 5;"), output);
		assertTrue(output.contains("return value > 10;"), output);
		assertTrue(output.contains("return value == 7;"), output);
		assertFalse(output.contains("value >= 7"), output);
	}

	@Test
	void replacesCanonicalArrayFillLoop() {
		String output = apply(new UseArrayFillTool(), """
				class Sample {
				    void clear(String[] values) {
				        for (int i = 0; i < values.length; i++) values[i] = null;
				    }
				}
				""");

		assertTrue(output.contains("import java.util.Arrays;"), output);
		assertTrue(output.contains("Arrays.fill(values, null);"), output);
		assertFalse(output.contains("for (int i"), output);
	}

	@Test
	void removesMappingBeforeCountAndObviousNullCheck() {
		String mapped = apply(new RemoveMappingBeforeCountTool(), """
				import java.util.List;
				class Sample {
				    long count(List<String> values) {
				        return values.stream().map(String::trim).count();
				    }
				}
				""");
		assertTrue(mapped.contains("values.stream().count()"), mapped);
		assertFalse(mapped.contains("map(String::trim)"), mapped);

		String checked = apply(new SimplifyObviousNullCheckTool(), """
				import java.util.Objects;
				class Sample {
				    String value() { return Objects.requireNonNull(new String("x")); }
				}
				""");
		assertTrue(checked.contains("return new String(\"x\");"), checked);
		assertFalse(checked.contains("requireNonNull"), checked);
	}

	@Test
	void reportsLombokAccessorCandidates() {
		ToolResult result = inspect(new ReportLombokAccessorTool(), """
				import lombok.Getter;
				class Sample {
				    private int value;
				    int getValue() { return value; }
				    void setValue(int next) { this.value = next; }
				}
				""");

		assertMessages(result, "@Getter", "@Setter");
	}

	@Test
	void reportsCopiedStaticBodiesAndRepeatedExpressions() {
		ToolResult result = inspect(new ReportDuplicateCodeTool(), """
				class Sample {
				    static int increment(int value) { return value + 1; }
				    int copy(int value) { return value + 1; }
				    int calculate(int left, int right) {
				        return (left + right) * (left + right) + (left + right);
				    }
				}
				""");

		assertMessages(result, "duplicates existing static method", "same reusable expression");
	}

	private static String apply(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertTrue(result.changed(), "Expected " + tool.id() + " to change source: " + result.findings());
		String output = TestSources.print(context);
		TestSources.parse(output);
		return output;
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed());
		return result;
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

}

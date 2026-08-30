package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import java.util.List;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentAndBitwiseIssuesTest {

	@Test
	void reportsAssignmentIssueFamilies() {
		ToolResult result = inspect(new ReportAssignmentIssuesTool(), """
				import java.util.List;
				import java.util.function.Function;
				class Parent { int inherited; }
				class Sample extends Parent {
				    static int shared;
				    Sample() { inherited = 1; }
				    void method(int parameter, String text) {
				        parameter++;
				        shared = 1;
				        text = null;
				        if ((text = "value") != null) System.out.println(text);
				        int nested = (parameter = 2);
				        int used = parameter++;
				        Function<Integer, Integer> function = value -> { value++; return value; };
				        try { throw new Exception(); } catch (Exception error) { error = new Exception(); }
				    }
				    void each(List<String> values) {
				        for (String value : values) value = value.trim();
				    }
				}
				""");

		assertMessages(result, "catch block", "for-loop", "lambda parameter", "method or constructor parameter",
				"static field", "used as a condition", "superclass", "nested inside", "increment or decrement");
	}

	@Test
	void reportsBitwiseMaskAndShiftIssues() {
		ToolResult result = inspect(new ReportBitwiseOperationIssuesTool(), """
				class Sample {
				    boolean impossible(int value) { return (value & 0x0f) == 0x10; }
				    int intShift(int value) { return value << 32; }
				    long longShift(long value) { return value >>> 64; }
				    void compound(int value) { value <<= -1; }
				}
				""");

		assertMessages(result, "guaranteed to be false", "modulo 32", "modulo 64", "Compound shift");
	}

	@Test
	void doesNotAssumeUnknownFieldShiftOperandsAreInts() {
		ToolResult result = inspect(new ReportBitwiseOperationIssuesTool(), """
				class Sample {
				    private long value;
				    long hashFold() { return value ^ this.value >>> 32; }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Issue reporters must not change source");
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

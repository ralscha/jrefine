package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import java.util.List;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericIssueToolsTest {

	@Test
	void modernizesFixableBigDecimalUsageAndReportsMissingRounding() {
		InspectionContext context = TestSources.parse("""
				import java.math.BigDecimal;
				class Sample {
				    boolean same(BigDecimal left, BigDecimal right) { return left.equals(right); }
				    BigDecimal quotient(BigDecimal left, BigDecimal right) { return left.divide(right); }
				    BigDecimal scaled(BigDecimal value) { return value.setScale(2); }
				    BigDecimal fromDouble() { return new BigDecimal(0.1); }
				}
				""");
		ToolResult result = new ModernizeBigDecimalTool().inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertTrue(result.findings().size() == 2, result.findings().toString());
		assertTrue(output.contains("left.compareTo(right) == 0"), output);
		assertTrue(output.contains("BigDecimal.valueOf(0.1)"), output);
		assertTrue(output.contains("left.divide(right)"), output);
		assertTrue(output.contains("value.setScale(2)"), output);
		TestSources.parse(output);

		ToolResult check = new ModernizeBigDecimalTool().inspect(TestSources.parse("""
				import java.math.BigDecimal;
				class Sample {
				    BigDecimal divide(BigDecimal left, BigDecimal right) { return left.divide(right); }
				    BigDecimal scale(BigDecimal value) { return value.setScale(2); }
				}
				"""), false);
		assertTrue(check.findings().size() == 2, check.findings().toString());
	}

	@Test
	void simplifiesLocalNumericMistakes() {
		String output = apply(new SimplifyNumericExpressionsTool(), """
				class Sample {
				    double nan(double value) { return value == Double.NaN; }
				    boolean floatNan(double value) { return value == Float.NaN; }
				    double constant() { return Math.sin(0.0); }
				    int identities(int value) { int a = value + 0; return value * 1; }
				    int promoted(short value) { return value + 0; }
				    int overlap(int value) { return value + -0; }
				    double retainSignedZero(double value) { return value + 0.0; }
				    boolean odd(int value) { return value % 2 == 1; }
				    int signs(int value) {
				        int positive = +value;
				        int original = - -value;
				        original += -8;
				        return value + -1;
				    }
				}
				""");

		assertTrue(output.contains("Double.isNaN(value)"), output);
		assertFalse(output.contains("Float.isNaN(value)"), output);
		assertTrue(output.contains("return 0.0"), output);
		assertTrue(output.contains("int a = value"), output);
		assertTrue(output.contains("return value;"), output);
		assertTrue(output.contains("return (int) value"), output);
		assertTrue(output.contains("return value + 0.0"), output);
		assertTrue(output.contains("value % 2 != 0"), output);
		assertTrue(output.contains("int positive = value"), output);
		assertTrue(output.contains("int original = value"), output);
		assertTrue(output.contains("original -= 8"), output);
		assertTrue(output.contains("return value - 1"), output);
	}

	@Test
	void replacesPrimitiveNumberConstructors() {
		String output = apply(new ReplaceNumberConstructorTool(), """
				class Sample {
				    Integer box(int value) { return new Integer(value); }
				    Long boxLong(long value) { return new Long(value); }
				}
				""");

		assertTrue(output.contains("Integer.valueOf(value)"), output);
		assertTrue(output.contains("Long.valueOf(value)"), output);
	}

	@Test
	void reportsNumericConversionIssuesWithoutEditing() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    boolean inspect(char character, short small, int count, int other) {
				        int code = character + 1;
				        boolean mixed = small == character;
				        double widened = count;
				        double ratio = count / other;
				        long mask = 0xffffffff;
				        small += count;
				        return mixed;
				    }
				}
				""");
		ToolResult result = new ReportNumericConversionIssuesTool().inspect(context, true);
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();

		assertFalse(result.changed());
		assertTrue(messages.stream().anyMatch(message -> message.contains("'char'")), messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("short and char")), messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("int to double")), messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("Integer division")), messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("hexadecimal")), messages.toString());
		assertTrue(messages.stream()
			.anyMatch(message -> message.contains("compound assignment") || message.contains("Compound assignment")),
				messages.toString());
	}

	@Test
	void reportsFloatingPointIssuesWithoutEditing() {
		InspectionContext context = TestSources.parse("""
				class Sample { boolean inspect(double left, double right) {
				    boolean exact = left == right;
				    double invalid = left / 0.0;
				    double platform = Math.sin(left);
				    return exact;
				} }
				""");
		ToolResult result = new ReportFloatingPointIssuesTool().inspect(context, true);
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();

		assertFalse(result.changed());
		assertTrue(messages.stream().anyMatch(message -> message.contains("zero")), messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("exact equality")), messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("reproducible")), messages.toString());
	}

	@Test
	void reportsConstantOverflowAndComplexArithmetic() {
		InspectionContext overflow = TestSources.parse("""
				class Sample { int value = 1_000_000 * 1_000_000; float huge = 1e30F * 1e30F; }
				""");
		ToolResult overflowResult = new ReportNumericOverflowTool().inspect(overflow, true);
		assertFalse(overflowResult.changed());
		assertTrue(overflowResult.findings().size() >= 2, overflowResult.findings().toString());

		InspectionContext complex = TestSources.parse("""
				class Sample { int sum(int a, int b, int c, int d, int e, int f) {
				    return a + b + c + d + e + f;
				} }
				""");
		ToolResult complexResult = new ReportComplexArithmeticExpressionTool().inspect(complex, true);
		assertFalse(complexResult.changed());
		assertTrue(complexResult.findings().size() == 1, complexResult.findings().toString());
	}

	private static String apply(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertTrue(result.changed(), "Expected " + tool.id() + " to change the source");
		String output = TestSources.print(context);
		TestSources.parse(output);
		return output;
	}

}

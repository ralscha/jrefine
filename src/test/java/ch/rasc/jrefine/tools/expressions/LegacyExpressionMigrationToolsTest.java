package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyExpressionMigrationToolsTest {

	@Test
	void replacesThreeWayComparisonWithCompareMethod() {
		String output = apply(new UseNumericCompareTool(), """
				class Sample { int compare(int left, int right) {
				    return left > right ? 1 : left < right ? -1 : 0;
				} }
				""");

		assertTrue(output.contains("Integer.compare(left, right)"), output);
	}

	@Test
	void replacesBigDecimalLegacyRoundingConstants() {
		String output = apply(new ReplaceBigDecimalLegacyRoundingTool(), """
				import java.math.BigDecimal;
				class Sample { BigDecimal scale(BigDecimal value) {
				    return value.setScale(2, BigDecimal.ROUND_FLOOR);
				} }
				""");

		assertTrue(output.contains("import java.math.RoundingMode;"), output);
		assertTrue(output.contains("RoundingMode.FLOOR"), output);
		assertFalse(output.contains("BigDecimal.ROUND_FLOOR"), output);
	}

	@Test
	void replacesNullSafeEqualityIdioms() {
		String output = apply(new UseObjectsEqualsTool(), """
				class Sample { boolean same(Object left, Object right) {
				    return left == null ? right == null : left.equals(right);
				} }
				""");

		assertTrue(output.contains("import java.util.Objects;"), output);
		assertTrue(output.contains("Objects.equals(left, right)"), output);

		InspectionContext guardedContext = TestSources.parse("""
				class Sample { boolean same(Object left, Object right) {
				    return left != null && left.equals(right);
				} }
				""");
		ToolResult guardedResult = new UseObjectsEqualsTool().inspect(guardedContext, true);
		assertFalse(guardedResult.changed());
	}

	@Test
	void replacesLegacyImmutableListCreation() {
		String output = apply(new UseCollectionFactoryTool(), """
				import java.util.ArrayList;
				import java.util.Arrays;
				import java.util.Collections;
				import java.util.List;
				class Sample {
				    List<String> constants = Collections.unmodifiableList(Arrays.asList("a", "b"));
				    List<String> copy(List<String> input) {
				        return Collections.unmodifiableList(new ArrayList<>(input));
				    }
				}
				""");

		assertTrue(output.contains("List.of(\"a\", \"b\")"), output);
		assertTrue(output.contains("List.copyOf(input)"), output);
	}

	@Test
	void usesFloatAndLongLiteralSuffixes() {
		String floatOutput = apply(new UseFloatLiteralTool(), "class Sample { float value = (float) 1.25; }");
		assertTrue(floatOutput.contains("1.25F"), floatOutput);

		String longOutput = apply(new UseLongLiteralTool(), "class Sample { long value = (long) 42; }");
		assertTrue(longOutput.contains("42L"), longOutput);

		String lowercaseLongOutput = apply(new UseLongLiteralTool(), "class Sample { long value = 0x2al; }");
		assertTrue(lowercaseLongOutput.contains("0x2aL"), lowercaseLongOutput);
	}

	@Test
	void promotesIntegerOperationsBeforeLongAssignment() {
		String output = apply(new PromoteIntegerOperationToLongTool(), """
				class Sample { void calculate(int count, int value, int bits) {
				    long product = 1000 * count;
				    long shifted = value << bits;
				} }
				""");

		assertTrue(output.contains("1000L * count"), output);
		assertTrue(output.contains("(long) value << bits"), output);
	}

	@Test
	void removesOnlyImplicitlyAvailableNumericCasts() {
		String output = apply(new RemoveUnnecessaryNumericCastTool(), """
				class Sample { void convert(int value, int other) {
				    long widened = (long) value;
				    int same = (int) other;
				} }
				""");

		assertTrue(output.contains("long widened = value"), output);
		assertTrue(output.contains("int same = other"), output);
	}

	@Test
	void reportsLossyNumericCastsWithoutClaimingAnAutomaticFix() {
		InspectionContext context = TestSources.parse("""
				class Sample { int narrow(double value) { return (int) value; } }
				""");
		ToolResult result = new ReportLossyNumericCastTool().inspect(context, true);

		assertTrue(result.findings().size() == 1);
		assertFalse(result.changed());
		assertTrue(TestSources.print(context).contains("(int) value"));
	}

	@Test
	void acceptsMaskedByteExtractionAndCharArrayRoundTrips() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    byte low(long value) { return (byte) (value & 0xff); }
				    String copy(String input) {
				        StringBuilder result = new StringBuilder();
				        for (int character : input.toCharArray()) {
				            result.append((char) character);
				        }
				        return result.toString();
				    }
				}
				""");

		ToolResult result = new ReportLossyNumericCastTool().inspect(context, true);

		assertTrue(result.findings().isEmpty(), result.findings().toString());
		assertFalse(result.changed());
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

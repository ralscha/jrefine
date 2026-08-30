package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ch.rasc.jrefine.api.InspectionContext;

class AdditionalVerboseExpressionToolsTest {

	@Test
	void removesReplacementCallsWhoseSearchCannotMatch() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    String literal() { return "hello".replace("z", "x"); }
				    String regex() { return "hello".replaceAll("z+", "x"); }
				}
				""");
		ToolResult result = new RemoveNoEffectStringReplacementTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size());
		assertEquals(2, occurrences(output, "return \"hello\";"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsEffectiveInvalidOrEvaluationSensitiveReplacements() {
		InspectionContext effective = TestSources.parse("""
				class Sample { String value() { return "hello".replace("ell", "x"); } }
				""");
		InspectionContext invalid = TestSources.parse("""
				class Sample { String value() { return "hello".replaceAll("[", "x"); } }
				""");
		InspectionContext dynamicReplacement = TestSources.parse("""
				class Sample { String value(String replacement) {
				    return "hello".replace("z", replacement);
				} }
				""");

		assertFalse(new RemoveNoEffectStringReplacementTool().inspect(effective, true).changed());
		assertFalse(new RemoveNoEffectStringReplacementTool().inspect(invalid, true).changed());
		assertFalse(new RemoveNoEffectStringReplacementTool().inspect(dynamicReplacement, true).changed());
	}

	@Test
	void removesApostropheEscapesButKeepsRequiredEscapes() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    String quote = "\\'Scare\\' quotes";
				    String required = "\\\"quoted\\\" and \\\\ slash";
				}
				""");
		ToolResult result = new RemoveUnnecessaryStringEscapeTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("String quote = \"'Scare' quotes\";"), output);
		assertTrue(output.contains("String required = \"\\\"quoted\\\" and \\\\ slash\";"), output);
		TestSources.parse(output);
	}

	@Test
	void narrowsStableLocalTypeAndRemovesAllCasts() {
		InspectionContext context = TestSources.parse("""
				class Sample { int measure() {
				    Object value = " text ";
				    return ((String) value).trim().length() + ((String) value).length();
				} }
				""");
		ToolResult result = new NarrowVariableTypeTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("String value = \" text \";"), output);
		assertFalse(output.contains("(String) value"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsWeakTypeWhenVariableHasAnUncastUseOrReassignment() {
		InspectionContext uncast = TestSources.parse("""
				class Sample {
				    void accept(Object value) {}
				    void run() { Object value = "text"; accept(value); ((String) value).trim(); }
				}
				""");
		InspectionContext reassigned = TestSources.parse("""
				class Sample { void run() {
				    Object value = "text";
				    value = new Object();
				    ((String) value).trim();
				} }
				""");

		assertFalse(new NarrowVariableTypeTool().inspect(uncast, true).changed());
		assertFalse(new NarrowVariableTypeTool().inspect(reassigned, true).changed());
	}

	@Test
	void replacesGroupingMaxCollectorWithToMap() {
		InspectionContext context = TestSources.parse("""
				import java.util.Optional;
				import java.util.stream.Collectors;
				class Sample { Object collector() {
				    return Collectors.groupingBy(String::length,
				            Collectors.collectingAndThen(
				                    Collectors.maxBy(String::compareTo), Optional::get));
				} }
				""");
		String output = apply(new SimplifyCollectorTool(), context);

		assertTrue(output.contains("Collectors.toMap(String::length"), output);
		assertTrue(output.contains("Function.identity()"), output);
		assertTrue(output.contains("BinaryOperator.maxBy(String::compareTo)"), output);
		assertTrue(output.contains("import java.util.function.Function;"), output);
		assertTrue(output.contains("import java.util.function.BinaryOperator;"), output);
	}

	@Test
	void keepsCollectorWithNonGettingFinisher() {
		InspectionContext context = TestSources.parse("""
				import java.util.Optional;
				import java.util.stream.Collectors;
				class Sample { Object collector() {
				    return Collectors.groupingBy(String::length,
				            Collectors.collectingAndThen(
				                    Collectors.maxBy(String::compareTo), Optional::orElseThrow));
				} }
				""");

		assertFalse(new SimplifyCollectorTool().inspect(context, true).changed());
	}

	@Test
	void simplifiesCanonicalStreamTerminalChains() {
		InspectionContext context = TestSources.parse("""
				import java.util.List;
				import java.util.stream.Collectors;
				import java.util.stream.Stream;
				class Sample {
				    long count(Stream<String> values) {
				        return values.collect(Collectors.counting());
				    }
				    int length(Stream<String> values) {
				        return values.collect(Collectors.summingInt(String::length));
				    }
				    boolean blank(Stream<String> values) {
				        return values.filter(String::isBlank).findFirst().isPresent();
				    }
				    Object[] array(List<String> values) { return values.stream().toArray(); }
				}
				""");
		ToolResult result = new SimplifyStreamCallChainTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(4, result.findings().size());
		assertTrue(output.contains("values.count()"), output);
		assertTrue(output.contains("values.mapToInt(String::length).sum()"), output);
		assertTrue(output.contains("values.anyMatch(String::isBlank)"), output);
		assertTrue(output.contains("return values.toArray();"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsLookalikeCollectorCalls() {
		InspectionContext context = TestSources.parse("""
				import java.util.stream.Stream;
				class Collectors { static Object counting() { return null; } }
				class Sample { Object count(Stream<String> values) {
				    return values.collect(Collectors.counting());
				} }
				""");

		assertFalse(new SimplifyStreamCallChainTool().inspect(context, true).changed());
	}

	private static String apply(InspectionTool tool, InspectionContext context) {
		ToolResult result = tool.inspect(context, true);
		assertTrue(result.changed(), "Expected " + tool.id() + " to change the source");
		String output = TestSources.print(context);
		TestSources.parse(output);
		return output;
	}

	private static int occurrences(String value, String needle) {
		return (value.length() - value.replace(needle, "").length()) / needle.length();
	}

}

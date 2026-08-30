package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdditionalPerformanceExpressionToolsTest {

	@Test
	void replacesPlainLiteralReplaceAllCall() {
		String output = apply(new UseStringReplaceTool(), """
				class Sample { String delimit(String value) {
				    return value.replaceAll(",", ";");
				} }
				""");

		assertTrue(output.contains("value.replace(\",\", \";\")"), output);
	}

	@Test
	void keepsRegexPatternsReplacementSyntaxAndLookalikeApis() {
		InspectionContext regex = TestSources.parse("""
				class Sample { String replace(String value) {
				    return value.replaceAll("a+", "x");
				} }
				""");
		InspectionContext replacement = TestSources.parse("""
				class Sample { String replace(String value) {
				    return value.replaceAll("a", "$1");
				} }
				""");
		InspectionContext custom = TestSources.parse("""
				class String { String replaceAll(String left, String right) { return this; } }
				class Sample { String replace(String value) { return value.replaceAll("a", "b"); } }
				""");

		assertFalse(new UseStringReplaceTool().inspect(regex, true).changed());
		assertFalse(new UseStringReplaceTool().inspect(replacement, true).changed());
		assertFalse(new UseStringReplaceTool().inspect(custom, true).changed());
	}

	@Test
	void foldsImmediateBulkAddsIntoCopyConstructors() {
		InspectionContext context = TestSources.parse("""
				import java.util.ArrayList;
				import java.util.HashMap;
				import java.util.List;
				import java.util.Map;
				class Sample {
				    void copy(List<String> source, Map<String, Integer> sourceMap) {
				        List<String> values = new ArrayList<>();
				        values.addAll(source);
				        Map<String, Integer> valuesByName = new HashMap<>();
				        valuesByName.putAll(sourceMap);
				    }
				}
				""");
		ToolResult result = new UseCollectionCopyConstructorTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size());
		assertTrue(output.contains("new ArrayList<>(source)"), output);
		assertTrue(output.contains("new HashMap<>(sourceMap)"), output);
		assertFalse(output.contains("values.addAll"), output);
		assertFalse(output.contains("valuesByName.putAll"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsNonImmediateSelfCopyAndComparatorSensitiveCollections() {
		InspectionContext context = TestSources.parse("""
				import java.util.ArrayList;
				import java.util.List;
				import java.util.TreeSet;
				class Sample { void copy(List<String> source) {
				    List<String> delayed = new ArrayList<>();
				    System.out.println();
				    delayed.addAll(source);
				    List<String> self = new ArrayList<>();
				    self.addAll(self);
				    TreeSet<String> sorted = new TreeSet<>();
				    sorted.addAll(source);
				} }
				""");

		assertFalse(new UseCollectionCopyConstructorTool().inspect(context, true).changed());
	}

	@Test
	void removesFormatCallWithoutFormattingWork() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    String plain() { return String.format("message"); }
				    String percent() { return String.format("100%% ready"); }
				}
				""");
		ToolResult result = new SimplifyStringFormatTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size());
		assertTrue(output.contains("return \"message\";"), output);
		assertTrue(output.contains("return \"100% ready\";"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsRealFormatDirectivesAndShadowingStringTypes() {
		InspectionContext directive = TestSources.parse("""
				class Sample { String label(String value) { return String.format("value=%s", value); } }
				""");
		InspectionContext custom = TestSources.parse("""
				class String { static String format(String value) { return new String(); } }
				class Sample { String label() { return String.format("message"); } }
				""");

		assertFalse(new SimplifyStringFormatTool().inspect(directive, true).changed());
		assertFalse(new SimplifyStringFormatTool().inspect(custom, true).changed());
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

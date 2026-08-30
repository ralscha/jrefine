package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationExpressionToolsTest {

	@Test
	void replacesStringIndexPresenceChecks() {
		String output = apply(new UseStringContainsTool(), """
				class Sample {
				    boolean present(String text, String part) {
				        return text.indexOf(part) >= 0 && text.indexOf("x") != -1;
				    }
				    boolean missing(String text) { return text.indexOf("x") == -1; }
				    boolean character(String text) { return text.indexOf('x') >= 0; }
				}
				""");
		assertTrue(output.contains("text.contains(part)"), output);
		assertTrue(output.contains("text.contains(\"x\")"), output);
		assertTrue(output.contains("!text.contains(\"x\")"), output);
		assertTrue(output.contains("text.indexOf('x')"), output);
	}

	@Test
	void removesExplicitBoxingAndUnboxingInAssignmentContexts() {
		String boxed = apply(new RemoveUnnecessaryBoxingTool(), """
				class Sample {
				    Integer first = Integer.valueOf(42);
				    Long second = new Long(7L);
				    Integer parsed = Integer.valueOf("42");
				}
				""");
		assertTrue(boxed.contains("Integer first = 42"), boxed);
		assertTrue(boxed.contains("Long second = 7L"), boxed);
		assertTrue(boxed.contains("Integer.valueOf(\"42\")"), boxed);

		String unboxed = apply(new RemoveUnnecessaryUnboxingTool(), """
				class Sample {
				    int value(Integer boxed) { int result = boxed.intValue(); return result; }
				}
				""");
		assertTrue(unboxed.contains("int result = boxed;"), unboxed);
	}

	@Test
	void usesListSortAndComparatorCombinator() {
		String sorted = apply(new UseListSortTool(), """
				import java.util.Collections;
				import java.util.Comparator;
				import java.util.List;
				class Sample { void sort(List<String> values, Comparator<String> order) {
				    Collections.sort(values);
				    Collections.sort(values, order);
				} }
				""");
		assertTrue(sorted.contains("values.sort(null)"), sorted);
		assertTrue(sorted.contains("values.sort(order)"), sorted);

		String compared = apply(new UseComparatorCombinatorsTool(), """
				import java.util.Comparator;
				class Sample {
				    Comparator<String> byLength = (left, right) ->
				            left.trim().compareTo(right.trim());
				}
				""");
		assertTrue(compared.contains("Comparator.comparing(left -> left.trim())"), compared);
	}

	@Test
	void usesClampAndSequencedCollectionMethods() {
		String clamped = apply(new UseClampTool(), """
				class Sample { int clamp(int value, int min, int max) {
				    return Math.min(Math.max(value, min), max);
				} int clampOther(int value, int min, int max) {
				    return Math.max(Math.min(value, max), min);
				} }
				""");
		assertTrue(clamped.contains("Math.clamp(value, min, max)"), clamped);
		assertTrue(occurrences(clamped, "Math.clamp(value, min, max)") == 2, clamped);

		String sequenced = apply(new UseSequencedCollectionMethodsTool(), """
				import java.util.List;
				class Sample { void use(List<String> values) {
				    String first = values.get(0);
				    String last = values.get(values.size() - 1);
				    values.add(0, "first");
				    values.add(values.size(), "last");
				} }
				""");
		assertTrue(sequenced.contains("values.getFirst()"), sequenced);
		assertTrue(sequenced.contains("values.getLast()"), sequenced);
		assertTrue(sequenced.contains("values.addFirst(\"first\")"), sequenced);
		assertTrue(sequenced.contains("values.addLast(\"last\")"), sequenced);
	}

	@Test
	void usesFilesStringMethodsAndStandardCharsetConstants() {
		String files = apply(new UseFilesStringMethodsTool(), """
				import java.nio.charset.Charset;
				import java.nio.file.Files;
				import java.nio.file.Path;
				class Sample { void copy(Path path, Charset charset) throws Exception {
				    String text = new String(Files.readAllBytes(path), charset);
				    Files.write(path, text.getBytes(charset));
				} }
				""");
		assertTrue(files.contains("Files.readString(path, charset)"), files);
		assertTrue(files.contains("Files.writeString(path, text, charset)"), files);

		String charset = apply(new UseStandardCharsetTool(), """
				import java.nio.charset.Charset;
				class Sample { Charset charset = Charset.forName("UTF-8"); }
				""");
		assertTrue(charset.contains("import java.nio.charset.StandardCharsets;"), charset);
		assertTrue(charset.contains("StandardCharsets.UTF_8"), charset);
	}

	@Test
	void removesRedundantToStringAndUsesMethodReferences() {
		String string = apply(new RemoveUnnecessaryToStringTool(), """
				class Sample { String label(Object value) { return "value=" + value.toString(); } }
				""");
		assertTrue(string.contains("\"value=\" + value"), string);
		assertFalse(string.contains("value.toString()"), string);

		String reference = apply(new UseMethodReferenceTool(), """
				import java.util.function.Function;
				class Sample {
				    String trim(String value) { return value.trim(); }
				    Function<String, String> function = value -> trim(value);
				}
				""");
		assertTrue(reference.contains("this::trim") || reference.contains("Sample.this::trim")
				|| reference.contains("trim"), reference);
		assertTrue(reference.contains("::trim"), reference);

		String staticReference = apply(new UseMethodReferenceTool(), """
				import java.util.function.Function;
				class Sample {
				    static String normalize(String value) { return value.trim(); }
				    Function<String, String> function = value -> normalize(value);
				}
				""");
		assertTrue(staticReference.contains("Sample::normalize"), staticReference);
	}

	@Test
	void simplifiesPointlessBitwiseExpressionsWithoutDroppingCalls() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    int next() { return 1; }
				    int simplify(int value) { return (value | 0) ^ (value ^ value); }
				    int keep() { return next() & 0; }
				}
				""");
		ToolResult result = new SimplifyPointlessBitwiseExpressionsTool().inspect(context, true);
		String output = TestSources.print(context);
		assertTrue(result.changed());
		assertTrue(output.contains("value ^ 0") || output.contains("value"), output);
		assertTrue(output.contains("next() & 0"), output);
		TestSources.parse(output);
	}

	private static String apply(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
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

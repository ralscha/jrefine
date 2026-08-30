package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerboseExpressionToolsTest {

	@Test
	void inlinesImmediatelyQueriedLiteralElements() {
		String output = apply(new InlineOnlyUsedElementTool(), """
				class Sample {
				    int number() { return new int[]{1, 2, 3}[1]; }
				    char letter() { return "abc".charAt(1); }
				}
				""");

		assertTrue(output.contains("return 2;"), output);
		assertTrue(output.contains("return 'b';"), output);

		InspectionContext unsafe = TestSources.parse("""
				class Unsafe {
				    int next() { return 1; }
				    int number() { return new int[]{next(), 2}[1]; }
				    long widened() { return new long[]{1}[0]; }
				}
				""");
		assertFalse(new InlineOnlyUsedElementTool().inspect(unsafe, true).changed());
	}

	@Test
	void simplifiesPresenceOnlyOptionalChains() {
		String output = apply(new SimplifyOptionalCallChainTool(), """
				import java.util.Optional;
				class Sample { boolean present(Optional<String> value) {
				    return value.map(item -> true).orElse(false);
				} }
				""");
		assertTrue(output.contains("value.isPresent()"), output);

		InspectionContext custom = TestSources.parse("""
				class Sample {
				    interface Optional<T> { <R> Optional<R> map(java.util.function.Function<T, R> f); R orElse(R r); }
				    boolean present(Optional<String> value) { return value.map(item -> true).orElse(false); }
				}
				""");
		assertFalse(new SimplifyOptionalCallChainTool().inspect(custom, true).changed());
	}

	@Test
	void replacesIntegralManualMinAndMax() {
		String output = apply(new UseMathMinMaxTool(), """
				class Sample {
				    int min(int left, int right) { return left < right ? left : right; }
				    long max(long left, long right) { return left >= right ? left : right; }
				}
				""");
		assertTrue(output.contains("Math.min(left, right)"), output);
		assertTrue(output.contains("Math.max(left, right)"), output);

		InspectionContext floating = TestSources.parse("""
				class Sample { double min(double left, double right) {
				    return left < right ? left : right;
				} }
				""");
		assertFalse(new UseMathMinMaxTool().inspect(floating, true).changed());
	}

	@Test
	void removesEmptyStringsOnlyWhenConcatenationRemainsStringTyped() {
		String output = apply(new RemoveEmptyStringConcatenationTool(), """
				class Sample {
				    String label(int left, int right) { return "" + left + " ; " + right; }
				    String same(String value) { return value + ""; }
				}
				""");
		assertTrue(output.contains("return left + \" ; \" + right;"), output);
		assertTrue(output.contains("return value;"), output);

		InspectionContext conversion = TestSources.parse("""
				class Sample { String value(int number) { return "" + number; } }
				""");
		assertFalse(new RemoveEmptyStringConcatenationTool().inspect(conversion, true).changed());
	}

	@Test
	void flattensNestedComparatorCombinators() {
		String output = apply(new SimplifyComparatorMethodTool(), """
				import java.util.Comparator;
				import java.util.function.Function;
				import java.util.function.ToIntFunction;
				class Sample {
				    Comparator<String> next(Comparator<String> comparator, Function<String, Integer> key) {
				        return comparator.thenComparing(Comparator.comparing(key));
				    }
				    Comparator<String> nextInt(Comparator<String> comparator, ToIntFunction<String> key) {
				        return comparator.thenComparing(Comparator.comparingInt(key));
				    }
				}
				""");
		assertTrue(output.contains("comparator.thenComparing(key)"), output);
		assertTrue(output.contains("comparator.thenComparingInt(key)"), output);

		InspectionContext custom = TestSources.parse("""
				class Sample {
				    interface Comparator<T> { Comparator<T> thenComparing(Comparator<T> other); }
				}
				""");
		assertFalse(new SimplifyComparatorMethodTool().inspect(custom, true).changed());
	}

	@Test
	void replacesRepeatedCastWithExistingVariable() {
		String output = apply(new ReplaceCastWithVariableTool(), """
				class Sample { void print(Object value) {
				    String text = (String) value;
				    System.out.println(((String) value).trim());
				} }
				""");
		assertTrue(output.contains("(text).trim()"), output);
		assertFalse(output.contains("((String) value).trim()"), output);

		InspectionContext reassigned = TestSources.parse("""
				class Sample { void print(Object value) {
				    String text = (String) value;
				    value = "changed";
				    System.out.println(((String) value).trim());
				} }
				""");
		assertFalse(new ReplaceCastWithVariableTool().inspect(reassigned, true).changed());
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

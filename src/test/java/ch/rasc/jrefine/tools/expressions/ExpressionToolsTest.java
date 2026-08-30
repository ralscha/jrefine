package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionToolsTest {

	@Test
	void usesIsEmptyOnlyForKnownJdkCollectionAndStringTypes() {
		InspectionContext context = TestSources.parse("""
				import java.util.List;
				class Sample {
				    boolean unrelated(List<String> counter) { return counter.isEmpty(); }
				    boolean empty(List<String> names, String text, Counter counter) {
				        return names.size() == 0 || 0 != text.length() || counter.size() == 0;
				    }
				    interface Counter { int size(); }
				}
				""");
		ToolResult result = new UseIsEmptyTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size());
		assertTrue(output.contains("names.isEmpty()"), output);
		assertTrue(output.contains("!text.isEmpty()"), output);
		assertTrue(output.contains("counter.size() == 0"), output);
		TestSources.parse(output);
	}

	@Test
	void doesNotAssumeAUserTypeNamedListHasIsEmpty() {
		InspectionContext context = TestSources.parse("""
				import example.List;
				class Sample {
				    boolean empty(List<String> values) { return values.size() == 0; }
				}
				""");

		ToolResult result = new UseIsEmptyTool().inspect(context, true);

		assertFalse(result.changed());
		assertTrue(TestSources.print(context).contains("values.size() == 0"));
	}

	@Test
	void usesCompoundAssignmentOnlyWithAStableRepeatedTarget() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    int value;
				    Sample next() { return this; }
				    void update(int amount) {
				        value = value + amount;
				        next().value = next().value + amount;
				    }
				}
				""");
		ToolResult result = new UseOperatorAssignmentTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("value += amount"), output);
		assertTrue(output.contains("next().value = next().value + amount"), output);
	}

	@Test
	void simplifiesBooleanConstantsNegationsAndComparisons() {
		String output = apply(new SimplifyBooleanExpressionTool(), """
				class Sample {
				    boolean first(boolean enabled) { return !!enabled; }
				    boolean second(boolean enabled) { return enabled && true; }
				    boolean third(int left, int right) { return !(left == right); }
				    boolean sideEffect() { return true; }
				    boolean preserveCall() { return sideEffect() && false; }
				}
				""");

		assertTrue(output.contains("return enabled;"), output);
		assertTrue(output.contains("return left != right;"), output);
		assertFalse(output.contains("&& true"), output);
		assertTrue(output.contains("return sideEffect() && false;"), output);
	}

	@Test
	void leavesReadableNegatedJunctionsAlone() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    boolean test(boolean first, boolean second, boolean third, boolean fourth) {
				        return !(first && second) && !(third && fourth);
				    }
				}
				""");
		ToolResult result = new SimplifyBooleanExpressionTool().inspect(context, true);
		String output = TestSources.print(context);

		assertFalse(result.changed());
		assertTrue(output.contains("return !(first && second) && !(third && fourth);"), output);
	}

	@Test
	void simplifiesBooleanConditionalsAndSingleStatementLambdas() {
		String booleans = apply(new SimplifyBooleanExpressionTool(), """
				class Sample {
				    boolean direct(boolean ready) { return ready ? true : false; }
				    boolean negated(boolean ready) { return ready ? false : true; }
				}
				""");
		String lambdas = apply(new UseExpressionLambdaTool(), """
				import java.util.function.Function;
				class Sample {
				    Function<String, Integer> size = value -> { return value.length(); };
				    Runnable run = () -> { System.out.println("run"); };
				}
				""");

		assertTrue(booleans.contains("return ready;"), booleans);
		assertTrue(booleans.contains("return !ready;"), booleans);
		assertTrue(lambdas.contains("value -> value.length()"), lambdas);
		assertTrue(lambdas.contains("() -> System.out.println(\"run\")"), lambdas);

		InspectionContext overloadContext = TestSources.parse("""
				import java.util.function.Supplier;
				class Overloaded {
				    void accept(Runnable action) {}
				    <T> T accept(Supplier<T> supplier) { return supplier.get(); }
				    int calculate() { return 1; }
				    void run() { accept(() -> { return calculate(); }); }
				}
				""");
		ToolResult overloadResult = new UseExpressionLambdaTool().inspect(overloadContext, true);
		assertFalse(overloadResult.changed());
	}

	@Test
	void movesComparisonConstantsToTheRight() {
		String output = apply(new NormalizeComparisonsTool(), """
				class Sample {
				    boolean check(int value, Object item) {
				        return 0 < value && null == item;
				    }
				}
				""");

		assertTrue(output.contains("value > 0"), output);
		assertTrue(output.contains("item == null"), output);
	}

	@Test
	void replacesEmptyStringEqualityOnlyForJavaLangString() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    boolean string(String value) { return value.equals(""); }
				    boolean sequence(CharSequence value) { return value.equals(""); }
				    boolean custom(Text value) { return value.equals(""); }
				    interface Text { boolean equals(Object other); }
				}
				""");
		ToolResult result = new UseIsEmptyTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("return value.isEmpty();"), output);
		assertEquals(2, occurrences(output, "value.equals(\"\")"), output);
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

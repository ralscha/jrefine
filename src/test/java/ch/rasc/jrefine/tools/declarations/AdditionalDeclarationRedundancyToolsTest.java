package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ch.rasc.jrefine.api.InspectionContext;

class AdditionalDeclarationRedundancyToolsTest {

	@Test
	void removesInferableLambdaParameterTypes() {
		InspectionContext context = TestSources.parse("""
				import java.util.function.BiFunction;
				class Sample {
				    BiFunction<String, String, String> join =
				            (String left, String right) -> left + right;
				}
				""");
		ToolResult result = new RemoveRedundantLambdaParameterTypesTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("(left, right) -> left + right"), output);
		assertFalse(output.contains("String left"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsAnnotatedLambdaParametersAndLocallyOverloadedTargets() {
		InspectionContext annotated = TestSources.parse("""
				import java.lang.annotation.ElementType;
				import java.lang.annotation.Target;
				import java.util.function.Consumer;
				@Target(ElementType.PARAMETER) @interface Marker {}
				class Sample { Consumer<String> action = (@Marker String value) -> {}; }
				""");
		InspectionContext overloaded = TestSources.parse("""
				interface TextAction { void accept(String value); }
				interface NumberAction { void accept(Integer value); }
				class Sample {
				    void apply(TextAction action) {}
				    void apply(NumberAction action) {}
				    void run() { apply((String value) -> {}); }
				}
				""");
		InspectionContext methodInference = TestSources.parse("""
				import java.util.Comparator;
				class Sample {
				    Comparator<String> comparator() {
				        return Comparator.comparingInt((String value) -> value.length());
				    }
				}
				""");

		assertFalse(new RemoveRedundantLambdaParameterTypesTool().inspect(annotated, true).changed());
		assertFalse(new RemoveRedundantLambdaParameterTypesTool().inspect(overloaded, true).changed());
		assertFalse(new RemoveRedundantLambdaParameterTypesTool().inspect(methodInference, true).changed());
	}

	@Test
	void removesAndCompactsCanonicalRecordConstructors() {
		InspectionContext context = TestSources.parse("""
				record Empty(int value) { Empty {} }
				record Range(int from, int to) {
				    Range(int from, int to) {
				        if (from > to) throw new IllegalArgumentException();
				        this.from = from;
				        this.to = to;
				    }
				}
				""");
		ToolResult result = new RemoveRedundantRecordConstructorTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size());
		assertFalse(output.contains("Empty {}"), output);
		assertTrue(output.contains("Range {"), output);
		assertTrue(output.contains("if (from > to)"), output);
		assertFalse(output.contains("this.from = from"), output);
		assertFalse(output.contains("this.to = to"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsRecordConstructorWithBehaviorAndBroaderAccess() {
		InspectionContext behavior = TestSources.parse("""
				record Range(int from, int to) {
				    Range { if (from > to) throw new IllegalArgumentException(); }
				}
				""");
		InspectionContext access = TestSources.parse("""
				record Value(int number) { public Value {} }
				""");

		assertFalse(new RemoveRedundantRecordConstructorTool().inspect(behavior, true).changed());
		assertFalse(new RemoveRedundantRecordConstructorTool().inspect(access, true).changed());
	}

	@Test
	void removesTriviallyUnusedPrivateThrowsClause() {
		InspectionContext context = TestSources.parse("""
				import java.io.IOException;
				class Sample {
				    private int constant() throws IOException, ReflectiveOperationException { return 1; }
				    int use() { return constant(); }
				}
				""");
		String output = apply(new RemoveRedundantThrowsTool(), context);

		assertTrue(output.contains("private int constant() { return 1; }"), output);
		assertFalse(output.contains("throws IOException"), output);
	}

	@Test
	void keepsThrowsWhenBodyCallsCodeOrCallerHasCatch() {
		InspectionContext bodyCall = TestSources.parse("""
				import java.io.IOException;
				class Sample { private void load() throws IOException { System.out.println(); } }
				""");
		InspectionContext caughtCall = TestSources.parse("""
				import java.io.IOException;
				class Sample {
				    private void load() throws IOException {}
				    void run() { try { load(); } catch (IOException exception) {} }
				}
				""");

		assertFalse(new RemoveRedundantThrowsTool().inspect(bodyCall, true).changed());
		assertFalse(new RemoveRedundantThrowsTool().inspect(caughtCall, true).changed());
	}

	@Test
	void removesIdenticalAndDirectDelegatingOverrides() {
		InspectionContext context = TestSources.parse("""
				class Base {
				    public String label() { return "same"; }
				    public int size(int value) { return value; }
				}
				class Child extends Base {
				    @Override public String label() { return "same"; }
				    @Override public int size(int value) { return super.size(value); }
				}
				""");
		ToolResult result = new RemoveRedundantMethodOverrideTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size());
		assertEquals(1, occurrences(output, "String label()"), output);
		assertEquals(1, occurrences(output, "int size(int value)"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsOverrideThatChangesContractOrCouldResolveShadowedState() {
		InspectionContext context = TestSources.parse("""
				class Base {
				    protected int value;
				    public int value() { return value; }
				    public String label() { return "same"; }
				}
				class Child extends Base {
				    private int value;
				    @Override public int value() { return value; }
				    @Deprecated @Override public String label() { return "same"; }
				}
				""");

		assertFalse(new RemoveRedundantMethodOverrideTool().inspect(context, true).changed());
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

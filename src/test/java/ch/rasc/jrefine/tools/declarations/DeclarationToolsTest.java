package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarationToolsTest {

	@Test
	void removesDefaultFieldValuesButPreservesFinalAndNonDefaultValues() {
		String output = apply(new RemoveRedundantFieldInitializationTool(), """
				class Sample {
				    int count = 0;
				    boolean ready = false;
				    Object value = null;
				    final int required = 0;
				    int retries = 1;
				    interface Contract { int implicitFinal = 0; }
				}
				""");

		assertFalse(output.contains("count = 0"), output);
		assertFalse(output.contains("ready = false"), output);
		assertFalse(output.contains("value = null"), output);
		assertTrue(output.contains("required = 0"), output);
		assertTrue(output.contains("retries = 1"), output);
		assertTrue(output.contains("implicitFinal = 0"), output);
	}

	@Test
	void joinsOnlyImmediatelyAdjacentDeclarationAndAssignment() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    void run() {
				        int joined;
				        joined = 42;
				        int separate;
				        System.out.println("gap");
				        separate = 7;
				    }
				}
				""");
		ToolResult result = new JoinDeclarationAndAssignmentTool().inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertTrue(output.contains("int joined = 42;"), output);
		assertTrue(output.contains("int separate;"), output);
		assertTrue(output.contains("separate = 7;"), output);
	}

	@Test
	void removesOnlyKnownJavaLangObjectSuperclassesAndBounds() {
		String output = apply(new RemoveRedundantObjectBoundsTool(), """
				class Sample extends Object {
				    <T extends java.lang.Object> T identity(T value) { return value; }
				}
				""");

		assertFalse(output.contains("extends Object"), output);
		assertFalse(output.contains("extends java.lang.Object"), output);

		InspectionContext custom = TestSources.parse("""
				package example;
				class Object {}
				class Sample extends Object { <T extends Object> T identity(T value) { return value; } }
				""");
		ToolResult result = new RemoveRedundantObjectBoundsTool().inspect(custom, true);
		assertFalse(result.changed());
	}

	@Test
	void removesEmptyInitializersAndOnlyPlainNoArgSuperCalls() {
		String source = """
				class Parent { Parent(int value) {} }
				class Sample extends Parent {
				    {}
				    static {}
				    Sample() { super(); }
				    Sample(int value) { super(value); }
				}
				""";
		String withoutInitializers = apply(new RemoveEmptyInitializersTool(), source);
		String output = apply(new RemoveUnnecessarySuperCallTool(), withoutInitializers);

		assertFalse(output.contains("static {}"), output);
		assertFalse(output.contains("super();"), output);
		assertTrue(output.contains("super(value);"), output);
	}

	@Test
	void removesFinalOnlyFromParametersAndLocalVariables() {
		String output = apply(new RemoveUnnecessaryFinalTool(), """
				final class Sample {
				    final int field = 1;
				    void run(final String input) {
				        final int count = input.length();
				    }
				}
				""");

		assertTrue(output.contains("final class Sample"), output);
		assertTrue(output.contains("final int field"), output);
		assertTrue(output.contains("void run(String input)"), output);
		assertTrue(output.contains("int count"), output);
	}

	@Test
	void removesLocalSelfAssignmentButKeepsFieldAndQualifiedAssignments() {
		String output = apply(new RemoveSelfAssignmentTool(), """
				class Sample {
				    int value;
				    void run(int input) {
				        input = input;
				        value = value;
				        this.value = this.value;
				    }
				    void outer(int nested) {
				        class Local {
				            volatile int nested;
				            void touch() { nested = nested; }
				        }
				    }
				}
				""");

		assertFalse(output.contains("input = input"), output);
		assertTrue(output.contains("value = value"), output);
		assertTrue(output.contains("this.value = this.value"), output);
		assertTrue(output.contains("nested = nested"), output);
	}

	@Test
	void removesOnlyAConstructorEquivalentToTheImplicitDefault() {
		String output = apply(new RemoveRedundantNoArgConstructorTool(), """
				class Removable { Removable() { super(); } }
				class KeptAccess { private KeptAccess() {} }
				class KeptOverload { KeptOverload() {} KeptOverload(int value) {} }
				""");

		assertFalse(output.contains("Removable()"), output);
		assertTrue(output.contains("private KeptAccess()"), output);
		assertTrue(output.contains("KeptOverload()"), output);
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

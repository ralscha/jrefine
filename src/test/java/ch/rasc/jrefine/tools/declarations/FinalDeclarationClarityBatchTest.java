package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalDeclarationClarityBatchTest {

	@Test
	void convertsOnlyUnambiguousPrivateArrayParametersToVarargs() {
		String output = apply(new UseVarargsParameterTool(), """
				class Sample {
				    private void process(String name, Object[] values) {}
				}
				""");

		assertTrue(output.contains("Object... values"), output);
		assertFalse(output.contains("Object[] values"), output);
	}

	@Test
	void keepsApiOverloadGenericAndMultidimensionalArrayParameters() {
		InspectionContext context = TestSources.parse("""
				class Sample<T> {
				    public void api(String[] values) {}
				    private void overloaded(String[] values) {}
				    private void overloaded(Object value) {}
				    private void matrix(String[][] values) {}
				    private void generic(T[] values) {}
				    private <U> void methodGeneric(U[] values) {}
				}
				""");

		ToolResult result = new UseVarargsParameterTool().inspect(context, true);

		assertFalse(result.changed());
		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsSerializationReplacementHooksWithUnsafeVisibility() {
		ToolResult result = inspect(new ReportSerializationContractBugsTool(), """
				import java.io.Serializable;
				class Sample implements Serializable {
				    public Object readResolve() { return this; }
				    Object writeReplace() { return this; }
				    protected Object inheritedHook() { return this; }
				}
				final class FinalSample implements Serializable {
				    private Object readResolve() { return this; }
				}
				""");

		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		assertTrue(messages.stream().anyMatch(message -> message.contains("readResolve() should")),
				messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("writeReplace() should")),
				messages.toString());
		assertTrue(messages.size() == 2, messages.toString());
	}

	@Test
	void reportsInheritedMembersThatLookLikeSurroundingMembers() {
		ToolResult result = inspect(new ReportNameShadowingIssuesTool(), """
				class Parent {
				    protected String value;
				    void refresh() {}
				}
				class Outer {
				    String value;
				    void refresh() {}
				    void create(String value) {
				        new Parent() {
				            void run() {
				                System.out.println(value);
				                refresh();
				            }
				        }.run();
				    }
				    class Inner extends Parent {
				        void run() {
				            System.out.println(value);
				            refresh();
				        }
				    }
				}
				""");

		List<String> messages = result.findings()
			.stream()
			.map(finding -> finding.message())
			.filter(message -> message.startsWith("Inherited"))
			.toList();
		assertTrue(messages.stream().filter(message -> message.contains("field access")).count() == 2,
				messages.toString());
		assertTrue(messages.stream().filter(message -> message.contains("method call")).count() == 2,
				messages.toString());
	}

	@Test
	void acceptsCurrentLocalsStaticNestingAndExplicitSuperAccess() {
		ToolResult result = inspect(new ReportNameShadowingIssuesTool(), """
				class Parent {
				    protected String value;
				    void refresh() {}
				}
				class Outer {
				    String value;
				    void refresh() {}
				    static class Inner extends Parent {
				        void run(String value) {
				            System.out.println(value);
				            super.refresh();
				        }
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		return tool.inspect(TestSources.parse(source), false);
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

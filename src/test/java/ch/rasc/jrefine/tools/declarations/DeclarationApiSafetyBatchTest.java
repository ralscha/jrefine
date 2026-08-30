package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.expressions.UseCollectionFactoryTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarationApiSafetyBatchTest {

	@Test
	void reportsRawSourceAndJdkGenericTypes() {
		ToolResult result = inspect(new ReportRawParameterizedTypesTool(), """
				import java.util.ArrayList;
				import java.util.List;
				import java.util.function.IntFunction;
				class Box<T> {}
				record Pair<T>(T value) {}
				class Sample {
				    List values;
				    Box box;
				    Pair pair;
				    ArrayList created = new ArrayList();
				    Class<?> token = List.class;
				    IntFunction<List<?>[]> arrays = List[]::new;
				}
				""");

		long rawUses = messages(result).stream().filter(message -> message.contains("Raw use")).count();
		assertTrue(rawUses == 5, messages(result).toString());
		assertMessages(result, "type 'List'", "type 'Box'", "type 'Pair'", "type 'ArrayList'");
	}

	@Test
	void acceptsParameterizedTypesUnavoidableClassTokensAndLookalikes() {
		ToolResult parameterized = inspect(new ReportRawParameterizedTypesTool(), """
				import java.util.ArrayList;
				import java.util.List;
				import java.util.function.IntFunction;
				class Box<T> {}
				class Sample {
				    List<String> values = new ArrayList<>();
				    Box<String> box = new Box<>();
				    Class<?> token = List.class;
				    IntFunction<List<?>[]> arrays = List[]::new;
				}
				""");
		assertTrue(parameterized.findings().isEmpty(), parameterized.findings().toString());

		ToolResult lookalike = inspect(new ReportRawParameterizedTypesTool(), """
				class List {}
				class Sample { List values = new List(); }
				""");
		assertTrue(lookalike.findings().isEmpty(), lookalike.findings().toString());
	}

	@Test
	void removesProtectedModifiersFromExplicitlyAndImplicitlyFinalTypes() {
		InspectionContext context = TestSources.parse("""
				final class FinalType {
				    protected int value;
				    protected FinalType() {}
				    protected void work() {}
				    protected class Nested {}
				}
				class OpenType { protected void extensionPoint() {} }
				record Value(int number) {
				    protected int doubled() { return number * 2; }
				}
				""");

		ToolResult result = new RemoveUnnecessaryModifiersTool().inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertTrue(result.findings().size() == 5, result.findings().toString());
		assertTrue(output.contains("protected void extensionPoint()"), output);
		assertFalse(output.contains("protected int value"), output);
		assertFalse(output.contains("protected FinalType"), output);
		assertFalse(output.contains("protected int doubled"), output);
		TestSources.parse(output);
	}

	@Test
	void reportsStaticInheritanceButAcceptsBehavioralInterfaces() {
		ToolResult result = inspect(new ReportInheritanceDesignIssuesTool(), """
				interface Constants { int LIMIT = 10; static int doubled() { return 20; } }
				interface MoreConstants extends Constants {}
				interface Contract { int DEFAULT = 1; void run(); }
				class StaticInheritance implements MoreConstants {}
				class Implementation implements Contract { public void run() {} }
				""");

		assertMessages(result, "only to inherit static constants");
		long findings = messages(result).stream().filter(message -> message.contains("only to inherit static")).count();
		assertTrue(findings == 1, messages(result).toString());
	}

	@Test
	void reportsOptionalJavaBeansConstructionAndAccessorConventions() {
		ToolResult result = inspect(new ReportJavaBeansPolicyIssuesTool(), """
				class Bean {
				    private String name;
				    Bean(String name) { this.name = name; }
				    public void setName(String name) { this.name = name; }
				}
				class CompleteBean {
				    private boolean active;
				    CompleteBean() {}
				    CompleteBean(boolean active) { this.active = active; }
				    public boolean isActive() { return active; }
				    public void setActive(boolean active) { this.active = active; }
				}
				class ImplicitConstructorBean {
				    private int value;
				    public int getValue() { return value; }
				    public void setValue(int value) { this.value = value; }
				}
				""");

		assertMessages(result, "no explicit no-arg constructor", "setter but no getter");
		assertTrue(result.findings().size() == 2, result.findings().toString());
	}

	@Test
	void recognizesLombokGeneratedBeanMembers() {
		ToolResult result = inspect(new ReportJavaBeansPolicyIssuesTool(), """
				import lombok.Getter;
				import lombok.NoArgsConstructor;
				@NoArgsConstructor
				class Bean {
				    @Getter private String name;
				    Bean(String name) { this.name = name; }
				    public void setName(String name) { this.name = name; }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void replacesRawEmptyCollectionFieldsWithGenericFactories() {
		InspectionContext context = TestSources.parse("""
				import java.util.Collections;
				import java.util.List;
				import java.util.Map;
				import java.util.Set;
				class Sample {
				    List<String> list = Collections.EMPTY_LIST;
				    Map<String, Integer> map = Collections.EMPTY_MAP;
				    Set<Long> set = Collections.EMPTY_SET;
				}
				""");

		ToolResult result = new UseCollectionFactoryTool().inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertTrue(output.contains("Collections.emptyList()"), output);
		assertTrue(output.contains("Collections.emptyMap()"), output);
		assertTrue(output.contains("Collections.emptySet()"), output);
		assertFalse(output.contains("EMPTY_"), output);
		TestSources.parse(output);
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Reporters must not change source");
		return result;
	}

	private static List<String> messages(ToolResult result) {
		return result.findings().stream().map(finding -> finding.message()).toList();
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = messages(result);
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

}

package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarationInheritanceBatchToolsTest {

	@Test
	void removesRedundantDeclarationElements() {
		InspectionContext context = TestSources.parse("""
				import java.io.IOException;
				@interface Flag {
				    int level() default 1;
				    String mode() default "safe";
				}
				class Sample {
				    @SafeVarargs
				    private static void names(String... names)
				            throws IOException, IOException {}
				    @Flag(level = 1, mode = "custom")
				    void run() {}
				}
				""");

		ToolResult result = new RemoveRedundantDeclarationElementsTool().inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertMessages(result, "@SafeVarargs", "duplicate exception", "declared default");
		assertFalse(output.contains("@SafeVarargs"), output);
		assertTrue(output.contains("throws IOException {}"), output);
		assertTrue(output.contains("@Flag(mode = \"custom\")"), output);
	}

	@Test
	void removesOnlyTheImplicitJavaBaseRequirement() {
		InspectionContext context = TestSources.parse("""
				module sample.module {
				    requires java.base;
				    requires java.logging;
				}
				""");

		ToolResult result = new RemoveRedundantDeclarationElementsTool().inspect(context, true);
		String output = TestSources.print(context);

		assertMessages(result, "java.base");
		assertFalse(output.contains("requires java.base"), output);
		assertTrue(output.contains("requires java.logging"), output);
	}

	@Test
	void preservesNonReifiableVarargsDistinctThrowsAndNonDefaultValues() {
		InspectionContext context = TestSources.parse("""
				import java.io.IOException;
				import java.sql.SQLException;
				import java.util.List;
				@interface Flag { int level() default 1; }
				class Safe {
				    @SafeVarargs
				    private static <T> void generic(T... values)
				            throws IOException, SQLException {}
				    @SafeVarargs
				    private static void parameterized(List<String>... values) {}
				    @Flag(level = 2) void run() {}
				}
				""");

		ToolResult result = new RemoveRedundantDeclarationElementsTool().inspect(context, true);

		assertFalse(result.changed(), result.findings().toString());
		assertTrue(TestSources.print(context).contains("@SafeVarargs"));
	}

	@Test
	void reportsSourceLocalInheritanceDesignProblems() {
		ToolResult result = inspect(new ReportInheritanceDesignIssuesTool(), """
				import java.util.ArrayList;
				@interface Marker {}
				class Concrete {}
				class Utility {
				    private Utility() {}
				    static void work() {}
				}
				class UtilityChild extends Utility {}
				class CollectionChild extends ArrayList<String> {}
				class InvalidAnnotationChild extends Marker {}
				abstract class EmptyAbstract extends Concrete {
				    public EmptyAbstract() {}
				}
				abstract class Parent {
				    private void hidden(int value) {}
				    static void conflict() {}
				    void values(String... values) {}
				    void convert(Number value) {}
				    abstract void pending();
				    void ready() {}
				}
				abstract class Child extends Parent {
				    void hidden(int value) {}
				    void conflict() {}
				    void values(String[] values) {}
				    void convert(Integer value) {}
				    abstract void pending();
				    abstract void ready();
				}
				final class FinalType {}
				class Bounds<T extends FinalType> {}
				""");

		assertMessages(result, "utility class", "Collection implementation", "annotation interface",
				"no abstract methods", "concrete source class", "Public constructor", "inaccessible private",
				"static/instance", "Non-varargs", "Parameter type prevents", "abstract ancestor", "concrete ancestor",
				"bounded by final class");
	}

	@Test
	void acceptsClearSourceLocalInheritanceRelationships() {
		ToolResult result = inspect(new ReportInheritanceDesignIssuesTool(), """
				interface Contract { void run(); }
				abstract class Base {
				    protected Base() {}
				    abstract void run();
				    void values(String... values) {}
				    void convert(Number value, int radix) {}
				}
				final class Child extends Base implements Contract {
				    @Override void run() {}
				    @Override void values(String... values) {}
				    void convert(Integer value) {}
				}
				class Generic<T extends Contract> {}
				class Ordinary {
				    Ordinary() {}
				    int value;
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsClosedPrivateHierarchiesWithNoConcreteDescendant() {
		ToolResult result = inspect(new ReportInheritanceDesignIssuesTool(), """
				class Container {
				    private abstract static class MissingBase {
				        abstract void run();
				    }
				    private abstract static class ImplementedBase {
				        abstract void run();
				    }
				    private static final class Child extends ImplementedBase {
				        @Override void run() {}
				    }
				    private interface MissingContract {
				        void first();
				        void second();
				    }
				    private interface PresentContract {
				        void first();
				        void second();
				    }
				    private static final class Implementation implements PresentContract {
				        public void first() {}
				        public void second() {}
				    }
				    @Deprecated
				    private abstract static class LegacyBase {
				        abstract void run();
				    }
				    @FunctionalInterface
				    private interface Callback {
				        void run();
				    }
				}
				""");

		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		assertTrue(messages.stream().anyMatch(message -> message.contains("abstract class has no concrete")),
				messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("interface has no concrete")),
				messages.toString());
		assertTrue(messages.stream().filter(message -> message.contains("no concrete source-local")).count() == 2,
				messages.toString());
	}

	@Test
	void reportsLexicalNameShadowing() {
		ToolResult result = inspect(new ReportNameShadowingIssuesTool(), """
				import java.util.function.Consumer;
				class Parent { int inherited; }
				class Outer extends Parent {
				    int value;
				    int inherited;
				    void local() { int value = 1; }
				    void parameter(int value) {}
				    void lambda() { Consumer<Integer> action = value -> {}; }
				    void pattern(Object input) {
				        if (input instanceof String value) {}
				    }
				    void anonymous(int local) {
				        Object object = new Object() { int local; };
				    }
				    <Outer> void typeParameter() {}
				    class Inner { int value; }
				}
				""");

		assertMessages(result, "Local variable", "Parameter shadows", "Lambda parameter", "Pattern variable",
				"Inner-class field", "Subclass field", "Anonymous-class field", "Type parameter");
	}

	@Test
	void acceptsIntentionalQualificationAndUnrelatedNames() {
		ToolResult result = inspect(new ReportNameShadowingIssuesTool(), """
				import java.util.function.Consumer;
				class Parent { int parentValue; }
				class Safe extends Parent {
				    int value;
				    Safe(int value) { this.value = value; }
				    void setValue(int value) { this.value = value; }
				    Safe(String value) { this(value.length()); }
				    void setValue(String value) { setValue(Integer.parseInt(value)); }
				    void work(Object input) {
				        int count = 1;
				        Consumer<Integer> action = item -> {};
				        if (input instanceof String text) {}
				    }
				    <T> T identity(T input) { return input; }
				    static int length(String value) { return value.length(); }
				    class Inner { int other; }
				}
				class Unrelated { int value; }
				class ExternalName extends com.example.ExternalName { int external; }
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Issue reporters must not change source");
		return result;
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

}

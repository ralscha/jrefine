package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestNgContractBugsToolTest {

	@Test
	void reportsDataProviderDeclarationAndReferenceProblems() {
		ToolResult result = inspect("""
				import java.util.List;
				import org.testng.annotations.DataProvider;
				import org.testng.annotations.Test;
				class Sample {
				    @DataProvider Object[][] rows() { return new Object[0][]; }
				    @DataProvider(name = "rows") Object[] duplicate() { return new Object[0]; }
				    @DataProvider List<String> invalid() { return List.of(); }
				    @Test(dataProvider = "missing") void missing(String value) {}
				}
				""");

		assertMessages(result, "declared more than once", "invalid return type", "'missing' cannot be resolved");
	}

	@Test
	void acceptsAllDocumentedProviderShapesAndLocalInheritance() {
		ToolResult result = inspect("""
				import java.util.Iterator;
				import org.testng.annotations.DataProvider;
				import org.testng.annotations.Factory;
				import org.testng.annotations.Test;
				class Parent {
				    @DataProvider Object[][] matrix() { return new Object[0][]; }
				    @DataProvider Object[] values() { return new Object[0]; }
				    @DataProvider String[][] strings() { return new String[0][]; }
				    @DataProvider Iterator<Object[]> rows() { return null; }
				    @DataProvider Iterator<Object> items() { return null; }
				    @Test void prepare() {}
				}
				class Sample extends Parent {
				    @Factory(dataProvider = "values") Sample(Object value) {}
				    @Test(dataProvider = "matrix", dependsOnMethods = "prep.*")
				    void test(Object value) {}
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsInvalidDependenciesAndObsoleteConfiguration() {
		ToolResult result = inspect("""
				import org.testng.annotations.Configuration;
				import org.testng.annotations.Test;
				class Sample {
				    @Configuration void setup() {}
				    /** @testng.before-test */
				    void legacySetup() {}
				    @Test void existing() {}
				    @Test(dependsOnMethods = {"existing", "misspelled", "["})
				    void test() {}
				}
				""");

		assertMessages(result, "does not resolve", "invalid pattern", "@Configuration", "Javadoc annotation");
	}

	@Test
	void reportsCheckedExpectedExceptionOnlyWhenAbsenceIsProvable() {
		ToolResult result = inspect("""
				import java.io.IOException;
				import org.testng.annotations.Test;
				class Sample {
				    @Test(expectedExceptions = IOException.class)
				    void empty() { int value = 1; }

				    @Test(expectedExceptions = IOException.class)
				    void mayThrow() { operation(); }

				    void operation() {}
				}
				""");

		assertMessages(result, "IOException", "never thrown");
		long neverThrown = messages(result).stream().filter(message -> message.contains("never thrown")).count();
		assertTrue(neverThrown == 1, messages(result).toString());
	}

	@Test
	void checksSourceLocalProviderClassesButSkipsExternalScopes() {
		ToolResult result = inspect("""
				import org.testng.annotations.DataProvider;
				import org.testng.annotations.Test;
				class Providers {
				    private Providers(String ignored) {}
				    @DataProvider Object[][] rows() { return new Object[0][]; }
				}
				class Sample extends ExternalBase {
				    @Test(dataProvider = "rows", dataProviderClass = Providers.class)
				    void inaccessible(Object value) {}

				    @Test(dataProvider = "external", dataProviderClass = ExternalProviders.class)
				    void external(Object value) {}

				    @Test(dataProvider = "rows", dataProviderClass = com.external.Providers.class)
				    void qualifiedExternal(Object value) {}

				    @Test(dataProvider = "inherited") void inherited(Object value) {}
				}
				""");

		assertMessages(result, "not accessible through dataProviderClass");
		assertTrue(messages(result).size() == 1, messages(result).toString());
	}

	@Test
	void ignoresSameNamedLocalAnnotations() {
		ToolResult result = inspect("""
				@interface Test { String dataProvider() default ""; }
				@interface DataProvider { String name() default ""; }
				@interface Configuration {}
				class Sample {
				    @DataProvider(name = "rows") String rows() { return ""; }
				    @Test(dataProvider = "missing") void test() {}
				    @Configuration void setup() {}
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = new ReportTestNgContractBugsTool().inspect(context, true);
		assertFalse(result.changed(), "Issue reporters must not change source");
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

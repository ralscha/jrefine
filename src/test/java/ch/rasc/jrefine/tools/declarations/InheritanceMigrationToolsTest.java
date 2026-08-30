package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ch.rasc.jrefine.analysis.LineEndingSupport.LINE_FEED;

class InheritanceMigrationToolsTest {

	@Test
	void addsOverrideForLocalSupertypesAndObjectMethods() {
		String output = apply(new AddOverrideAnnotationTool(), """
				interface Contract { String label(int value); }
				class Base { public String name() { return "base"; } }
				class Child extends Base implements Contract {
				    public String name() { return "child"; }
				    public String label(int value) { return String.valueOf(value); }
				    public String toString() { return name(); }
				    public String own() { return "own"; }
				}
				""");

		assertTrue(occurrences(output, "@Override") == 3, output);
		assertFalse(output.contains("@Override" + LINE_FEED + "    public String own"), output);
	}

	@Test
	void doesNotAnnotateAnOverloadWithDifferentParameters() {
		InspectionContext context = TestSources.parse("""
				class Base { void run(int value) {} }
				class Child extends Base { void run(String value) {} }
				""");

		ToolResult result = new AddOverrideAnnotationTool().inspect(context, true);
		assertFalse(result.changed());
	}

	@Test
	void doesNotTreatQualifiedSuperclassWithSameSimpleNameAsTheOwner() {
		InspectionContext context = TestSources.parse("""
				class JPAQueryFactory extends com.querydsl.jpa.impl.JPAQueryFactory {
				    Object getEntityManager() { return null; }
				}
				""");

		ToolResult result = new AddOverrideAnnotationTool().inspect(context, true);

		assertFalse(result.changed());
	}

	@Test
	void removesInterfacesInheritedThroughClassesAndOtherInterfaces() {
		String output = apply(new RemoveRedundantInterfacesTool(), """
				interface Parent {}
				interface Child extends Parent {}
				interface Combined extends Child, Parent {}
				class Base implements Parent {}
				class Sample extends Base implements Parent {}
				class Direct implements Parent {}
				""");

		assertTrue(output.contains("interface Combined extends Child"), output);
		assertFalse(output.contains("extends Child, Parent"), output);
		assertTrue(output.contains("class Sample extends Base"), output);
		assertFalse(output.contains("class Sample extends Base implements Parent"), output);
		assertTrue(output.contains("class Direct implements Parent"), output);
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

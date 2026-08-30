package ch.rasc.jrefine.tools.types;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.NameExpr;

class ReplaceStringBuilderWithStringToolTest {

	@Test
	void replacesStraightLineBuilderAssemblyWithAString() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    String build(int index, char[] suffix) {
				        StringBuilder result = new StringBuilder();
				        result.append("i = ");
				        result.append(index).append(suffix);
				        return result.toString();
				    }
				}
				""");
		assertEquals("char[]", context.compilationUnit().findAll(Parameter.class).get(1).getType().asString());
		NameExpr suffixUse = context.compilationUnit()
			.findAll(NameExpr.class)
			.stream()
			.filter(name -> "suffix".equals(name.getNameAsString()))
			.findFirst()
			.orElseThrow();
		assertEquals(java.util.Optional.of("char[]"), ch.rasc.jrefine.analysis.TypeLookup
			.visibleTypePreservingArrays(context.compilationUnit(), suffixUse, suffixUse));
		ToolResult result = new ReplaceStringBuilderWithStringTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("String result = \"i = \" + index + String.valueOf(suffix);"), output);
		assertFalse(output.contains("result.append"), output);
		assertTrue(output.contains("return result;"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsConditionalOrEscapingBuilders() {
		InspectionContext conditional = TestSources.parse("""
				class Sample { String build(boolean enabled) {
				    StringBuilder result = new StringBuilder();
				    if (enabled) result.append("enabled");
				    return result.toString();
				} }
				""");
		InspectionContext escaping = TestSources.parse("""
				class Sample {
				    void consume(Object value) {}
				    String build() {
				        StringBuilder result = new StringBuilder();
				        result.append("value");
				        consume(result);
				        return result.toString();
				    }
				}
				""");

		assertFalse(new ReplaceStringBuilderWithStringTool().inspect(conditional, true).changed());
		assertFalse(new ReplaceStringBuilderWithStringTool().inspect(escaping, true).changed());
	}

	@Test
	void keepsUserDefinedStringBuilderTypes() {
		InspectionContext context = TestSources.parse("""
				class StringBuilder {
				    StringBuilder append(String value) { return this; }
				    public String toString() { return ""; }
				}
				class Sample { String build() {
				    StringBuilder result = new StringBuilder();
				    result.append("value");
				    return result.toString();
				} }
				""");

		assertFalse(new ReplaceStringBuilderWithStringTool().inspect(context, true).changed());
	}

}

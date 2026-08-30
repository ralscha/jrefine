package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.syntax.UseTextBlockTool;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernLanguageMigrationBatchTest {

	@Test
	void replacesMultilineLiteralConcatenationWithAnExactTextBlock() {
		String output = apply(new UseTextBlockTool(), """
				class Sample {
				    String html = "<html>\\n" + "<body>  \\n" + "</html>";
				}
				""");

		InspectionContext parsed = TestSources.parse(output);
		TextBlockLiteralExpr block = parsed.compilationUnit().findFirst(TextBlockLiteralExpr.class).orElseThrow();
		assertEquals("<html>\n<body>  \n</html>", block.asString());
		assertFalse(output.contains(" + "), output);
	}

	@Test
	void preservesEscapesAndTheFinalNewlineInTextBlocks() {
		String output = apply(new UseTextBlockTool(), """
				class Sample {
				    String value = "quote: \\\"\\\"\\\"\\n"
				            + "path: C:\\\\tmp\\n" + "done\\n";
				}
				""");

		TextBlockLiteralExpr block = TestSources.parse(output)
			.compilationUnit()
			.findFirst(TextBlockLiteralExpr.class)
			.orElseThrow();
		assertEquals("quote: \"\"\"\npath: C:\\tmp\ndone\n", block.asString());
	}

	@Test
	void keepsShortAndDynamicStringConcatenations() {
		InspectionContext shortValue = TestSources.parse("class Sample { String value = \"one\\n\" + \"two\"; }");
		InspectionContext dynamic = TestSources
			.parse("class Sample { String value(String name) { return \"hello\\n\\n\" + name; } }");

		assertFalse(new UseTextBlockTool().inspect(shortValue, true).changed());
		assertFalse(new UseTextBlockTool().inspect(dynamic, true).changed());
	}

	@Test
	void replacesEagerAndSafeLazyNullFallbacks() {
		String output = apply(new UseNullFallbackMethodTool(), """
				class Message { Message() {} }
				class Sample {
				    Object eager(Object value) {
				        return value == null ? "fallback" : value;
				    }
				    Message lazy(Message value) {
				        return null != value ? value : new Message();
				    }
				    String[] array(String[] value, int size) {
				        return value == null ? new String[size] : value;
				    }
				}
				""");

		assertTrue(output.contains("import java.util.Objects;"), output);
		assertTrue(output.contains("Objects.requireNonNullElse(value, \"fallback\")"), output);
		assertTrue(output.contains("Objects.requireNonNullElseGet(value, () -> new Message())"), output);
		assertTrue(output.contains("Objects.requireNonNullElseGet(value, () -> new String[size])"), output);
	}

	@Test
	void keepsFallbacksThatCannotSafelyBecomeSuppliers() {
		InspectionContext methodCall = TestSources.parse("""
				class Sample {
				    Object create() { return new Object(); }
				    Object value(Object current) {
				        return current == null ? create() : current;
				    }
				}
				""");
		InspectionContext field = TestSources.parse("""
				class Sample {
				    Object current;
				    Object value() { return current == null ? "fallback" : current; }
				}
				""");
		InspectionContext checkedConstructor = TestSources.parse("""
				class Message { Message() throws Exception {} }
				class Sample {
				    Message value(Message current) throws Exception {
				        return current == null ? new Message() : current;
				    }
				}
				""");

		UseNullFallbackMethodTool tool = new UseNullFallbackMethodTool();
		assertFalse(tool.inspect(methodCall, true).changed());
		assertFalse(tool.inspect(field, true).changed());
		assertFalse(tool.inspect(checkedConstructor, true).changed());
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

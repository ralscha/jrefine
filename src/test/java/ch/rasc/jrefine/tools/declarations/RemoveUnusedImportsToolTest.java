package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoveUnusedImportsToolTest {

	private final RemoveUnusedImportsTool tool = new RemoveUnusedImportsTool();

	@Test
	void removesUnusedAndRedundantImportsButKeepsReferencedImports() {
		InspectionContext context = TestSources.parse("""
				package demo;

				import java.util.List;
				import java.util.Map;
				import java.lang.String;
				import demo.Helper;
				import static java.util.Collections.emptyList;
				import static java.util.Collections.singletonList;

				class Sample {
				    private final List<String> values = emptyList();
				}

				class Helper {}
				""");

		ToolResult result = tool.inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertEquals(4, result.findings().size());
		assertTrue(output.contains("import java.util.List;"));
		assertTrue(output.contains("import static java.util.Collections.emptyList;"));
		assertFalse(output.contains("java.util.Map"));
		assertFalse(output.contains("java.lang.String"));
		assertFalse(output.contains("import demo.Helper"));
		assertFalse(output.contains("singletonList"));
	}

	@Test
	void ignoresNamesThatOnlyOccurInCommentsAndStringLiterals() {
		InspectionContext context = TestSources.parse("""
				import java.util.Map;

				// Map is mentioned here, but is not a source reference.
				class Sample {
				    String text = "Map";
				}
				""");

		ToolResult result = tool.inspect(context, true);

		assertEquals(1, result.findings().size());
		assertFalse(TestSources.print(context).contains("import java.util.Map"));
	}

	@Test
	void keepsWildcardImportsButRemovesExactDuplicates() {
		InspectionContext context = TestSources.parse("""
				import java.util.*;
				import java.util.*;

				class Sample {}
				""");

		ToolResult result = tool.inspect(context, true);

		assertEquals(1, result.findings().size());
		assertEquals(1, context.compilationUnit().getImports().size());
		assertTrue(context.compilationUnit().getImport(0).isAsterisk());
	}

	@Test
	void checkModeDoesNotChangeTheTree() {
		InspectionContext context = TestSources.parse("""
				import java.util.Map;
				class Sample {}
				""");

		ToolResult result = tool.inspect(context, false);

		assertFalse(result.changed());
		assertEquals(1, result.findings().size());
		assertTrue(TestSources.print(context).contains("import java.util.Map;"));
	}

	@Test
	void keepsImportsReferencedByJavadocLinks() {
		InspectionContext context = TestSources.parse("""
				import java.util.List;

				/** Returns a {@link List} of values. */
				class Sample {}
				""");

		ToolResult result = tool.inspect(context, true);

		assertTrue(result.findings().isEmpty());
		assertTrue(TestSources.print(context).contains("import java.util.List;"));
	}

	@Test
	void keepsImportUsedAsQualifierOfNestedAnnotation() {
		InspectionContext context = TestSources.parse("""
				import org.jooq.Stringly;

				class Sample {
				    void run(@Stringly.SQL String query) {}
				}
				""");

		ToolResult result = tool.inspect(context, true);

		assertTrue(result.findings().isEmpty());
		assertTrue(TestSources.print(context).contains("import org.jooq.Stringly;"));
	}

}

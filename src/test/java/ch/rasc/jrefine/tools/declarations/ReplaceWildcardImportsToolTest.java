package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ch.rasc.jrefine.analysis.LineEndingSupport.LINE_FEED;

class ReplaceWildcardImportsToolTest {

	private final ReplaceWildcardImportsTool tool = new ReplaceWildcardImportsTool();

	@Test
	void replacesTypeWildcardWithReferencedTypesInNameOrder() {
		InspectionContext context = TestSources.parse("""
				import java.util.*;

				class Sample {
				    private final Map<String, List<String>> values = new HashMap<>();
				}
				""");

		ToolResult result = tool.inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertEquals(1, result.findings().size());
		assertTrue(output.contains("""
				import java.util.HashMap;
				import java.util.List;
				import java.util.Map;
				"""));
		assertFalse(output.contains("import java.util.*;"));
	}

	@Test
	void replacesStaticWildcardWithReferencedMembers() {
		InspectionContext context = TestSources.parse("""
				import static java.util.Collections.*;

				class Sample {
				    Object first = emptyList();
				    Object second = singletonList("value");
				}
				""");

		ToolResult result = tool.inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertTrue(output.contains("""
				import static java.util.Collections.emptyList;
				import static java.util.Collections.singletonList;
				"""));
		assertFalse(output.contains("import static java.util.Collections.*;"));
	}

	@Test
	void checkModeReportsWithoutEditing() {
		InspectionContext context = TestSources.parse("""
				import java.util.*;
				class Sample { List<String> values; }
				""");

		ToolResult result = tool.inspect(context, false);

		assertFalse(result.changed());
		assertEquals(1, result.findings().size());
		assertTrue(TestSources.print(context).contains("import java.util.*;"));
	}

	@Test
	void keepsTypesReferencedFromJavadoc() {
		InspectionContext context = TestSources.parse("""
				import java.util.*;

				/** Returns a {@link List} of values. */
				class Sample {}
				""");

		ToolResult result = tool.inspect(context, true);

		assertTrue(result.changed());
		assertTrue(TestSources.print(context).contains("import java.util.List;"));
	}

	@Test
	void leavesUnresolvableAndAmbiguousWildcardsAlone() {
		InspectionContext unresolvable = TestSources.parse("""
				import example.library.*;
				class Sample { External value; }
				""");
		InspectionContext ambiguous = TestSources.parse("""
				import java.awt.*;
				import java.util.*;
				class Sample { List value; }
				""");

		assertTrue(tool.inspect(unresolvable, true).findings().isEmpty());
		assertTrue(tool.inspect(ambiguous, true).findings().isEmpty());
		assertTrue(TestSources.print(unresolvable).contains("import example.library.*;"));
		assertTrue(TestSources.print(ambiguous).contains("import java.awt.*;"));
		assertTrue(TestSources.print(ambiguous).contains("import java.util.*;"));
	}

	@Test
	void resolvesPublicTypesFromTheSameSourceRoot(@TempDir Path directory) throws IOException {
		Path sourceRoot = directory.resolve("src").resolve("main").resolve("java");
		Path importedType = sourceRoot.resolve("support").resolve("Widget.java");
		Path sample = sourceRoot.resolve("demo").resolve("Sample.java");
		Files.createDirectories(importedType.getParent());
		Files.createDirectories(sample.getParent());
		Files.writeString(importedType, "package support; public class Widget {}" + LINE_FEED);
		String source = """
				package demo;

				import support.*;
				class Sample { Widget value; }
				""";
		Files.writeString(sample, source);
		InspectionContext parsed = TestSources.parse(source);
		InspectionContext context = new InspectionContext(sample, parsed.compilationUnit(), source);

		ToolResult result = tool.inspect(context, true);

		assertTrue(result.changed());
		assertTrue(context.editor().render().contains("import support.Widget;"));
	}

	@Test
	void doesNotReplaceATypeSuppliedByTheCurrentPackage(@TempDir Path directory) throws IOException {
		Path sourceRoot = directory.resolve("src").resolve("main").resolve("java");
		Path packageType = sourceRoot.resolve("demo").resolve("List.java");
		Path sample = sourceRoot.resolve("demo").resolve("Sample.java");
		Files.createDirectories(packageType.getParent());
		Files.writeString(packageType, "package demo; class List {}" + LINE_FEED);
		String source = """
				package demo;

				import java.awt.*;
				class Sample { List value; }
				""";
		Files.writeString(sample, source);
		InspectionContext parsed = TestSources.parse(source);
		InspectionContext context = new InspectionContext(sample, parsed.compilationUnit(), source);

		ToolResult result = tool.inspect(context, true);

		assertTrue(result.findings().isEmpty());
		assertTrue(context.editor().render().contains("import java.awt.*;"));
	}

	@Test
	void doesNotImportAStaticNestedTypeOverALocalType() {
		InspectionContext context = TestSources.parse("""
				import static java.util.Map.*;

				class Entry {}
				class Sample { Entry value; }
				""");

		ToolResult result = tool.inspect(context, true);

		assertTrue(result.findings().isEmpty());
		assertTrue(TestSources.print(context).contains("import static java.util.Map.*;"));
	}

}

package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceExpressionToolsTest {

	@Test
	void removesBoxingWhenTheValueAlreadyHasTheSameWrapperType() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    void accept(Integer value) {}
				    void use(Integer value, Long other) {
				        accept(Integer.valueOf(value));
				        accept(Integer.valueOf((Integer) value));
				        Integer.valueOf(other);
				        Integer.valueOf(42);
				        Integer.valueOf("42");
				    }
				}
				""");
		ToolResult result = new RemoveBoxingOfBoxedValueTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size());
		assertTrue(output.contains("accept(value);"), output);
		assertTrue(output.contains("accept((Integer) value);"), output);
		assertTrue(output.contains("Integer.valueOf(other)"), output);
		assertTrue(output.contains("Integer.valueOf(42)"), output);
		assertTrue(output.contains("Integer.valueOf(\"42\")"), output);
		TestSources.parse(output);
	}

	@Test
	void doesNotTreatAUserTypeNamedLikeAWrapperAsJavaLang() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    static class Integer {
				        static Integer valueOf(Integer value) { return value; }
				    }
				    Integer use(Integer value) { return Integer.valueOf(value); }
				}
				""");

		assertFalse(new RemoveBoxingOfBoxedValueTool().inspect(context, true).changed());
	}

	@Test
	void reportsBulkAttributeReadsWithoutChangingFailureBehavior() {
		InspectionContext context = TestSources.parse("""
				import java.io.File;
				import java.io.IOException;
				class Sample {
				    boolean isNewFile(File file, long lastModified) throws IOException {
				        return file.isFile() && file.lastModified() > lastModified
				                && file.length() > 0 && !file.isDirectory();
				    }
				}
				""");
		String original = TestSources.print(context);
		ToolResult result = new UseBulkFileAttributesTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertFalse(result.changed());
		assertTrue(result.findings().getFirst().message().contains("missing-file"));
		assertEquals(original, output);
	}

	@Test
	void doesNotGroupStructurallyIdenticalStatementsInDifferentScopes() {
		InspectionContext context = TestSources.parse("""
				import java.io.File;
				import java.io.IOException;
				class Sample {
				    boolean first(File file) throws IOException { return file.isDirectory(); }
				    boolean second(File file) throws IOException { return file.isDirectory(); }
				}
				""");
		ToolResult result = new UseBulkFileAttributesTool().inspect(context, true);
		assertTrue(result.findings().isEmpty(), result.findings().toString());
		assertFalse(result.changed());
	}

	@Test
	void keepsSingleOrUncheckedFileAttributeReads() {
		InspectionContext unchecked = TestSources.parse("""
				import java.io.File;
				class Sample {
				    boolean exists(File file) {
				        return file.isFile() && file.length() > 0;
				    }
				}
				""");
		InspectionContext single = TestSources.parse("""
				import java.io.File;
				import java.io.IOException;
				class Sample {
				    long size(File file) throws IOException { return file.length(); }
				}
				""");

		assertFalse(new UseBulkFileAttributesTool().inspect(unchecked, true).changed());
		assertFalse(new UseBulkFileAttributesTool().inspect(single, true).changed());
	}

}

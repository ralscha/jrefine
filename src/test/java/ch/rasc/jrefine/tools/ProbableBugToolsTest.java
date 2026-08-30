package ch.rasc.jrefine.tools;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.tools.declarations.RemoveInvalidSerialAnnotationTool;
import ch.rasc.jrefine.tools.expressions.ReportJdbcIndexZeroTool;
import ch.rasc.jrefine.tools.expressions.ReportWriteOnlyObjectTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbableBugToolsTest {

	@Test
	void removesSerialAnnotationsFromUnrecognizedMembers() {
		InspectionContext context = TestSources.parse("""
				import java.io.ObjectOutputStream;
				import java.io.Serial;
				import java.io.Serializable;
				class Sample implements Serializable {
				    @Serial
				    private static long serialVersionUID = 1L;
				    @Serial
				    void writeObject(ObjectOutputStream output) {}
				    @Serial
				    private void readObjectNoData() {}
				}
				""");

		ToolResult result = new RemoveInvalidSerialAnnotationTool().inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertEquals(2, result.findings().size());
		assertEquals(1, occurrences(output, "@Serial"), output);
		assertTrue(output.contains("private void readObjectNoData()"), output);
		TestSources.parse(output);
	}

	@Test
	void reportsOnlyKnownJdbcColumnAndParameterIndexCalls() {
		InspectionContext context = TestSources.parse("""
				import java.sql.PreparedStatement;
				import java.sql.ResultSet;
				class Sample { void use(ResultSet results, PreparedStatement statement) throws Exception {
				    results.getString(0);
				    statement.setString(0, "name");
				    statement.setFetchSize(0);
				    results.getString(1);
				} }
				""");

		ToolResult result = new ReportJdbcIndexZeroTool().inspect(context, true);

		assertFalse(result.changed());
		assertEquals(2, result.findings().size());

		InspectionContext custom = TestSources.parse("""
				class Sample {
				    interface ResultSet { String getString(int index); }
				    String use(ResultSet results) { return results.getString(0); }
				}
				""");
		assertTrue(new ReportJdbcIndexZeroTool().inspect(custom, false).findings().isEmpty());
	}

	@Test
	void reportsAtomicObjectsThatAreOnlyWritten() {
		InspectionContext writeOnly = TestSources.parse("""
				import java.util.concurrent.atomic.AtomicReference;
				class Sample { void run() {
				    AtomicReference<String> reference = new AtomicReference<>();
				    reference.set("hello");
				} }
				""");
		ToolResult result = new ReportWriteOnlyObjectTool().inspect(writeOnly, true);

		assertFalse(result.changed());
		assertEquals(1, result.findings().size());

		InspectionContext queried = TestSources.parse("""
				import java.util.concurrent.atomic.AtomicReference;
				class Sample { void run() {
				    AtomicReference<String> reference = new AtomicReference<>();
				    reference.set("hello");
				    System.out.println(reference.get());
				} }
				""");
		assertTrue(new ReportWriteOnlyObjectTool().inspect(queried, false).findings().isEmpty());
	}

	private static int occurrences(String value, String needle) {
		return (value.length() - value.replace(needle, "").length()) / needle.length();
	}

}

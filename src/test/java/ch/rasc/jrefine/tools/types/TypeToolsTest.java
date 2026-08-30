package ch.rasc.jrefine.tools.types;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeToolsTest {

	@Test
	void replacesOnlyNonEscapingLocalStringBuffers() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    void consume(Object value) {}
				    String build() {
				        StringBuffer local = new StringBuffer();
				        local.append("safe");
				        StringBuffer escaping = new StringBuffer();
				        consume(escaping);
				        return local.toString();
				    }
				}
				""");
		ToolResult result = new UseStringBuilderTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("StringBuilder local = new StringBuilder()"), output);
		assertTrue(output.contains("StringBuffer escaping = new StringBuffer()"), output);
	}

	@Test
	void doesNotRewriteAUserClassNamedStringBuffer() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    static class StringBuffer {
				        StringBuffer append(String value) { return this; }
				    }
				    void run() {
				        StringBuffer value = new StringBuffer();
				        value.append("custom");
				    }
				}
				""");

		ToolResult result = new UseStringBuilderTool().inspect(context, true);

		assertFalse(result.changed());
	}

}

package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.api.InspectionContext;
import java.util.List;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.syntax.ReportPortabilityIssuesTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureMaturityPortabilityIssuesTest {

	@Test
	void reportsCloningIssues() {
		ToolResult result = inspect(new ReportCloningIssuesTool(), """
				class Sample {
				    protected Object clone() {
				        new Object();
				        return this;
				    }
				    Object copy() { return clone(); }
				}
				class CloneableThing implements Cloneable {}
				""");

		assertMessages(result, "does not declare CloneNotSupportedException", "instantiates objects", "non-Cloneable",
				"not public", "return type", "does not declare a clone", "Use of Cloneable");
	}

	@Test
	void reportsCodeMaturityIssues() {
		ToolResult result = inspect(new ReportCodeMaturityIssuesTool(), """
				import java.util.Date;
				import java.util.Optional;
				import java.util.Vector;
				@Deprecated(forRemoval = true)
				class Legacy {
				    @Deprecated(forRemoval = true) static void old() {}
				}
				class Sample {
				    Optional<String> missing = null;
				    // int removed = 1;
				    // removed++;
				    // if (removed > 1) {
				    // }
				    @ScheduledForRemoval
				    void scheduled() {}
				    Optional<String> optional() { return null; }
				    void diagnostics(Throwable error) {
				        System.out.println(error);
				        error.printStackTrace();
				        Thread.dumpStack();
				    }
				    void uses() {
				        Legacy legacy = new Legacy();
				        Legacy.old();
				        Vector<String> values = new Vector<>();
				        Date date = new Date();
				    }
				    void longMethod() {
				        old(); old(); old(); old(); old(); old(); old();
				        old(); old(); old(); old(); old(); old(); old();
				        old(); old(); old(); old(); old(); old(); old();
				    }
				    void old() {}
				}
				""");

		assertMessages(result, "Throwable is printed", "printStackTrace", "Thread.dumpStack", "Commented out code",
				"Deprecated API usage", "Deprecated member is still used", "can be extracted", "Null value",
				"Null is returned", "ScheduledForRemoval", "marked for removal", "obsolete collection",
				"obsolete date-time");
	}

	@Test
	void ignoresShortCommentedCodeAndJavadoc() {
		ToolResult result = inspect(new ReportCodeMaturityIssuesTool(), """
				class Sample {
				    // int first = 1;
				    // int second = 2;
				    // return;
				    int value;

				    /**
				     * int documented = 1;
				     * documented++;
				     * if (documented > 1) {
				     *     return;
				     * }
				     */
				    void documented() {}
				}
				""");

		assertNoMessages(result, "Commented out code");
	}

	@Test
	void reportsClassStructureIssues() {
		ToolResult result = inspect(new ReportClassStructureIssuesTool(), """
				abstract class AbstractCandidate {
				    public static final int VALUE = 1;
				    abstract void work();
				}
				abstract class AbstractNoop {
				    void noop() {}
				    int kind() { return 1; }
				}
				interface Constants {
				    int VALUE = 1;
				    class Nested {}
				}
				interface Functional { void run(); }
				interface Marker {}
				final class Closed {}
				class PrivateOnly { private PrivateOnly() {} }
				class Empty {}
				enum Kind { A; int mutable; }
				class SingletonSample {
				    private static final SingletonSample INSTANCE = new SingletonSample();
				    private SingletonSample() {}
				}
				class Utility {
				    public Utility() {}
				    static void help() {}
				}
				class Different implements java.awt.event.MouseListener {
				    static int mutableStatic;
				    private int temporary;
				    { temporary = 1; }
				    public Different() {}
				    private final void privateFinal() {}
				    static final void staticFinal() {}
				    final void closedMethod() {}
				    int constant() { return 42; }
				    void onlyUser() { System.out.println(temporary); }
				    void unused(int input) {}
				    void local() {
				        class Local {}
				        Runnable value = new Runnable() { public void run() {} };
				    }
				}
				""");

		assertMessages(result, "Private method", "Static method", "Static field is not final", "may be an interface",
				"MouseAdapter", "differs from file name", "only private constructors", "abstract class", "interface",
				"Empty class", "Inner class", "@FunctionalInterface", "Local class", "Marker interface",
				"cannot be overridden", "Multiple top-level", "No-op method", "Non-static initializer",
				"Non-final field in enum", "Singleton class", "not final", "public constructor",
				"no private constructor", "never read");
		assertNoMessages(result, "Method returns a per-class constant", "Class is closed to inheritance",
				"Utility class", "Utility class can be converted to an enum");
	}

	@Test
	void reportsMemoryIssues() {
		ToolResult result = inspect(new ReportMemoryIssuesTool(), """
				class Item {
				    static final Item[] EMPTY = new Item[0];
				}
				class Sample {
				    StringBuilder builder = new StringBuilder();
				    class Inner {}
				    void collect() {
				        System.gc();
				        Runtime.getRuntime().gc();
				        Item[] values = new Item[0];
				        Runnable task = new Runnable() { public void run() {} };
				    }
				    Object returned() { return new Inner(); }
				}
				""");

		assertMessages(result, "StringBuilder", "System.gc() or Runtime.gc()", "Inner class may be static",
				"Return of anonymous, local, or inner", "Unnecessary zero-length array");
	}

	@Test
	void reportsPortabilityIssues() {
		ToolResult result = inspect(new ReportPortabilityIssuesTool(), """
				import sun.misc.Unsafe;
				import java.awt.peer.ComponentPeer;
				import com.mysql.cj.jdbc.Driver;
				import java.nio.file.Path;
				class Sample {
				    ProcessBuilder builder;
				    Unsafe unsafe;
				    ComponentPeer peer;
				    Driver driver;
				    native void nativeCall();
				    void run() throws Exception {
				        Runtime.getRuntime().exec("tool");
				        Runtime.getRuntime().halt(1);
				        System.exit(1);
				        System.getenv("HOME");
				        Path path = Path.of("folder/file");
				        String lineSeparator = "\\n";
				    }
				}
				""");

		assertMessages(result, "Runtime.exec", "System.exit", "System.getenv", "Hardcoded file separator",
				"Hardcoded line separator", "Native method", "ProcessBuilder", "sun.*", "AWT peer",
				"concrete JDBC driver");
	}

	@Test
	void doesNotTreatSyntaxOrProseAsFilePaths() {
		ToolResult result = inspect(new ReportPortabilityIssuesTool(), """
				class Sample {
				    boolean syntax(String source) {
				        return source.contains("//") || source.contains("/*")
				                || source.matches("a/b")
				                || "query/update collection".contains(source)
				                || "https://example.test/a/b".contains(source);
				    }
				}
				""");

		assertNoMessages(result, "Hardcoded file separator");
	}

	@Test
	void doesNotTreatWebRoutesOrContentDelimitersAsPlatformSeparators() {
		ToolResult result = inspect(new ReportPortabilityIssuesTool(), """
				@interface GetMapping { String value(); }
				class Sample {
				    @GetMapping("/v2/items")
				    void route(String content, StringBuilder output) {
				        requestMatchers("/api/**");
				        content.split("\\n");
				        content.replace("\\n", "<br>");
				        output.append("\\n");
				    }
				    void requestMatchers(String route) {}
				}
				""");

		assertNoMessages(result, "Hardcoded file separator", "Hardcoded line separator");
	}

	@Test
	void recognizesUnixPathsOnlyInFileSystemContexts() {
		ToolResult result = inspect(new ReportPortabilityIssuesTool(), """
				import java.nio.file.Path;
				class Sample {
				    Path path = Path.of("/var/data");
				    String route = "/v2/data";
				}
				""");

		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		assertTrue(messages.stream().filter(message -> message.contains("Hardcoded file separator")).count() == 1,
				messages.toString());
	}

	@Test
	void acceptsSystemExitAtTheApplicationEntryPoint() {
		ToolResult result = inspect(new ReportPortabilityIssuesTool(), """
				class Sample {
				    public static void main(String[] args) {
				        System.exit(run(args));
				    }
				    static int run(String[] args) { return 0; }
				}
				""");

		assertNoMessages(result, "Call to System.exit()");
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

	private static void assertNoMessages(ToolResult result, String... unwanted) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String message : unwanted) {
			assertFalse(messages.contains(message), () -> "Unexpected '" + message + "' in " + messages);
		}
	}

}

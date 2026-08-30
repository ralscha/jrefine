package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.expressions.ReportInjectionRisksTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSafetyToolsTest {

	@Test
	void reportsLocallyOwnedResourcesWithoutCleanup() {
		ToolResult result = inspect(new ReportResourceManagementBugsTool(), """
				import java.io.FileInputStream;
				import java.net.Socket;
				import java.nio.channels.FileChannel;
				import java.nio.file.Path;
				import java.nio.file.StandardOpenOption;
				import java.sql.Connection;
				import java.sql.Statement;
				class Sample {
				    static final class Resource implements AutoCloseable {
				        public void close() {}
				    }
				    void open(Path path, Connection connection) throws Exception {
				        FileInputStream input = new FileInputStream(path.toFile());
				        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
				        Socket socket = new Socket("localhost", 8080);
				        Statement statement = connection.createStatement();
				        Resource resource = new Resource();
				    }
				}
				""");

		assertMessages(result, "I/O resource", "Channel", "Socket", "JDBC resource", "AutoCloseable resource");
	}

	@Test
	void acceptsManagedAndTransferredResources() {
		ToolResult result = inspect(new ReportResourceManagementBugsTool(), """
				import java.io.FileInputStream;
				import java.io.IOException;
				import java.io.InputStream;
				import java.nio.file.Path;
				class Sample {
				    void managed(Path path) throws IOException {
				        try (FileInputStream input = new FileInputStream(path.toFile())) {
				            input.read();
				        }
				        FileInputStream closed = new FileInputStream(path.toFile());
				        closed.close();
				        FileInputStream transferred = new FileInputStream(path.toFile());
				        consume(transferred);
				    }
				    InputStream returned(Path path) throws IOException {
				        FileInputStream input = new FileInputStream(path.toFile());
				        return input;
				    }
				    void consume(InputStream input) {}
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsExceptionDiscardingControlFlow() {
		ToolResult result = inspect(new ReportExceptionFlowBugsTool(), """
				class Sample {
				    int returns() {
				        try { work(); }
				        finally { return 1; }
				    }
				    void throwsFromFinally() {
				        try { work(); }
				        finally { throw new IllegalStateException(); }
				    }
				    void breaks() {
				        while (true) {
				            try { work(); }
				            finally { break; }
				        }
				    }
				    void nullFailure() { throw null; }
				    void fatal() {
				        try { work(); } catch (Error error) { log(error); }
				        try { work(); } catch (ThreadDeath death) { log(death); }
				        try { work(); } catch (RuntimeException error) { throw error; }
				        try {} finally {}
				    }
				    void work() {}
				    void log(Throwable error) {}
				}
				""");

		assertMessages(result, "Return inside finally", "Throw inside finally", "Break exits from inside finally",
				"Throwing null", "Caught Error", "Caught ThreadDeath", "immediately rethrown", "Empty try",
				"Empty finally", "cannot complete normally");
	}

	@Test
	void ignoresControlFlowContainedInsideFinallyHelpers() {
		ToolResult result = inspect(new ReportExceptionFlowBugsTool(), """
				class Sample {
				    void safe() {
				        try { work(); }
				        finally {
				            while (condition()) { break; }
				            Runnable task = () -> { return; };
				            try { throw new IllegalStateException(); }
				            catch (IllegalStateException expected) { log(expected); }
				        }
				        try { work(); }
				        catch (Error error) { throw error; }
				    }
				    boolean condition() { return false; }
				    void work() {}
				    void log(Throwable error) {}
				}
				""");

		assertNoMessages(result, "Break exits", "Return inside finally", "Throw inside finally", "Caught Error");
	}

	@Test
	void reportsOnlyDemonstrablyDynamicInterpreterInput() {
		ToolResult result = inspect(new ReportInjectionRisksTool(), """
				import java.sql.Connection;
				import java.sql.Statement;
				class Sample {
				    void run(String user, Connection connection, Statement statement) throws Exception {
				        String query = "select * from users where name='" + user + "'";
				        statement.executeQuery(query);
				        connection.prepareStatement("select * from items where id=" + user);
				        Runtime.getRuntime().exec("lookup " + user);
				        new ProcessBuilder("lookup", user);
				        System.loadLibrary(user);

				        String constant = "select 1";
				        statement.executeQuery(constant);
				        Runtime.getRuntime().exec("version");
				    }
				}
				""");

		assertMessages(result, "SQL passed to Statement", "Connection.prepare", "Runtime.exec", "ProcessBuilder",
				"Native library");
		assertFalse(messages(result).stream().anyMatch(message -> message.contains("select 1")));
	}

	@Test
	void ignoresSameNamedCustomApisAndUnknownExternalConstants() {
		ToolResult result = inspect(new ReportInjectionRisksTool(), """
				class Sample {
				    static final class Statement { void executeQuery(String value) {} }
				    static final class Runtime { void exec(String value) {} }
				    static final class Queries { static String SELECT; }
				    void run(String value, Statement statement, Runtime runtime) {
				        statement.executeQuery(value);
				        runtime.exec(value);
				        use(Queries.SELECT);
				    }
				    void use(String value) {}
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void doesNotTreatNumericSqlValuesAsInjectionInput() {
		ToolResult result = inspect(new ReportInjectionRisksTool(), """
				import java.sql.Statement;
				class Sample {
				    void run(Statement statement, long gameId, boolean alternate) throws Exception {
				        long playerId = lookup();
				        statement.executeQuery("select * from Game where id=" + gameId);
				        statement.executeQuery("select * from Player where id=" + playerId);
				        statement.executeQuery(alternate ? "select 1" : "select 2");
				    }
				    long lookup() { return 1L; }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
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

	private static void assertNoMessages(ToolResult result, String... fragments) {
		List<String> messages = messages(result);
		for (String fragment : fragments) {
			assertFalse(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Unexpected '" + fragment + "' in " + messages);
		}
	}

}

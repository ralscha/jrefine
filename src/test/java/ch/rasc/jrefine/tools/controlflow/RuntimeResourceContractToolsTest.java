package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.expressions.ReportThrowableConstructionIssuesTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeResourceContractToolsTest {

	@Test
	void reportsHibernateAndJndiLeaks() {
		ToolResult result = inspect(new ReportResourceManagementBugsTool(), """
				import javax.naming.Context;
				import javax.naming.InitialContext;
				import javax.naming.NamingEnumeration;
				import org.hibernate.Session;
				import org.hibernate.SessionFactory;
				class Sample {
				    void leak(SessionFactory factory) throws Exception {
				        Session session = factory.openSession();
				        Context context = new InitialContext();
				        NamingEnumeration<?> names = context.list("");
				    }
				}
				""");

		assertMessages(result, "Hibernate resource", "JNDI resource");
		long jndiFindings = messages(result).stream().filter(message -> message.contains("JNDI resource")).count();
		assertTrue(jndiFindings == 2, messages(result).toString());
	}

	@Test
	void acceptsClosedHibernateAndJndiResources() {
		ToolResult result = inspect(new ReportResourceManagementBugsTool(), """
				import javax.naming.Context;
				import javax.naming.InitialContext;
				import javax.naming.NamingEnumeration;
				import org.hibernate.Session;
				import org.hibernate.SessionFactory;
				class Sample {
				    void managed(SessionFactory factory) throws Exception {
				        Session session = factory.openSession();
				        try { work(); }
				        finally { session.close(); }
				        Context context = new InitialContext();
				        NamingEnumeration<?> names = context.list("");
				        names.close();
				        context.close();
				    }
				    void work() {}
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsDriverManagerAndRedundantClose() {
		ToolResult result = inspect(new ReportResourceLifecyclePolicyIssuesTool(), """
				import java.sql.Connection;
				import java.sql.DriverManager;
				class Sample {
				    void connect() throws Exception {
				        try (Connection connection = DriverManager.getConnection("jdbc:test")) {
				            work();
				            connection.close();
				        }
				    }
				    void work() {}
				}
				""");

		assertMessages(result, "DriverManager", "redundant");
	}

	@Test
	void acceptsDataSourceAndImplicitResourceClose() {
		ToolResult result = inspect(new ReportResourceLifecyclePolicyIssuesTool(), """
				import java.sql.Connection;
				import javax.sql.DataSource;
				class Sample {
				    void connect(DataSource source) throws Exception {
				        try (Connection connection = source.getConnection()) {
				            work(connection);
				        }
				    }
				    void work(Connection connection) {}
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsReplaceableInitCauseCalls() {
		ToolResult result = inspect(new ReportThrowableConstructionIssuesTool(), """
				import java.io.IOException;
				class Sample {
				    Throwable first(Throwable cause) {
				        return new RuntimeException().initCause(cause);
				    }
				    Throwable second(String message, Throwable cause) {
				        return new IOException(message).initCause(cause);
				    }
				}
				""");

		long findings = messages(result).stream().filter(message -> message.contains("Unnecessary initCause")).count();
		assertTrue(findings == 2, messages(result).toString());
	}

	@Test
	void ignoresCustomAndAlreadyCauseConstructedExceptions() {
		ToolResult custom = inspect(new ReportThrowableConstructionIssuesTool(), """
				class IOException extends Exception {}
				class Sample {
				    Throwable custom(Throwable cause) {
				        return new IOException().initCause(cause);
				    }
				}
				""");
		assertTrue(custom.findings().isEmpty(), custom.findings().toString());

		ToolResult constructed = inspect(new ReportThrowableConstructionIssuesTool(), """
				class Sample {
				    Throwable constructed(Throwable cause, Throwable other) {
				        return new RuntimeException(cause).initCause(other);
				    }
				}
				""");
		assertTrue(constructed.findings().isEmpty(), constructed.findings().toString());
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

}

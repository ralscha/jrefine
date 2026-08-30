package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.expressions.ReportLoggingIssuesTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NextSafetyBatchToolsTest {

	@Test
	void reportsConstructionAndInitializationHazards() {
		ToolResult result = inspect(new ReportInitializationBugsTool(), """
				import java.util.HashMap;
				import java.util.Map;
				abstract class Broken {
				    static Broken escaped;
				    static Object singleton;
				    int early = this.later;
				    int later = 7;
				    static int staticEarly = Broken.staticLater;
				    static int staticLater = makeValue();

				    Broken() {
				        escaped = this;
				        Registry.register(this);
				        new Holder(this);
				        initialize();
				    }
				    abstract void initialize();
				    static Object instance() {
				        if (singleton == null) { singleton = new Object(); }
				        return singleton;
				    }
				    static int makeValue() { return 1; }
				}
				class Parent { void initialize() {} }
				class Child extends Parent {
				    Child() { initialize(); }
				    @Override void initialize() {}
				}
				class Extensible {
				    Extensible() { initialize(); }
				    void initialize() {}
				}
				class ExtensibleChild extends Extensible {
				    @Override void initialize() {}
				}
				class DoubleBrace {
				    Map<String, String> values = new HashMap<>() {{ put("a", "b"); }};
				}
				class Registry { static void register(Object value) {} }
				class Holder { Holder(Object value) {} }
				@SuppressWarnings("this-escape")
				class Generated {
				    Generated() { initialize(); }
				    void initialize() {}
				}
				""");

		assertMessages(result, "Abstract method", "Overridden method", "Overridable method", "may escape",
				"published from the constructor", "assigned outside", "Instance field is read before",
				"Static field is read before", "lazily initialized", "Double-brace");
	}

	@Test
	void acceptsConstructionPatternsWithoutImmediateEscapeOrDispatch() {
		ToolResult result = inspect(new ReportInitializationBugsTool(), """
				final class Safe {
				    static volatile Object singleton;
				    static Object delayed;
				    static int code = Safe.VALUE;
				    static final int VALUE = -1;
				    Runnable callback = () -> use(later);
				    int later = 7;
				    Safe self;
				    Safe() { self = this; setSelf(this); initialize(); }
				    private void setSelf(Safe value) { self = value; }
				    private void initialize() {}
				    private static void use(int value) {}
				    static Object instance() {
				        if (singleton == null) { singleton = new Object(); }
				        return singleton;
				    }
				    static void delayedInitialization() {
				        if (delayed == null) {
				            Runnable task = () -> delayed = new Object();
				        }
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsSerializationDeclarationsIgnoredByTheRuntime() {
		ToolResult result = inspect(new ReportSerializationContractBugsTool(), """
				import java.io.Externalizable;
				import java.io.ObjectInput;
				import java.io.ObjectInputStream;
				import java.io.ObjectOutput;
				import java.io.ObjectOutputStream;
				import java.io.ObjectStreamField;
				import java.io.Serializable;
				class Broken implements Serializable {
				    public static long serialVersionUID = 1L;
				    static final ObjectStreamField[] serialPersistentFields = {};
				    void writeObject(ObjectOutputStream output) {}
				    protected void readObject(ObjectInputStream input) {}
				}
				class External implements Externalizable {
				    External(int value) {}
				    public void readExternal(ObjectInput input) {}
				    public void writeExternal(ObjectOutput output) {}
				    private void readObject(ObjectInputStream input) {}
				}
				class Plain {
				    private static final long serialVersionUID = 1L;
				    transient String ignored;
				    private void writeObject(ObjectOutputStream output) {}
				}
				class Outer {
				    class Inner implements Serializable {}
				}
				class Payload {}
				class Writer {
				    void write(ObjectOutputStream output) throws Exception {
				        output.writeObject(new Payload());
				    }
				}
				""");

		assertMessages(result, "serialVersionUID must", "serialPersistentFields must", "writeObject() must be private",
				"readObject() must be private", "no public no-argument constructor", "Externalizable uses",
				"has no effect", "transient has no", "ignored because", "has no serialVersionUID",
				"captures a non-serializable outer", "passed to ObjectOutputStream");
	}

	@Test
	void acceptsValidAndInheritanceDependentSerializationDeclarations() {
		ToolResult result = inspect(new ReportSerializationContractBugsTool(), """
				import java.io.Externalizable;
				import java.io.ObjectInput;
				import java.io.ObjectInputStream;
				import java.io.ObjectOutput;
				import java.io.ObjectOutputStream;
				import java.io.ObjectStreamField;
				import java.io.Serializable;
				public class ExternalOk implements Externalizable {
				    public ExternalOk() {}
				    public void readExternal(ObjectInput input) {}
				    public void writeExternal(ObjectOutput output) {}
				}
				class SerialOk implements Serializable {
				    private static final long serialVersionUID = 1L;
				    private static final ObjectStreamField[] serialPersistentFields = {};
				    transient String cache;
				    private void writeObject(ObjectOutputStream output) {}
				    private void readObject(ObjectInputStream input) {}
				}
				class UnknownBase {}
				class InheritanceDependent extends UnknownBase {
				    private static final long serialVersionUID = 1L;
				    transient String cache;
				    private void writeObject(ObjectOutputStream output) {}
				}
				class SerializableOuter implements Serializable {
				    class Inner implements Serializable {
				        private static final long serialVersionUID = 1L;
				    }
				}
				class Payload implements Serializable {}
				class Writer {
				    void write(ObjectOutputStream output) throws Exception {
				        output.writeObject(new Payload());
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsLoggerCategoryFieldAndConcatenationIssues() {
		ToolResult result = inspect(new ReportLoggingIssuesTool(), """
				import org.slf4j.Logger;
				import org.slf4j.LoggerFactory;
				class Sample {
				    static Logger mutable = LoggerFactory.getLogger(Other.class);
				    final Logger secondary = LoggerFactory.getLogger(Sample.class);
				    void log(String user, int count) {
				        mutable.info("User " + user);
				        this.secondary.info("Count " + count);
				        mutable.info("constant " + "message");
				    }
				}
				class Other {}
				""");

		assertMessages(result, "multiple logger", "Logger field is mutable", "foreign class Other",
				"eager string concatenation");
		long concatenations = messages(result).stream()
			.filter(message -> message.contains("eager string concatenation"))
			.count();
		assertTrue(concatenations == 2, messages(result).toString());
	}

	@Test
	void acceptsParameterizedLoggingConstantsAndCustomApis() {
		ToolResult result = inspect(new ReportLoggingIssuesTool(), """
				import org.slf4j.Logger;
				import org.slf4j.LoggerFactory;
				class Sample {
				    private static final Logger LOGGER = LoggerFactory.getLogger(Sample.class);
				    private static final String PREFIX = "fixed";
				    void log(String user) {
				        LOGGER.info("User {}", user);
				        LOGGER.info("message " + PREFIX);
				        info("ordinary method");
				    }
				    void info(String value) {}
				}
				""");
		assertTrue(result.findings().isEmpty(), result.findings().toString());

		ToolResult custom = inspect(new ReportLoggingIssuesTool(), """
				class Sample {
				    static final class Logger { void info(String value) {} }
				    final Logger logger = new Logger();
				    void log(String user) { logger.info("User " + user); }
				}
				""");
		assertTrue(custom.findings().isEmpty(), custom.findings().toString());

		ToolResult legacy = inspect(new ReportLoggingIssuesTool(), """
				import org.apache.commons.logging.Log;
				import org.apache.commons.logging.LogFactory;
				class Sample {
				    private static final Log LOG = LogFactory.getLog(Sample.class);
				    void log(String user) { LOG.debug("User " + user); }
				}
				""");
		assertTrue(legacy.findings().isEmpty(), legacy.findings().toString());
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

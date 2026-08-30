package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.expressions.ReportReflectionContractBugsTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityReflectionBeanToolsTest {

	@Test
	void reportsSecuritySensitiveBoundariesAndExposedState() {
		ToolResult result = inspect(new ReportSecuritySensitiveCodeTool(), """
				import java.net.URL;
				import java.net.URLClassLoader;
				import java.util.ArrayList;
				import java.util.List;
				import java.util.Random;
				import java.util.concurrent.ThreadLocalRandom;
				class SecurityBoundary {
				    public static final byte[] KEY = {};
				    public static final List<String> MUTABLE = new ArrayList<>();
				    String generateToken() { return Long.toString(new Random().nextLong()); }
				    double generateNonce() { return Math.random() + ThreadLocalRandom.current().nextDouble(); }
				    String configuration() { return System.getProperty("application.key"); }
				    void disableSandbox() { System.setSecurityManager(null); }
				    ClassLoader loader(URL[] urls) { return new URLClassLoader(urls); }
				}
				class BoundaryLoader extends ClassLoader {}
				class BoundaryManager extends SecurityManager {}
				""");

		assertMessages(result, "Public static array", "Public static collection", "predictable random generator",
				"System property access", "System.setSecurityManager", "ClassLoader instantiation",
				"Custom ClassLoader", "Custom SecurityManager");
		assertEquals(3,
				messages(result).stream().filter(message -> message.contains("predictable random generator")).count());
	}

	@Test
	void acceptsImmutableStateSecureRandomAndOrdinarySimulationRandomness() {
		ToolResult result = inspect(new ReportSecuritySensitiveCodeTool(), """
				import java.security.SecureRandom;
				import java.util.Collections;
				import java.util.List;
				import java.util.Map;
				import java.util.Random;
				class SafeBoundary {
				    private static final byte[] KEY = {};
				    public static final List<String> NAMES = List.of("safe");
				    public static final Map<String, String> VALUES = Map.copyOf(Map.of());
				    public static final List<String> EMPTY = Collections.emptyList();
				    byte[] createToken() {
				        byte[] token = new byte[16];
				        new SecureRandom().nextBytes(token);
				        return token;
				    }
				    int simulationStep() { return new Random().nextInt(); }
				    int hockeySimulation() { return new Random().nextInt(); }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsImpossibleReflectionContracts() {
		ToolResult result = inspect(new ReportReflectionContractBugsTool(), """
				import java.lang.invoke.MethodHandles;
				import java.lang.invoke.MethodType;
				import java.lang.reflect.Constructor;
				import java.lang.reflect.Method;
				class Target {
				    private int hidden;
				    public Target(String value) {}
				    private void secret() {}
				    public String greet(String name) { return name; }
				    public int count() { return 1; }
				    public static int convert(int value) { return value; }
				}
				interface Contract {}
				class BrokenReflection {
				    void inspect() throws Throwable {
				        MethodHandles.Lookup lookup = MethodHandles.lookup();
				        Target.class.getDeclaredMethod("missing");
				        Target.class.getMethod("equals", String.class);
				        Contract.class.getMethod("toString");
				        Target.class.getMethod("secret");
				        Target.class.getDeclaredField("missing");
				        Target.class.getField("hidden");
				        Target.class.getDeclaredConstructor(int.class);
				        Method greet = Target.class.getDeclaredMethod("greet", String.class);
				        greet.invoke(new Target("value"));
				        Method count = Target.class.getDeclaredMethod("count");
				        count.invoke(null);
				        Constructor<Target> constructor =
				                Target.class.getDeclaredConstructor(String.class);
				        constructor.newInstance();
				        lookup.findVirtual(Target.class, "greet",
				                MethodType.methodType(int.class, String.class));
				        MethodHandles.lookup().findStatic(Target.class, "convert",
				                MethodType.methodType(int.class, String.class));
				        MethodHandles.lookup().findGetter(Target.class, "hidden", String.class);
				        MethodHandles.lookup().findConstructor(Target.class,
				                MethodType.methodType(Target.class, String.class));
				    }
				}
				""");

		assertMessages(result, "method lookup does not match", "cannot access a non-public",
				"field lookup does not match", "constructor lookup does not match", "Method.invoke() argument count",
				"null receiver", "Constructor.newInstance() argument count", "MethodHandle lookup type",
				"MethodHandle/VarHandle field lookup type", "MethodHandle constructor lookup type");
		assertEquals(14, result.findings().size(), result.findings().toString());
	}

	@Test
	void acceptsValidDynamicAndCompilerGeneratedReflectionContracts() {
		ToolResult result = inspect(new ReportReflectionContractBugsTool(), """
				import java.lang.invoke.MethodHandles;
				import java.lang.invoke.MethodType;
				import java.lang.reflect.Constructor;
				import java.lang.reflect.Method;
				record Data(String name) {}
				enum Mode { ON }
				class ReflectionTarget {
				    public String value;
				    public ReflectionTarget(String value) { this.value = value; }
				    public String greet(String name) { return name; }
				    public static int convert(int value) { return value; }
				}
				class ParentDependent extends ExternalBase {}
				class FieldDependent extends ExternalBase {
				    private String inherited;
				}
				class ExternalBase {}
				class SafeReflection {
				    void findVirtual(Class<?> owner, String name, MethodType type) {}
				    void inspect() throws Throwable {
				        Method greet = ReflectionTarget.class.getMethod("greet", String.class);
				        Object[] arguments = { "value" };
				        greet.invoke(new ReflectionTarget("value"), arguments);
				        ReflectionTarget.class.getMethod("equals", Object.class);
				        Constructor<ReflectionTarget> constructor =
				                ReflectionTarget.class.getConstructor(String.class);
				        constructor.newInstance("value");
				        ReflectionTarget.class.getField("value");
				        MethodHandles.lookup().findVirtual(ReflectionTarget.class, "greet",
				                MethodType.methodType(String.class, String.class));
				        MethodHandles.lookup().findVirtual(ReflectionTarget.class, "toString",
				                MethodType.methodType(String.class));
				        MethodHandles.lookup().findStatic(ReflectionTarget.class, "convert",
				                MethodType.methodType(int.class, int.class));
				        MethodHandles.lookup().findGetter(
				                ReflectionTarget.class, "value", String.class);
				        MethodHandles.lookup().findConstructor(ReflectionTarget.class,
				                MethodType.methodType(void.class, String.class));
				        findVirtual(ReflectionTarget.class, "missing",
				                MethodType.methodType(void.class));
				        Data.class.getDeclaredField("name");
				        Data.class.getMethod("name");
				        Data.class.getMethod("toString");
				        Mode.class.getMethod("values");
				        Mode.class.getMethod("valueOf", String.class);
				        Mode.class.getField("ON");
				        ExternalType.class.getDeclaredMethod("unknown");
				        ParentDependent.class.getMethod("inherited");
				        FieldDependent.class.getField("inherited");
				        Method changed = ReflectionTarget.class.getMethod("greet", String.class);
				        changed = ExternalType.class.getDeclaredMethod("unknown");
				        changed.invoke(null);
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsJavaBeansAccessorsThatUseTheWrongValue() {
		ToolResult result = inspect(new ReportJavaBeansContractBugsTool(), """
				class BrokenBean {
				    private String name;
				    private String other;
				    private boolean active;
				    public String getName() { return this.other; }
				    public void setName(String name) {
				        this.other = name;
				        this.active = this.active;
				    }
				    public boolean isActive() { return this.active; }
				    public void setActive(boolean active) { active = active; }
				    public void setOther(String value) { other = other; }
				}
				""");

		assertMessages(result, "Getter for property 'name' returns field 'other'",
				"Setter for property 'name' writes field 'other'", "Setter assigns a property value to itself");
		assertEquals(5, result.findings().size(), result.findings().toString());
	}

	@Test
	void acceptsConventionalComputedAndLocallyShadowedAccessors() {
		ToolResult result = inspect(new ReportJavaBeansContractBugsTool(), """
				class SafeBean {
				    private String name;
				    private String other;
				    private String URL;
				    private boolean active;
				    public String getName() { return this.name; }
				    public void setName(String name) { this.name = name; }
				    public String getURL() { return URL; }
				    public void setURL(String URL) { this.URL = URL; }
				    public boolean isActive() { return active; }
				    public void setActive(boolean active) { this.active = active; }
				    public String getFullName() { return name + " " + other; }
				}
				class ShadowedBean {
				    private String name;
				    private String other;
				    public String getName() {
				        String other = "derived";
				        return other;
				    }
				    public void setName(String value) {
				        String other = "local";
				        other = value;
				    }
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

}

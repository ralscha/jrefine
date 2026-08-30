package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiDesignPolicyToolsTest {

	@Test
	void reportsVisibilityAndEncapsulationProblems() {
		ToolResult result = inspect(new ReportEncapsulationPolicyIssuesTool(), """
				class PackageApi {
				    public int exposed;
				    protected int inherited;
				    int packageState;
				    public static final int CONSTANT = 1;
				    public PackageApi() {}
				    public class PublicNested {}
				    protected class ProtectedNested {}
				    class PackageNested {}
				    private class PrivateNested {}
				}
				class Peer {
				    private int secret;
				    int read(Peer other) { return other.secret; }
				}
				public class PublicApi {
				    private static class Hidden {}
				    public Hidden hidden() { return new Hidden(); }
				}
				""");

		assertMessages(result, "Public field", "Protected field", "Package-visible field", "Public nested type",
				"Protected nested type", "Package-visible nested type", "another object", "Public constructor",
				"less-visible type");
	}

	@Test
	void acceptsPrivateStateConstantsAndVisibilityCompatibleApis() {
		ToolResult result = inspect(new ReportEncapsulationPolicyIssuesTool(), """
				interface Contract {
				    int VALUE = 1;
				    class Nested {}
				}
				class PackageType {}
				class PackageApi {
				    private int state;
				    @Deprecated int injected;
				    public static final String NAME = "sample";
				    private class Nested {}
				    PackageApi() {}
				    public PackageType value() { return new PackageType(); }
				    int read(PackageApi other) { return other.getState(); }
				    void local() {
				        java.util.ArrayList<String> temporary = new java.util.ArrayList<>();
				    }
				    private int getState() { return this.state; }
				}
				public class PublicApi {
				    public PublicApi() {}
				    public java.time.Instant value() { return java.time.Instant.EPOCH; }
				}
				class ExternalName extends com.example.ExternalName {
				    private int state;
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsMutableStateStoredAndReturnedDirectly() {
		ToolResult result = inspect(new ReportMutableStateExposureTool(), """
				import java.util.Date;
				import java.util.List;
				class State {
				    private final List<String> values;
				    private final byte[] bytes;
				    private Date date;
				    State(List<String> values, byte[] bytes) {
				        this.values = values;
				        this.bytes = bytes;
				    }
				    void setDate(Date date) { this.date = date; }
				    List<String> values() { return values; }
				    byte[] bytes() { return this.bytes; }
				    Date date() { return date; }
				}
				""");

		assertMessages(result, "stored directly", "returned directly");
		long stored = messages(result).stream().filter(message -> message.contains("stored directly")).count();
		long returned = messages(result).stream().filter(message -> message.contains("returned directly")).count();
		assertTrue(stored == 3, messages(result).toString());
		assertTrue(returned == 3, messages(result).toString());
	}

	@Test
	void acceptsDefensiveCopiesImmutableValuesAndCustomNamesakes() {
		ToolResult result = inspect(new ReportMutableStateExposureTool(), """
				import java.util.List;
				class Safe {
				    private final List<String> values;
				    private final byte[] bytes;
				    private final String name;
				    Safe(List<String> values, byte[] bytes, String name) {
				        this.values = List.copyOf(values);
				        this.bytes = bytes.clone();
				        this.name = name;
				    }
				    List<String> values() { return List.copyOf(values); }
				    byte[] bytes() { return bytes.clone(); }
				    String name() { return name; }
				}
				class List<T> {
				    private List<T> value;
				    List(List<T> value) { this.value = value; }
				    List<T> value() { return value; }
				}
				@jakarta.persistence.Entity
				class EntityState {
				    private List<String> values;
				    EntityState(List<String> values) { this.values = values; }
				    List<String> values() { return values; }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsAbstractionAndInterfaceDesignProblems() {
		ToolResult result = inspect(new ReportAbstractionDesignIssuesTool(), """
				import java.util.ArrayList;
				import java.util.Optional;
				interface Contract {
				    void run();
				    String toString();
				}
				class Implementation implements Contract {
				    Optional<String> selected;
				    ArrayList<String> values = new ArrayList<>();
				    @Override public void run() {}
				    public void configure(boolean enabled) {}
				    public void extra(Optional<String> value, ArrayList<String> input) {}
				    public ArrayList<String> values() { return values; }
				    void inspect(Object value, Object other) {
				        if (value instanceof String) {}
				        else if (value instanceof Number) {}
				        if (this instanceof Contract) {}
				    }
				}
				class Base {
				    Child create() { return new Child(); }
				}
				class Child extends Base {}
				class Outer {
				    private void helper() {}
				    class Inner { void call() { helper(); } }
				}
				""");

		assertMessages(result, "Optional is used as a field", "Optional is used as a method", "boolean parameter",
				"Chain of instanceof", "'this' is tested", "concrete class", "not exposed through",
				"source-local subclass", "clashes with", "only from an inner class");
	}

	@Test
	void acceptsInterfaceTypesLocalOptionalAndSeparateTypeTests() {
		ToolResult result = inspect(new ReportAbstractionDesignIssuesTool(), """
				import java.util.List;
				import java.util.Optional;
				interface Contract { void run(); void endpoint(@Deprecated boolean enabled); }
				interface WaitPolicy { void wait(int retries); }
				interface VarargsContract { void values(String... values); }
				class Implementation implements Contract {
				    List<String> values;
				    @Override public void run() {}
				    @Override public boolean equals(Object other) { return this == other; }
				    public void endpoint(@Deprecated boolean enabled) {}
				    private void configure(boolean enabled) {}
				    void inspect(Object first, Object second) {
				        Optional<String> local = Optional.empty();
				        if (first instanceof String) {}
				        else if (second instanceof Number) {}
				        helper();
				    }
				    private void helper() {}
				    class Inner { void call() { helper(); } }
				}
				class Base {}
				class Child extends Base {}
				class VarargsImplementation implements VarargsContract {
				    public void values(String[] values) {}
				}
				class ArrayList<T> { ArrayList<T> value; }
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsOnlySourceProvenOverlyStrongCasts() {
		ToolResult result = inspect(new ReportAbstractionDesignIssuesTool(), """
				interface Operation { void run(); }
				interface Specialized extends Operation { void configure(String value); }
				class Casts {
				    void inspect(Object value) {
				        ((Specialized) value).run();
				        ((Specialized) value).configure("safe");
				        Specialized selected = (Specialized) value;
				    }
				}
				""");

		List<String> messages = messages(result);
		assertTrue(
				messages.stream()
					.anyMatch(message -> message.contains("stronger than required") && message.contains("Operation")),
				messages.toString());
		assertTrue(messages.stream().filter(message -> message.contains("stronger than required")).count() == 1,
				messages.toString());
	}

	@Test
	void skipsGeneratedSourcesAcrossThePolicyBatch() {
		String source = """
				// This file is generated by a schema compiler.
				import java.util.ArrayList;
				public class GeneratedModel {
				    public ArrayList<String> values;
				    public void configure(boolean enabled) {}
				    public ArrayList<String> values() { return values; }
				}
				""";

		assertTrue(inspect(new ReportEncapsulationPolicyIssuesTool(), source).findings().isEmpty());
		assertTrue(inspect(new ReportMutableStateExposureTool(), source).findings().isEmpty());
		assertTrue(inspect(new ReportAbstractionDesignIssuesTool(), source).findings().isEmpty());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Policy reporters must not change source");
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

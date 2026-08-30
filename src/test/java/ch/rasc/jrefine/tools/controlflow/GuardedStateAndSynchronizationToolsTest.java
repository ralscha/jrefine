package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardedStateAndSynchronizationToolsTest {

	@Test
	void reportsBrokenGuardAndImmutableContracts() {
		ToolResult result = inspect(new ReportGuardedStateIssuesTool(), """
				import net.jcip.annotations.GuardedBy;
				import net.jcip.annotations.Immutable;

				@Immutable
				class Value {
				    int mutable;
				    final int stable = 1;
				    static int cache;
				}

				class Sample {
				    private static final Object STATIC_LOCK = new Object();
				    private final Object lock = new Object();

				    @GuardedBy("STATIC_LOCK") private int perInstance;
				    @GuardedBy("lock") private static int shared;
				    @GuardedBy("this") private static int alsoShared;
				    @GuardedBy("lock") private int guarded;

				    @GuardedBy("lock") void guardedOperation() {}

				    void unsafe() {
				        guarded++;
				        guardedOperation();
				    }
				}
				""");

		assertMessages(result, "Non-final instance field", "Instance member is guarded",
				"Static member is guarded by an instance field", "Static member is guarded by this",
				"accessed without holding", "called without holding");
	}

	@Test
	void acceptsLexicallyHeldAndDeclaredGuards() {
		ToolResult result = inspect(new ReportGuardedStateIssuesTool(), """
				import javax.annotation.concurrent.GuardedBy;
				import javax.annotation.concurrent.Immutable;

				@Immutable
				class Value {
				    private final int stable;
				    Value(int stable) { this.stable = stable; }
				}

				class Sample {
				    private final Object lock = new Object();
				    @GuardedBy("lock") private int guarded;
				    @GuardedBy("lock") void guardedOperation() { guarded++; }

				    void safe() {
				        synchronized (lock) {
				            guarded++;
				            guardedOperation();
				        }
				    }

				    @GuardedBy("lock")
				    void callerPromisesGuard() {
				        guarded++;
				        guardedOperation();
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void ignoresUnrecognizedLookalikeAnnotations() {
		ToolResult result = inspect(new ReportGuardedStateIssuesTool(), """
				@interface GuardedBy { String value(); }
				@interface Immutable {}
				@Immutable class Value { int mutable; }
				class Sample {
				    @GuardedBy("missing") int value;
				    void read() { System.out.println(value); }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsInconsistentSynchronizationAndSharedStaticState() {
		ToolResult result = inspect(new ReportSynchronizationConsistencyIssuesTool(), """
				import java.text.SimpleDateFormat;
				import java.util.Date;
				class Sample {
				    private int count;
				    int externallyVisible;
				    private static final SimpleDateFormat FORMAT =
				            new SimpleDateFormat("yyyy-MM-dd");

				    synchronized void increment() {
				        count++;
				        externallyVisible++;
				    }

				    int count() { return count; }
				    String format(Date date) { return FORMAT.format(date); }
				}
				""");

		assertMessages(result, "both synchronized and unsynchronized", "Non-private field is accessed",
				"Non-thread-safe static field");
	}

	@Test
	void reportsUnsynchronizedMutationOfStaticCollection() {
		ToolResult result = inspect(new ReportSynchronizationConsistencyIssuesTool(), """
				import java.util.ArrayList;
				class Sample {
				    private static final ArrayList<String> VALUES = new ArrayList<>();
				    void add(String value) { VALUES.add(value); }
				}
				""");

		assertMessages(result, "Non-thread-safe static field");
	}

	@Test
	void acceptsConsistentAndConcurrencySafeState() {
		ToolResult result = inspect(new ReportSynchronizationConsistencyIssuesTool(), """
				import java.time.format.DateTimeFormatter;
				import java.util.concurrent.atomic.AtomicInteger;
				class Sample {
				    private int guarded;
				    private volatile int published;
				    private final AtomicInteger atomic = new AtomicInteger();
				    private static final DateTimeFormatter FORMAT =
				            DateTimeFormatter.ISO_LOCAL_DATE;

				    synchronized void increment() {
				        guarded++;
				        published++;
				        atomic.incrementAndGet();
				    }

				    synchronized int guarded() { return guarded; }
				    int published() { return published; }
				    int atomic() { return atomic.get(); }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Reporter must not change source");
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

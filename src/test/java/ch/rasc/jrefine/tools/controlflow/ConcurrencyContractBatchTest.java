package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyContractBatchTest {

	@Test
	void reportsMissingCoordinationCounterpartsAndStateChanges() {
		ToolResult result = inspect(new ReportThreadCoordinationIssuesTool(), """
				import java.util.concurrent.locks.Condition;
				class Sample {
				    private final Object waiting = new Object();
				    private final Object notifying = new Object();
				    private final Condition awaiting;
				    private final Condition signaling;

				    Sample(Condition awaiting, Condition signaling) {
				        this.awaiting = awaiting;
				        this.signaling = signaling;
				    }

				    void waitOnly() throws Exception {
				        synchronized (waiting) {
				            waiting.wait();
				        }
				    }

				    void notifyOnly() {
				        synchronized (notifying) {
				            notifying.notifyAll();
				        }
				    }

				    void awaitOnly() throws Exception { awaiting.await(); }
				    void signalOnly() { signaling.signalAll(); }
				}
				""");

		assertMessages(result, "no corresponding notify", "no corresponding wait", "no visible guarded-state change",
				"no corresponding signal", "no corresponding await");
	}

	@Test
	void acceptsPairedCoordinationWithVisibleStateMutation() {
		ToolResult result = inspect(new ReportThreadCoordinationIssuesTool(), """
				import java.util.concurrent.locks.Condition;
				class Sample {
				    private final Object monitor = new Object();
				    private final Condition condition;
				    private boolean ready;

				    Sample(Condition condition) { this.condition = condition; }

				    void waitForReady() throws Exception {
				        synchronized (monitor) {
				            while (!ready) { monitor.wait(); }
				        }
				        while (!ready) { condition.await(); }
				    }

				    void makeReady() {
				        synchronized (monitor) {
				            ready = true;
				            monitor.notifyAll();
				        }
				        condition.signalAll();
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void ignoresCoordinationOwnedOutsideTheSourceLocalClass() {
		ToolResult result = inspect(new ReportThreadCoordinationIssuesTool(), """
				import java.util.concurrent.locks.Condition;
				class Sample {
				    void use(Object external, Condition condition) throws Exception {
				        synchronized (external) { external.wait(); }
				        condition.await();
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsBrokenConcurrencyContracts() {
		ToolResult result = inspect(new ReportConcurrencyContractBugsTool(), """
				import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
				class Parent {
				    private static final Parent DEFAULT = new Child();
				    private static Parent instance;
				    private volatile long count;
				    private static final AtomicIntegerFieldUpdater<Parent> COUNT =
				            AtomicIntegerFieldUpdater.newUpdater(Parent.class, "count");

				    static Parent instance() {
				        if (instance == null) {
				            synchronized (Parent.class) {
				                if (instance == null) {
				                    instance = new Parent();
				                }
				            }
				        }
				        return instance;
				    }

				    synchronized void update(String value) {}
				}
				class Child extends Parent {
				    @Override void update(String value) {}
				}
				""");

		assertMessages(result, "Double-checked locking", "integer updater target",
				"Static initializer references source-local subclass",
				"Unsynchronized method overrides synchronized method");
	}

	@Test
	void reportsMissingAtomicUpdaterTarget() {
		ToolResult result = inspect(new ReportConcurrencyContractBugsTool(), """
				import java.util.concurrent.atomic.AtomicLongFieldUpdater;
				class Sample {
				    private static final AtomicLongFieldUpdater<Sample> MISSING =
				            AtomicLongFieldUpdater.newUpdater(Sample.class, "missing");
				}
				""");

		assertMessages(result, "target field does not exist");
	}

	@Test
	void acceptsConsistentConcurrencyContracts() {
		ToolResult result = inspect(new ReportConcurrencyContractBugsTool(), """
				import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
				class Parent {
				    static final int CONSTANT = 1;
				    private static final Class<?> CHILD_TYPE = Child.class;
				    private static final int INHERITED_CONSTANT = Child.CONSTANT;
				    private static final int CHILD_CONSTANT = Child.VALUE;
				    private static volatile Parent instance;
				    private volatile int count;
				    private static final AtomicIntegerFieldUpdater<Parent> COUNT =
				            AtomicIntegerFieldUpdater.newUpdater(Parent.class, "count");

				    static Parent instance() {
				        if (instance == null) {
				            synchronized (Parent.class) {
				                if (instance == null) {
				                    instance = new Parent();
				                }
				            }
				        }
				        return instance;
				    }

				    synchronized void update(String value) {}
				}
				class Child extends Parent {
				    static final int VALUE = 2;
				    @Override synchronized void update(String value) {}
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

package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.controlflow.ReportExceptionContractIssuesTool;
import ch.rasc.jrefine.tools.controlflow.ReportThreadingPolicyIssuesTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityPolicyToolsTest {

	@Test
	void reportsRiskyThreadCoordinationAndMonitorPolicies() {
		ToolResult result = inspect(new ReportThreadingPolicyIssuesTool(), """
				import java.util.concurrent.locks.Condition;
				class Worker extends Thread {
				    static final Object STATIC_LOCK = new Object();
				    static int shared;
				    ThreadLocal<String> context = new ThreadLocal<>();
				    boolean ready;
				    native void nativeWork();
				    native void overloaded(int value);
				    void overloaded(String value) {}
				    public synchronized void synchronizedWork() {}
				    void coordinate(Condition condition, Object parameter, Thread thread)
				            throws InterruptedException {
				        synchronized (this) {
				            shared++;
				            synchronized (STATIC_LOCK) {}
				            nativeWork();
				        }
				        synchronized (getClass()) {}
				        Object local = new Object();
				        synchronized (local) {}
				        while (ready) {}
				        wait();
				        notify();
				        condition.await();
				        condition.signal();
				        Thread.yield();
				        thread.setPriority(3);
				    }
				}
				""");

		assertMessages(result, "directly extends Thread", "Synchronized method", "ThreadLocal field",
				"wait() has no timeout", "await has no timeout", "notify() wakes", "signal() wakes", "Thread.yield()",
				"Thread.setPriority()", "Nested synchronized", "Synchronization on this",
				"static field as its exposed monitor", "locking only instance data", "Native method", "getClass()",
				"local variable or parameter", "Busy wait");
	}

	@Test
	void acceptsBoundedCoordinationAndPrivateStableMonitors() {
		ToolResult result = inspect(new ReportThreadingPolicyIssuesTool(), """
				import java.util.concurrent.TimeUnit;
				import java.util.concurrent.atomic.AtomicLong;
				import java.util.concurrent.locks.Condition;
				import java.util.concurrent.locks.LockSupport;
				class SafeWorker implements Runnable {
				    private static final ThreadLocal<String> CONTEXT =
				            ThreadLocal.withInitial(() -> "safe");
				    private static final AtomicLong COUNTER = new AtomicLong();
				    private static final long LIMIT = 10L;
				    private static int shared;
				    private final Object lock = new Object();
				    private volatile boolean ready;
				    private Other other;
				    native void nativeWork();
				    public void run() {
				        synchronized (lock) {
				            use(ready);
				            COUNTER.incrementAndGet();
				            use(COUNTER.get() < LIMIT);
				            use(other.shared);
				            overloaded("safe");
				        }
				        nativeWork();
				    }
				    void coordinate(Condition condition) throws InterruptedException {
				        condition.await(1, TimeUnit.SECONDS);
				        condition.signalAll();
				        while (ready) { LockSupport.parkNanos(1); }
				    }
				    private static void use(boolean value) {}
				}
				class Other { int shared; }
				class Thread {}
				class CustomWorker extends Thread {}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsExceptionContractsThatLoseContextOrOverbroadenHandling() {
		ToolResult result = inspect(new ReportExceptionContractIssuesTool(), """
				import java.io.IOException;
				import java.util.Optional;
				class RootFailure extends Throwable { int status; }
				class MutableFailure extends Exception { String detail; }
				class BrokenExceptions {
				    void broad() throws Exception {}
				    void unchecked() throws RuntimeException {}
				    void inspect(Optional<String> value) {
				        try {
				            throw new IllegalStateException();
				        }
				        catch (Exception failure) {
				            if (failure instanceof IllegalStateException) {
				                throw new RuntimeException();
				            }
				        }
				        try {
				            try { broad(); }
				            catch (IOException failure) { recover(); }
				        }
				        catch (Exception ignored) {}
				        value.orElseThrow(() -> { throw new IllegalStateException("missing"); });
				    }
				    void recover() {}
				}
				""");

		assertMessages(result, "directly extends Throwable", "exception state mutable", "Overly broad catch",
				"may ignore the caught exception", "instanceof on a catch", "does not retain the caught exception",
				"caught by that same try", "Nested try", "Overly broad throws", "redundantly declared",
				"without a message or cause", "supplier throws or returns null");
	}

	@Test
	void acceptsPreciseCatchesImmutableExceptionsAndReturningSuppliers() {
		ToolResult result = inspect(new ReportExceptionContractIssuesTool(), """
				import java.io.IOException;
				import java.sql.SQLException;
				import java.util.Optional;
				import java.util.function.Supplier;
				class DomainException extends Exception {
				    private final int code;
				    DomainException(String message, Throwable cause) {
				        super(message, cause);
				        this.code = 1;
				    }
				}
				class SafeExceptions {
				    void inspect(Optional<String> value) throws IOException {
				        try { work(); }
				        catch (IOException | SQLException failure) {
				            throw new IllegalStateException("failed", failure);
				        }
				        value.orElseThrow(() -> new IllegalStateException("missing"));
				    }
				    void ignoredFallback() {
				        try { work(); }
				        catch (IOException ignored) { fallback(); }
				        catch (SQLException expected) { fallback(); }
				    }
				    void interrupted() {
				        try { block(); }
				        catch (InterruptedException failure) {
				            Thread.currentThread().interrupt();
				        }
				    }
				    void work() throws IOException, SQLException {}
				    void block() throws InterruptedException {}
				    void fallback() {}
				}
				class Maybe {
				    void orElseThrow(Supplier<RuntimeException> supplier) {}
				    void custom() {
				        orElseThrow(() -> { throw new IllegalStateException("custom"); });
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsSerializationStateThatCannotBeReconstructed() {
		ToolResult result = inspect(new ReportSerializationStateBugsTool(), """
				import java.io.IOException;
				import java.io.Externalizable;
				import java.io.ObjectInputStream;
				import java.io.ObjectInput;
				import java.io.ObjectOutput;
				import java.io.ObjectOutputStream;
				import java.io.ObjectStreamField;
				import java.io.Serializable;
				import jakarta.servlet.http.HttpSession;
				class Payload {}
				class Base {
				    private Base(int value) {}
				}
				class BrokenState extends Base implements Serializable {
				    Payload payload;
				    int count;
				    transient String cache = "warm";
				    BrokenState() { super(1); }
				    private void readObject(ObjectInputStream input) throws IOException {
				        this.count = input.readInt();
				    }
				}
				record BrokenRecord(String value) implements Serializable {
				    private static final ObjectStreamField[] serialPersistentFields = {};
				    private void writeObject(ObjectOutputStream output) {}
				}
				class SessionWriter {
				    void bind(HttpSession session) {
				        session.setAttribute("payload", new Payload());
				    }
				}
				""");

		assertMessages(result, "non-serializable field 'payload'", "Transient field initializer",
				"does not restore this serialized field", "no accessible no-argument constructor",
				"record ignores serialPersistentFields", "record ignores this custom", "stored in HttpSession");
	}

	@Test
	void acceptsSerializableStateDefaultReadingAndExplicitTransientRestoration() {
		ToolResult result = inspect(new ReportSerializationStateBugsTool(), """
				import java.io.IOException;
				import java.io.ObjectInputStream;
				import java.io.Serializable;
				import jakarta.servlet.http.HttpSession;
				class SafePayload implements Serializable {}
				class SafeBase { protected SafeBase() {} }
				class SafeState extends SafeBase implements Serializable {
				    SafePayload payload;
				    ExternalValue external;
				    int count;
				    transient String cache = "warm";
				    private void readObject(ObjectInputStream input)
				            throws IOException, ClassNotFoundException {
				        input.defaultReadObject();
				        this.cache = input.readUTF();
				    }
				}
				class DelegatingState implements Serializable {
				    transient String cache = "warm";
				    private void readObject(ObjectInputStream input) { restoreState(); }
				    private void restoreState() { this.cache = "restored"; }
				}
				class UpdatingState implements Serializable {
				    String value;
				    private void readObject(ObjectInputStream input) {
				        Mapper.INSTANCE.readerForUpdating(this).readValue(input);
				    }
				}
				class Mapper {
				    static final Mapper INSTANCE = new Mapper();
				    Mapper readerForUpdating(Object target) { return this; }
				    void readValue(ObjectInputStream input) {}
				}
				class ExternalState implements Externalizable {
				    transient String cache = "constructed";
				    public ExternalState() {}
				    public void readExternal(ObjectInput input) {}
				    public void writeExternal(ObjectOutput output) {}
				}
				record SafeRecord(String value) implements Serializable {}
				class SessionWriter {
				    void bind(HttpSession session, SafePayload payload) {
				        session.setAttribute("payload", payload);
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

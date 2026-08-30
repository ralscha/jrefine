package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.declarations.ReportCodeMaturityIssuesTool;
import ch.rasc.jrefine.tools.expressions.ReportLocaleSensitiveCodeTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdditionalRuntimeSafetyToolsTest {

	@Test
	void reportsMechanicalConcurrencyBugs() {
		ToolResult result = inspect(new ReportConcurrencyApiBugsTool(), """
				import java.util.concurrent.ThreadLocalRandom;
				import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
				import java.util.concurrent.locks.Condition;
				import java.util.concurrent.locks.Lock;
				import java.util.concurrent.locks.ReentrantLock;
				class Sample {
				    private Object monitor = new Object();
				    private final Object other = new Object();
				    private final Lock lock = new ReentrantLock();
				    private final ThreadLocal<String> local = new ThreadLocal<>();
				    private final ThreadLocalRandom random = ThreadLocalRandom.current();
				    private static AtomicIntegerFieldUpdater<Sample> updater;
				    private volatile int count;
				    private volatile int[] values;

				    Sample(Thread worker) { worker.start(); }

				    void issues(Condition condition, Thread worker) throws Exception {
				        condition.await();
				        condition.wait();
				        local.set(null);
				        worker.stop();
				        System.runFinalizersOnExit(true);
				        new Thread();
				        String shared = "shared";
				        synchronized (shared) {}
				        synchronized (lock) { work(); }
				        synchronized (monitor) {
				            Thread.sleep(1);
				            synchronized (other) { monitor.wait(); }
				        }
				        Lock unsafe = new ReentrantLock();
				        unsafe.lock();
				        work();
				        unsafe.unlock();
				        try {
				            lock.lock();
				            work();
				        }
				        finally { lock.unlock(); }
				        count++;
				        count += 2;
				    }
				    void work() {}
				}
				""");

		assertMessages(result, "Condition.await", "called on a Condition", "ThreadLocal.set(null)",
				"Unsafe deprecated Thread.stop", "Thread is started", "runFinalizersOnExit", "default no-op run",
				"Empty synchronized", "shared literal", "Lock object", "non-final field", "Thread.sleep",
				"holding two monitors", "without a matching", "acquired inside the try block", "Non-atomic",
				"AtomicFieldUpdater", "ThreadLocalRandom", "Volatile array");
	}

	@Test
	void acceptsCanonicalConcurrencyPatterns() {
		ToolResult result = inspect(new ReportConcurrencyApiBugsTool(), """
				import java.util.concurrent.atomic.AtomicInteger;
				import java.util.concurrent.locks.Condition;
				import java.util.concurrent.locks.Lock;
				import java.util.concurrent.locks.ReentrantLock;
				class Sample {
				    private final Object monitor = new Object();
				    private final Lock lock = new ReentrantLock();
				    private final AtomicInteger count = new AtomicInteger();
				    private volatile boolean ready;
				    void waitForReady(Condition condition) throws Exception {
				        while (!ready) { condition.await(); }
				        synchronized (monitor) {
				            while (!ready) { monitor.wait(); }
				        }
				    }
				    void guarded() {
				        lock.lock();
				        try { count.incrementAndGet(); }
				        finally { lock.unlock(); }
				    }
				    Thread worker() { return new Thread(() -> guarded()); }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsIneffectiveAndReversedJunitAssertions() {
		ToolResult result = inspect(new ReportTestAssertionBugsTool(), """
				import static org.junit.jupiter.api.Assertions.assertEquals;
				import static org.junit.jupiter.api.Assertions.assertTrue;
				class Sample {
				    void test(boolean condition) {
				        assertTrue(true);
				        assertEquals(actual(), 1);
				        assertEquals(true, condition);
				        try { assertTrue(condition); }
				        catch (AssertionError ignored) {}
				    }
				    int actual() { return 1; }
				}
				""");

		assertMessages(result, "constant true", "arguments appear reversed", "dedicated boolean", "suppressed");
	}

	@Test
	void acceptsConventionalJunitAssertionsAndCustomApis() {
		ToolResult result = inspect(new ReportTestAssertionBugsTool(), """
				import static org.junit.jupiter.api.Assertions.assertEquals;
				import static org.junit.jupiter.api.Assertions.assertTrue;
				class Sample {
				    void test(boolean condition) {
				        assertEquals(1, actual());
				        assertTrue(condition);
				        try { assertTrue(condition); }
				        catch (AssertionError error) { throw error; }
				    }
				    int actual() { return 1; }
				}
				""");
		assertTrue(result.findings().isEmpty(), result.findings().toString());

		ToolResult custom = inspect(new ReportTestAssertionBugsTool(), """
				class Sample {
				    void assertEquals(int actual, int expected) {}
				    void run() { assertEquals(value(), 1); }
				    int value() { return 1; }
				}
				""");
		assertTrue(custom.findings().isEmpty(), custom.findings().toString());
	}

	@Test
	void understandsJunit4MessageOverloads() {
		ToolResult result = inspect(new ReportTestAssertionBugsTool(), """
				import static org.junit.Assert.assertEquals;
				import static org.junit.Assert.assertTrue;
				class Sample {
				    void test(String message, boolean condition, int actual) {
				        assertEquals(message, 1, actual);
				        assertTrue(message, condition);
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsImplicitLocaleAndCharsetUse() {
		ToolResult result = inspect(new ReportLocaleSensitiveCodeTool(), """
				import java.io.File;
				import java.io.FileReader;
				import java.io.FileWriter;
				import java.io.InputStream;
				import java.io.InputStreamReader;
				import java.io.OutputStream;
				import java.io.OutputStreamWriter;
				import java.text.SimpleDateFormat;
				class Sample {
				    void convert(String value, byte[] bytes, InputStream input,
				            OutputStream output, File file) throws Exception {
				        value.toLowerCase();
				        "title".toUpperCase();
				        value.getBytes();
				        new String(bytes);
				        new String(bytes, 0, bytes.length);
				        new SimpleDateFormat("yyyy-MM-dd");
				        new InputStreamReader(input);
				        new OutputStreamWriter(output);
				        new FileReader(file);
				        new FileWriter(file, true);
				    }
				}
				""");

		assertMessages(result, "default Locale", "getBytes", "byte decoding", "SimpleDateFormat", "InputStreamReader",
				"OutputStreamWriter", "FileReader", "FileWriter");
	}

	@Test
	void acceptsExplicitLocaleAndCharsetUse() {
		ToolResult result = inspect(new ReportLocaleSensitiveCodeTool(), """
				import java.io.InputStream;
				import java.io.InputStreamReader;
				import java.nio.charset.StandardCharsets;
				import java.text.SimpleDateFormat;
				import java.util.Locale;
				class Sample {
				    void convert(String value, byte[] bytes, InputStream input) {
				        value.toLowerCase(Locale.ROOT);
				        value.getBytes(StandardCharsets.UTF_8);
				        new String(bytes, StandardCharsets.UTF_8);
				        new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
				        new InputStreamReader(input, StandardCharsets.UTF_8);
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsFinalizationInTheMaturityTool() {
		ToolResult result = inspect(new ReportCodeMaturityIssuesTool(), """
				interface FinalizationContract { void finalize(); }
				class Sample {
				    public void finalize() throws Throwable { super.finalize(); }
				    void invoke(Sample other) throws Throwable { other.finalize(); }
				}
				""");

		assertMessages(result, "overrides deprecated finalization", "should not be public", "called explicitly");
		long explicitCalls = messages(result).stream().filter(message -> message.contains("called explicitly")).count();
		assertTrue(explicitCalls == 1, messages(result).toString());
		long declarations = messages(result).stream()
			.filter(message -> message.contains("overrides deprecated finalization"))
			.count();
		assertTrue(declarations == 1, messages(result).toString());
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

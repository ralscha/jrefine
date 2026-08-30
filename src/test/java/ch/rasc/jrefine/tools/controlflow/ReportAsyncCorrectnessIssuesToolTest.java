package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.JavaVersion;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportAsyncCorrectnessIssuesToolTest {

	private final ReportAsyncCorrectnessIssuesTool tool = new ReportAsyncCorrectnessIssuesTool();

	@Test
	void reportsIgnoredWorkUnobservedFutureAndExecutorLeak() {
		ToolResult result = inspect(TestSources.parse("""
				import java.util.concurrent.CompletableFuture;
				import java.util.concurrent.ExecutorService;
				import java.util.concurrent.Executors;
				class AsyncWork {
				    void run(ExecutorService shared) {
				        shared.submit(() -> work());
				        CompletableFuture.runAsync(() -> work());
				        CompletableFuture<String> future =
				                CompletableFuture.supplyAsync(() -> "value");
				        ExecutorService local = Executors.newFixedThreadPool(2);
				    }
				    void work() {}
				}
				"""));

		assertEquals(4, result.findings().size(), result.findings().toString());
		assertTrue(result.findings()
			.stream()
			.anyMatch(finding -> finding.message().contains("Asynchronous result is ignored")));
		assertTrue(result.findings()
			.stream()
			.anyMatch(finding -> finding.message().contains("Future result is never observed")));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.message().contains("without shutdown")));
	}

	@Test
	void acceptsObservedTransferredAndClosedWork() {
		ToolResult result = inspect(TestSources.parse("""
				import java.util.concurrent.CompletableFuture;
				import java.util.concurrent.ExecutorService;
				import java.util.concurrent.Executors;
				import java.util.concurrent.Future;
				import java.util.List;
				import java.util.function.BooleanSupplier;
				class ManagedAsyncWork {
				    String observed(ExecutorService shared) throws Exception {
				        Future<String> future = shared.submit(() -> "value");
				        return future.get();
				    }
				    CompletableFuture<String> transferred() {
				        CompletableFuture<String> future =
				                CompletableFuture.supplyAsync(() -> "value");
				        return future;
				    }
				    List<Future<String>> collected(ExecutorService shared, List<String> values) {
				        return values.stream().map(value -> shared.submit(() -> value)).toList();
				    }
				    boolean observedByMethodReference() {
				        CompletableFuture<Void> future = new CompletableFuture<>();
				        return await(future::isDone);
				    }
				    boolean await(BooleanSupplier condition) {
				        return condition.getAsBoolean();
				    }
				    void closed() {
				        ExecutorService local = Executors.newFixedThreadPool(2);
				        try {
				            local.execute(() -> {});
				        }
				        finally {
				            local.shutdown();
				        }
				    }
				}
				"""));

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsIgnoredContinuationAndJava25ScopedValueCandidate() {
		InspectionContext context = TestSources.parse("""
				import java.util.concurrent.CompletableFuture;
				class RequestContext {
				    private static final ThreadLocal<String> USER = new ThreadLocal<>();
				    void invoke(CompletableFuture<String> source, String user) {
				        CompletableFuture<String> local = source;
				        local.thenApply(String::trim);
				        USER.set(user);
				        try {
				            USER.get();
				        }
				        finally {
				            USER.remove();
				        }
				    }
				}
				""");

		ToolResult result = inspect(context);

		assertEquals(2, result.findings().size(), result.findings().toString());
		assertTrue(result.findings().stream().anyMatch(finding -> finding.message().contains("ScopedValue")));
		assertFalse(result.findings()
			.stream()
			.anyMatch(finding -> finding.message().contains("Future result is never observed")));

		InspectionContext java21 = new InspectionContext(context.path(), context.compilationUnit(), context.editor(),
				JavaVersion.JAVA_21);
		ToolResult earlierTarget = inspect(java21);
		assertEquals(1, earlierTarget.findings().size(), earlierTarget.findings().toString());
	}

	private ToolResult inspect(InspectionContext context) {
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Policy reporters must not change source");
		return result;
	}

}

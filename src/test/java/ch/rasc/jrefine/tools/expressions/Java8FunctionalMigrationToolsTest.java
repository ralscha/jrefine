package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java8FunctionalMigrationToolsTest {

	@Test
	void replacesAnonymousFunctionalInterfaceWithLambda() {
		String output = apply(new UseLambdaForAnonymousTool(), """
				class Sample {
				    void work() {}
				    Runnable task = new Runnable() {
				        @Override public void run() { work(); }
				    };
				}
				""");

		assertTrue(output.contains("Runnable task = () -> work()"), output);
		assertFalse(output.contains("new Runnable()"), output);
	}

	@Test
	void replacesAnonymousFunctionalInterfaceWithMethodReference() {
		String output = apply(new UseMethodReferenceForAnonymousTool(), """
				class Sample {
				    Runnable task = new Runnable() {
				        @Override public void run() { System.out.println(); }
				    };
				}
				""");

		assertTrue(output.contains("System.out::println"), output);
	}

	@Test
	void keepsAnonymousClassWhoseThisIdentityWouldChange() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    Runnable task = new Runnable() {
				        @Override public void run() { System.out.println(this); }
				    };
				}
				""");

		assertFalse(new UseLambdaForAnonymousTool().inspect(context, true).changed());
	}

	@Test
	void usesThreadAndThreadLocalLambdaAlternatives() {
		String thread = apply(new UseShorterLambdaAlternativeTool(), """
				class Sample { void start() {
				    new Thread() {
				        @Override public void run() { System.out.println("running"); }
				    }.start();
				} }
				""");
		assertTrue(thread.contains("new Thread(() -> System.out.println(\"running\"))"), thread);

		String local = apply(new UseShorterLambdaAlternativeTool(), """
				class Sample {
				    ThreadLocal<String> value = new ThreadLocal<String>() {
				        @Override protected String initialValue() { return "initial"; }
				    };
				}
				""");
		assertTrue(local.contains("ThreadLocal.withInitial(() -> \"initial\")"), local);
	}

	@Test
	void foldsRepeatedExpressionsIntoStreamAndStringJoin() {
		String output = apply(new FoldExpressionIntoStreamTool(), """
				class Sample {
				    boolean all(String a, String b, String c, String prefix) {
				        return a.startsWith(prefix) && b.startsWith(prefix) && c.startsWith(prefix);
				    }
				    String join(String a, String b, String c) { return a + "," + b + "," + c; }
				}
				""");

		assertTrue(output.contains("import java.util.stream.Stream;"), output);
		assertTrue(output.contains("Stream.of(a, b, c).allMatch"), output);
		assertTrue(output.contains("String.join(\",\", a, b, c)"), output);
	}

	@Test
	void replacesGuavaPseudoFunctionalCallWithStream() {
		String output = apply(new UseStreamForGuavaCallTool(), """
				import com.google.common.collect.Iterables;
				import java.util.List;
				import java.util.function.Function;
				class Sample { List<String> transform(
				        List<Integer> values, Function<Integer, String> function) {
				    return Iterables.transform(values, function);
				} }
				""");

		assertTrue(output.contains("values.stream().map(function).collect(Collectors.toList())"), output);
		assertTrue(output.contains("import java.util.stream.Collectors;"), output);
	}

	@Test
	void replacesGuavaFunctionalTypeAndFluentIterableChain() {
		String functional = apply(new ReplaceGuavaFunctionalPrimitivesTool(), """
				import com.google.common.base.Function;
				class Sample { Function<String, Integer> length = String::length; }
				""");
		assertTrue(functional.contains("java.util.function.Function<String, Integer> length"), functional);

		String fluent = apply(new ReplaceGuavaFunctionalPrimitivesTool(), """
				import com.google.common.collect.FluentIterable;
				import com.google.common.collect.ImmutableList;
				import java.util.List;
				class Sample { ImmutableList<String> convert(List<Integer> values) {
				    return FluentIterable.from(values).transform(Object::toString).toList();
				} }
				""");
		assertTrue(fluent.contains("List<String> convert"), fluent);
		assertTrue(fluent.contains("values.stream().map(Object::toString).collect(Collectors.toList())"), fluent);
	}

	@Test
	void replacesIdentityLambdaWithFunctionIdentity() {
		String output = apply(new UseMethodCallForLambdaTool(), """
				import java.util.function.Function;
				class Sample { Function<String, String> identity = value -> value; }
				""");

		assertTrue(output.contains("Function.identity()"), output);
	}

	@Test
	void simplifiesForEachFilterCollection() {
		String output = apply(new SimplifyForEachTool(), """
				import java.util.ArrayList;
				import java.util.List;
				class Sample { List<String> select(List<String> source, int size) {
				    List<String> result = new ArrayList<>();
				    source.forEach(value -> { if (value.length() > size) result.add(value); });
				    return result;
				} }
				""");

		assertTrue(output.contains("source.stream().filter(value -> value.length() > size)"), output);
		assertTrue(output.contains("collect(Collectors.toList())"), output);
		assertFalse(output.contains("source.forEach"), output);
	}

	@Test
	void simplifiesMapGetAndPutPatterns() {
		String output = apply(new SimplifyMapOperationsTool(), """
				import java.util.Map;
				class Sample {
				    String get(Map<String, String> map, String key) {
				        return map.containsKey(key) ? map.get(key) : "default";
				    }
				    void put(Map<String, String> map, String key, String value) {
				        if (!map.containsKey(key)) map.put(key, value);
				    }
				}
				""");

		assertTrue(output.contains("map.getOrDefault(key, \"default\")"), output);
		assertTrue(output.contains("map.putIfAbsent(key, value);"), output);
	}

	@Test
	void usesStandardLongHashCode() {
		String output = apply(new UseStandardHashCodeTool(), """
				class Sample { int hash(long value) { return (int) (value ^ (value >>> 32)); } }
				""");

		assertTrue(output.contains("Long.hashCode(value)"), output);
	}

	private static String apply(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertTrue(result.changed(), "Expected " + tool.id() + " to change the source");
		String output = TestSources.print(context);
		TestSources.parse(output);
		return output;
	}

}

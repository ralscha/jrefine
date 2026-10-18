package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.tools.controlflow.UseEnhancedSwitchTool;
import ch.rasc.jrefine.tools.controlflow.UseSwitchExpressionTool;
import ch.rasc.jrefine.tools.controlflow.CollapseLoopToStreamTool;
import ch.rasc.jrefine.tools.controlflow.MergeDuplicateSwitchBranchesTool;
import ch.rasc.jrefine.tools.controlflow.MergeIdenticalCatchBranchesTool;
import ch.rasc.jrefine.tools.controlflow.RemoveUnnecessaryEnumSwitchDefaultTool;
import ch.rasc.jrefine.tools.expressions.OptimizePerformanceExpressionsTool;
import ch.rasc.jrefine.tools.expressions.RemoveRedundantTypeCastTool;
import ch.rasc.jrefine.tools.expressions.SimplifyForEachTool;
import ch.rasc.jrefine.tools.expressions.UseBulkFileAttributesTool;
import ch.rasc.jrefine.tools.expressions.UseInstanceofPatternsTool;
import ch.rasc.jrefine.tools.expressions.UseFilesStringMethodsTool;
import ch.rasc.jrefine.tools.expressions.UseListSortTool;
import ch.rasc.jrefine.tools.syntax.NormalizeArrayDeclarationsTool;
import ch.rasc.jrefine.tools.syntax.RemoveUnnecessaryParenthesesTool;
import ch.rasc.jrefine.tools.syntax.RemoveUnnecessarySemicolonsTool;
import ch.rasc.jrefine.tools.types.UseDiamondOperatorTool;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewriteCompilationRegressionTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void keepsConstructorTypesNeededByLambdaArguments() throws Exception {
		String original = """
				import java.util.*;
				import java.util.function.Function;
				public class Sample {
				    static class Base {}
				    static class Person extends Base { String name() { return "Alice"; } }
				    static class Row<T extends Base, R> {
				        final Function<T, R> read;
				        Row(Function<T, R> read, Class<R> valueType) { this.read = read; }
				    }
				    public static boolean probe() {
				        List<Row> rows = new ArrayList<>();
				        rows.add(new Row<Person, String>(person -> person.name(), String.class));
				        rows.add(new Row<Person, String>(Person::name, String.class));
				        return rows.get(0).read.apply(new Person()).equals("Alice")
				            && rows.get(1).read.apply(new Person()).equals("Alice");
				    }
				}
				""";
		assertUnchangedAndCompiles(original, List.of(new UseDiamondOperatorTool()), "8");
		try (var loader = loader()) {
			assertTrue((Boolean) loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
	}

	@Test
	void mergesOnlyProvenUnrelatedCatchesWithoutChangingOverloadSelection() throws Exception {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source, """
				import java.io.*;
				import java.sql.SQLException;
				public class Sample {
				    static int handled;
				    static void log(IOException error) { handled += 1; }
				    static void log(SQLException error) { handled += 2; }
				    static void log(Exception error) { handled += 100; }
				    static void work(boolean io) throws IOException, SQLException {
				        if (io) throw new IOException("io");
				        throw new SQLException("sql");
				    }
				    static void overloaded(boolean io) {
				        try { work(io); }
				        catch (IOException error) { log(error); }
				        catch (SQLException error) { log(error); }
				    }
				    static String safe(boolean io) {
				        try { work(io); }
				        catch (IOException error) { return error.getMessage(); }
				        catch (SQLException error) { return error.getMessage(); }
				        return "";
				    }
				    static void ioWork() throws IOException { throw new UnsupportedEncodingException("encoding"); }
				    static String related() {
				        try { ioWork(); }
				        catch (UnsupportedEncodingException error) { return error.getMessage(); }
				        catch (IOException error) { return error.getMessage(); }
				        return "";
				    }
				    static class Parent extends RuntimeException {}
				    static class Child extends Parent {}
				    static void custom() {
				        try { throw new Child(); }
				        catch (Child error) { handled++; }
				        catch (Parent error) { handled++; }
				    }
				    public static boolean probe() {
				        overloaded(true); overloaded(false); custom();
				        return handled == 4 && safe(true).equals("io") && safe(false).equals("sql")
				            && related().equals("encoding");
				    }
				}
				""");
		List<InspectionTool> tools = List.of(new MergeIdenticalCatchBranchesTool());
		ModernizationReport report = new JRefineEngine().run(source, tools, true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		String output = Files.readString(source);
		assertTrue(output.contains("IOException | SQLException"), output);
		assertTrue(!output.contains("UnsupportedEncodingException | IOException"), output);
		assertTrue(output.contains("catch (Child error)"), output);
		assertEquals(0, ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "--release", "8", "-d", temporaryDirectory.toString(), source.toString()));
		try (var loader = loader()) {
			assertTrue((Boolean) loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
		assertEquals(0, new JRefineEngine().run(source, tools, true).changedFiles());
	}

	@Test
	void preservesListSortReceiversAndGenericInference() throws Exception {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source, """
				import java.util.*;
				public class Sample {
				    static String trace = "";
				    static Object values() { trace += "v"; return new ArrayList<>(Arrays.asList(3, 1, 2)); }
				    static Comparator<Integer> order() { trace += "c"; return Comparator.naturalOrder(); }
				    public static boolean probe() {
				        List<Integer> first = (List<Integer>) values();
				        List<Integer> second = new ArrayList<>(Arrays.asList(6, 4, 5));
				        Collections.sort((List<Integer>) (Object) first, order());
				        Collections.sort(first.size() == 3 ? first : second, Comparator.reverseOrder());
				        List<Integer> selected;
				        Collections.sort(selected = second);
				        Collections.sort(Collections.emptyList(), Comparator.<String>naturalOrder());
				        Collections.sort(new ArrayList<>(), Comparator.<String>naturalOrder());
				        boolean nullRejected = false;
				        try { Collections.sort((null)); }
				        catch (NullPointerException expected) { nullRejected = true; }
				        return trace.equals("vc") && first.equals(Arrays.asList(3, 2, 1))
				            && selected.equals(Arrays.asList(4, 5, 6)) && nullRejected;
				    }
				}
				""");
		List<InspectionTool> tools = List.of(new UseListSortTool(), new RemoveUnnecessaryParenthesesTool());
		ModernizationReport report = new JRefineEngine().run(source, tools, true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertTrue(report.changedFiles() > 0);
		String output = Files.readString(source);
		assertTrue(output.contains("((List<Integer>) (Object) first).sort(order())"), output);
		assertTrue(output.contains("(first.size() == 3 ? first : second).sort"), output);
		assertTrue(output.contains("(selected = second).sort(null)"), output);
		assertTrue(output.contains("Collections.sort(Collections.emptyList()"), output);
		assertTrue(output.contains("Collections.sort(new ArrayList<>()"), output);
		assertEquals(0, ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "--release", "8", "-d", temporaryDirectory.toString(), source.toString()));
		try (var loader = loader()) {
			assertTrue((Boolean) loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
		assertEquals(0, new JRefineEngine().run(source, tools, true).changedFiles());
	}

	@Test
	void createsLegalDistinctPatternNamesAndPreservesMutableReads() throws Exception {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source,
				"""
						import java.util.List;
						public class Sample {
						    static Object field = Boolean.TRUE;
						    static boolean replace() { field = Boolean.FALSE; return true; }
						    static boolean mutable() { return field instanceof Boolean && (replace() && (Boolean) field); }
						    static boolean local(Object value) {
						        return value instanceof Boolean && (((value = Boolean.FALSE) != null) && (Boolean) value);
						    }
						    static boolean legal(Object a, Object b, Object c, Object d, boolean booleanValue, boolean booleanValue2) {
						        return (a instanceof Boolean && (Boolean) a) && (b instanceof Boolean && (Boolean) b)
						            && (c instanceof Class<?> && ((Class<?>) c).isPrimitive())
						            && (d instanceof List<?> && !((List<?>) d).isEmpty());
						    }
						    public static boolean probe() {
						        return !mutable() && !local(Boolean.TRUE) && legal(true, true, int.class, List.of(1), false, false);
						    }
						}
						""");
		List<InspectionTool> tools = List.of(new UseInstanceofPatternsTool());
		ModernizationReport report = new JRefineEngine().run(source, tools, true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertTrue(report.changedFiles() > 0);
		String output = Files.readString(source);
		assertTrue(output.contains("instanceof Boolean booleanValue3"), output);
		assertTrue(output.contains("instanceof Class<?> classValue"), output);
		assertEquals(0, ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "--release", "17", "-d", temporaryDirectory.toString(), source.toString()));
		try (var loader = loader()) {
			assertTrue((Boolean) loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
		assertEquals(0, new JRefineEngine().run(source, tools, true).changedFiles());
	}

	@Test
	void preservesFileEncodingReplacementAndStringCharsetOverloads() throws Exception {
		String original = """
				import java.nio.file.Files;
				import java.nio.file.Path;
				import java.nio.charset.StandardCharsets;
				import java.io.UnsupportedEncodingException;
				public class Sample {
				    public static boolean probe(String folder) throws Exception {
				        Path file = Path.of(folder, "encoded.txt");
				        Files.write(file, new byte[] { (byte) 0xc3 });
				        String replaced = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				        String malformed = String.valueOf((char) 0xd800);
				        Files.write(file, malformed.getBytes(StandardCharsets.UTF_8));
				        boolean written = Files.readAllBytes(file)[0] == '?';
				        String encoding = "UTF-8";
				        Files.write(file, "valid".getBytes(encoding));
				        String restored = new String(Files.readAllBytes(file), encoding);
				        boolean unsupported = false;
				        try { Files.write(file, "valid".getBytes("missing-charset")); }
				        catch (UnsupportedEncodingException expected) { unsupported = true; }
				        return replaced.equals(String.valueOf((char) 0xfffd)) && written
				            && restored.equals("valid") && unsupported;
				    }
				}
				""";
		assertUnchangedAndCompiles(original, List.of(new UseFilesStringMethodsTool()), "17");
		try (var loader = loader()) {
			assertTrue((Boolean) loader.loadClass("Sample")
				.getMethod("probe", String.class)
				.invoke(null, temporaryDirectory.toString()));
		}
	}

	@Test
	void keepsGenericCastsRequiredForAssignmentAndReturn() throws Exception {
		String original = """
				import java.util.List;
				class Sample {
				    interface Value<T> {}
				    <X> Value<X> convert(Value<?> current) { return (Value<X>) current; }
				    <X> void assign(Value<?> current) { Value<X> result = (Value<X>) current; }
				    <X> void reassign(Value<?> current, Value<X> target) { target = (Value<X>) current; }
				    List<String> raw(List current) { return (List<String>) current; }
				    String[] array(Object[] current) { return (String[]) current; }
				}
				""";
		assertUnchangedAndCompiles(original, List.of(new RemoveRedundantTypeCastTool()));
	}

	@Test
	void keepsDefaultFallThroughLabelsAndAssignmentFallThrough() throws Exception {
		String original = """
				public class Sample {
				    static int value(int input) {
				        switch (input) {
				            case 1: return 1;
				            case 2:
				            default: return 2;
				        }
				    }
				    static int assign(int input) {
				        int value;
				        switch (input) {
				            case 1: value = 1;
				            default: value = 2;
				        }
				        return value;
				    }
				    public static int probe() { return value(1) + value(2) + value(3) + assign(1); }
				}
				""";
		assertUnchangedAndCompiles(original, List.of(new UseEnhancedSwitchTool(), new UseSwitchExpressionTool()));
		try (var loader = loader()) {
			assertEquals(7, loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
	}

	@Test
	void keepsStreamExceptionsConstructorEffectsNullChecksAndRandomSequences() throws Exception {
		String original = """
				import java.io.*;
				import java.util.Random;
				public class Sample {
				    enum Color { RED }
				    static int constructions;
				    Sample() { constructions++; }
				    static InputStream input(File file) throws FileNotFoundException { return new FileInputStream(file); }
				    static OutputStream output(File file) throws FileNotFoundException { return new FileOutputStream(file); }
				    static boolean exists(File file) throws IOException { return file.isFile() && file.length() > 0; }
				    static boolean same(Color left, Color right) { return left.equals(right); }
				    static int random(Random random) { return (int) (random.nextDouble() * 10); }
				    static String observe(StringBuilder builder) { return builder.toString(); }
				    public static boolean probe(File missing) throws Exception {
				        try { input(missing); return false; } catch (FileNotFoundException expected) {}
				        try { output(missing); return false; } catch (FileNotFoundException expected) {}
				        Class<?> type = new Sample().getClass();
				        Boolean first = new Boolean(true);
				        Boolean second = new Boolean(true);
				        try { same(null, Color.RED); return false; } catch (NullPointerException expected) {}
				        StringBuilder builder = new StringBuilder("start");
				        builder.append("prefix" + observe(builder));
				        Random actual = new Random(42);
				        Random expected = new Random(42);
				        return constructions == 1 && type == Sample.class && first != second
				            && builder.toString().equals("startprefixstart")
				            && random(actual) == (int) (expected.nextDouble() * 10) && !exists(missing);
				    }
				}
				""";
		assertUnchangedAndCompiles(original,
				List.of(new OptimizePerformanceExpressionsTool(), new UseBulkFileAttributesTool()));
		try (var loader = loader()) {
			assertTrue((Boolean) loader.loadClass("Sample")
				.getMethod("probe", java.io.File.class)
				.invoke(null, temporaryDirectory.resolve("missing-parent").resolve("file").toFile()));
		}
	}

	private void assertUnchangedAndCompiles(String original, List<InspectionTool> tools) throws Exception {
		assertUnchangedAndCompiles(original, tools, "17");
	}

	@Test
	void keepsAccumulatorDependentFiltersAndCheckedExceptionLoops() throws Exception {
		String original = """
				import java.io.IOException;
				import java.util.ArrayList;
				import java.util.List;
				public class Sample {
				    interface Checked { boolean valid() throws IOException; }
				    static String selected;
				    static String choose(Object value) { return "object"; }
				    static String choose(String value) { return "string"; }
				    static void covariant(List<String> source) {
				        for (Object value : source) {
				            if (value != null) selected = choose(value);
				        }
				    }
				    static boolean isBlank(Object value) { return false; }
				    static boolean isBlank(String value) { return true; }
				    static boolean mapped(List<String> source) {
				        for (String value : source) {
				            Object trimmed = value.trim();
				            if (isBlank(trimmed)) return false;
				        }
				        return true;
				    }
				    static List<String> unique(List<String> source) {
				        List<String> result = new ArrayList<>();
				        source.forEach(value -> { if (!result.contains(value)) result.add(value); });
				        return result;
				    }
				    void process(List<String> source) throws IOException {
				        for (String value : source) {
				            if (!value.isEmpty()) accept(value);
				        }
				    }
				    void accept(String value) throws IOException { throw new IOException(value); }
				    boolean validate(List<Checked> source) throws IOException {
				        for (Checked value : source) {
				            if (!value.valid()) return false;
				        }
				        return true;
				    }
				    public static int probe() {
				        covariant(List.of("x"));
				        return "object".equals(selected) && mapped(List.of("x")) ? unique(List.of("a", "b", "a")).size() : -1;
				    }
				}
				""";
		assertUnchangedAndCompiles(original, List.of(new CollapseLoopToStreamTool(), new SimplifyForEachTool()));
		try (var loader = loader()) {
			assertEquals(2, loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
	}

	@Test
	void keepsNullAndConstantSwitchLabelsSeparate() throws Exception {
		String original = """
				public class Sample {
				    static boolean empty(Integer value) {
				        return switch (value) {
				            case 0 -> true;
				            case null -> true;
				            default -> false;
				        };
				    }
				    public static boolean probe() { return empty(null) && empty(0) && !empty(1); }
				}
				""";
		assertUnchangedAndCompiles(original, List.of(new MergeDuplicateSwitchBranchesTool()), "21");
		try (var loader = loader()) {
			assertTrue((Boolean) loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
	}

	private void assertUnchangedAndCompiles(String original, List<InspectionTool> tools, String release)
			throws Exception {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source, original);
		ModernizationReport report = new JRefineEngine().run(source, tools, true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertEquals(original, Files.readString(source));
		assertEquals(0, ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "--release", release, "-d", temporaryDirectory.toString(), source.toString()));
	}

	private URLClassLoader loader() throws Exception {
		return new URLClassLoader(new URL[] { temporaryDirectory.toUri().toURL() });
	}

	@Test
	void keepsRequiredSemicolonsInEnumConstantBodiesAndEmptyEnums() throws Exception {
		String original = """
				public class Sample {
				    enum Mode {
				        FIRST {
				            @Override boolean enabled() { return true; }
				        },
				        SECOND;
				        boolean enabled() { return false; }
				    }
				    enum Empty { ; int marker; }
				    public static boolean probe() { return Mode.FIRST.enabled() && !Mode.SECOND.enabled() && Empty.values().length == 0; }
				}
				""";
		assertUnchangedAndCompiles(original, List.of(new RemoveUnnecessarySemicolonsTool()));
		try (var loader = loader()) {
			assertTrue((Boolean) loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
	}

	@Test
	void preservesEnumDefaultReturnsAndNullLabels() throws Exception {
		String original = """
				class Sample {
				    enum State { READY, DONE }
				    int value(State state) {
				        switch (state) {
				            case READY: return 1;
				            case DONE: return 2;
				            default: return 3;
				        }
				    }
				    void nullable(State state) {
				        switch (state) {
				            case READY -> System.out.println("ready");
				            case DONE -> System.out.println("done");
				            case null, default -> {}
				        }
				    }
				}
				""";
		assertUnchangedAndCompiles(original, List.of(new RemoveUnnecessaryEnumSwitchDefaultTool()), "21");
	}

	@Test
	void preservesTokenBoundariesWhenRemovingParentheses() throws Exception {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source, """
				public class Sample {
				    static boolean check(int number) { assert(number > 0); return(number > 0); }
				    static boolean string(Object value) { return (value)instanceof String; }
				    public static int probe() { int value = 2; return check(1) && string("x") ? 5+(+value) : 0; }
				}
				""");
		ModernizationReport report = new JRefineEngine().run(source, List.of(new RemoveUnnecessaryParenthesesTool()),
				true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		String output = Files.readString(source);
		assertTrue(output.contains("assert number > 0;"), output);
		assertTrue(output.contains("return number > 0;"), output);
		assertEquals(0, ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "-d", temporaryDirectory.toString(), source.toString()));
		try (var loader = loader()) {
			assertEquals(7, loader.loadClass("Sample").getMethod("probe").invoke(null));
		}
	}

	@Test
	void normalizesMultidimensionalArraysWithoutChangingSharedDeclarationsOrAnnotations() throws Exception {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source, """
				import java.lang.annotation.*;
				@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface A {}
				@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface B {}
				public class Sample {
				    public int matrix[][];
				    public int mixed[], scalar;
				    public String annotated @A [] @B [];
				}
				""");
		List<InspectionTool> tools = List.of(new NormalizeArrayDeclarationsTool());
		ModernizationReport report = new JRefineEngine().run(source, tools, true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertTrue(Files.readString(source).contains("int[][] matrix"));
		assertEquals(0, ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "-d", temporaryDirectory.toString(), source.toString()));
		try (var loader = loader()) {
			Class<?> sample = loader.loadClass("Sample");
			assertEquals(int[][].class, sample.getField("matrix").getType());
			assertEquals(int[].class, sample.getField("mixed").getType());
			assertEquals(int.class, sample.getField("scalar").getType());
			var outer = (java.lang.reflect.AnnotatedArrayType) sample.getField("annotated").getAnnotatedType();
			assertEquals("A", outer.getAnnotations()[0].annotationType().getSimpleName());
			assertEquals("B",
					outer.getAnnotatedGenericComponentType().getAnnotations()[0].annotationType().getSimpleName());
		}
		assertEquals(0, new JRefineEngine().run(source, tools, true).changedFiles());
	}

}

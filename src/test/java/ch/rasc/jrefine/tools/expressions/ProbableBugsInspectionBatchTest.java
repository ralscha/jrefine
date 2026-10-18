package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import java.util.List;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.controlflow.ReportAssertionControlFlowBugsTool;
import ch.rasc.jrefine.tools.declarations.ReportDeclarationContractBugsTool;
import ch.rasc.jrefine.tools.declarations.ReportNullabilityBugsTool;
import ch.rasc.jrefine.tools.declarations.ReportStateUsageBugsTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbableBugsInspectionBatchTest {

	@Test
	void reportsAssertionAndControlFlowBugs() {
		ToolResult result = inspect(new ReportAssertionControlFlowBugsTool(), """
				import java.util.stream.Stream;
				class Sample {
				    void run(boolean left, boolean right) {
				        int state = 0;
				        assert ++state > 0;
				        assert "value".equals("value");
				        assert true;
				        if (false) System.out.println("never");
				        if ((left == right) & true) System.out.println("bitwise");
				        while (false) {}
				        Stream.generate(() -> "x").count();
				        return;
				        System.out.println("unreachable");
				    }
				}
				""");

		assertMessages(result, "side effects", "Constant condition", "same value", "Non-short-circuit", "empty body",
				"unbounded Stream", "Unreachable");
		assertEquals(1,
				result.findings().stream().filter(finding -> finding.message().contains("side effects")).count());
	}

	@Test
	void reportsEqualityAndComparisonBugs() {
		ToolResult result = inspect(new ReportEqualityContractBugsTool(), """
				class Sample implements Comparable<Sample> {
				    int value;
				    public int compareTo(Sample other) { return value - other.value; }
				    boolean equal(Sample other) { return true; }
				    boolean equals(String other) { return true; }
				    boolean arrays(int[] left, int[] right) { return left == right; }
				    boolean strings(String left, String right) { return left == right; }
				    boolean self(String value) { return value.equals(value); }
				}
				""");

		assertMessages(result, "equal()", "Covariant equals", "Array comparison", "String comparison",
				"compared with itself", "Subtraction");
	}

	@Test
	void acceptsInheritedDelegatedAndIdentityEquality() {
		ToolResult result = inspect(new ReportEqualityContractBugsTool(), """
				class Base {
				    public boolean equals(Object other) { return this == other; }
				    public int hashCode() { return System.identityHashCode(this); }
				}
				class Inherited extends Base implements Comparable<Inherited> {
				    public int compareTo(Inherited other) { return Integer.compare(hashCode(), other.hashCode()); }
				}
				class Delegated extends Base {
				    public boolean equals(Object other) { return super.equals(other); }
				}
				class HashDelegated {
				    public int hashCode() { return super.hashCode(); }
				}
				""");
		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void acceptsNormalizedComparisonResultsArithmeticAndEquivalentIntegerBounds() {
		ToolResult result = inspect(new ReportEqualityContractBugsTool(), """
				import java.math.BigDecimal;
				import java.util.Date;
				class Sample implements Comparable<Sample> {
				    public int compareTo(Sample other) { return Integer.compare(hashCode(), other.hashCode()); }
				    boolean decimals(BigDecimal first, BigDecimal second) { return first.compareTo(second) == 1; }
				    boolean dates(Date first, Date second) { return first.compareTo(second) != -1; }
				    int reverse(String first, String second) { return first.compareTo(second) * -1; }
				    boolean bounds(String first, String second) { return first.compareTo(second) < 1; }
				    boolean reversedBounds(String first, String second) { return -1 < first.compareTo(second); }
				    boolean suspicious(String first, String second) { return first.compareTo(second) == 1; }
				}
				""");
		assertEquals(1, result.findings().size(), result.findings().toString());
		assertMessages(result, "compared with zero");
	}

	@Test
	void stillReportsUncheckedEqualityParameterCasts() {
		ToolResult result = inspect(new ReportEqualityContractBugsTool(), """
				class Sample {
				    int value;
				    public boolean equals(Object other) { return value == ((Sample) other).value; }
				    public int hashCode() { return value; }
				}
				""");
		assertMessages(result, "does not check the class");
		assertNoMessages(result, "Non-final field");
	}

	@Test
	void acceptsDynamicToStringBroaderInstanceofCastsAndComputedComparatorReturns() {
		ToolResult result = inspect(new ReportEqualityContractBugsTool(), """
				import java.util.List;
				class Sample {
				    String render(Object value) { return value.toString(); }
				    List<?> list(Object value) {
				        if (value instanceof Iterable<?>) {
				            return (List<?>) value;
				        }
				        return List.of();
				    }
				    int compare(Sample left, Sample right) {
				        if (left == right) return -1;
				        if (left != right) return 1;
				        return Integer.compare(left.hashCode(), right.hashCode());
				    }
				    void verifyReflexivity(Sample value) {
				        assert value.equals(value);
				        assertThat(value.equals(value));
				    }
				    void assertThat(boolean condition) {}
				}
				""");

		assertNoMessages(result, "default Object.toString", "Cast conflicts",
				"does not represent all comparison outcomes", "compared with itself");
	}

	@Test
	void acceptsGeneratedEqualityAndIdentityFastPaths() {
		ToolResult result = inspect(new ReportEqualityContractBugsTool(), """
				import org.immutables.value.Value;
				@Value.Immutable
				abstract class Generated implements Comparable<Generated> {
				    public int compareTo(Generated other) { return 0; }
				}
				class Sample {
				    int compare(Object left, Object right) {
				        if (left == right) return 0;
				        return left.toString().compareTo(right.toString());
				    }
				    boolean equal(Object left, Object right) {
				        return left == right || (left != null && left.equals(right));
				    }
				}
				""");

		assertNoMessages(result, "Comparable is implemented", "Object comparison uses identity equality");
	}

	@Test
	void reportsCollectionArrayAndVarargsBugs() {
		ToolResult result = inspect(new ReportCollectionArrayBugsTool(), """
				import java.util.*;
				class Sample implements Iterator<String> {
				    public boolean hasNext() { return next() != null; }
				    public String next() { return null; }
				    static void accept(Object... values) {}
				    void use(List<String> list, Map<String, String> map,
				            Properties properties) {
				        list.addAll(list);
				        accept(new int[2]);
				        map.put("key", "first");
				        map.put("key", "second");
				        Set.of("a", "a");
				        properties.put("key", "value");
				    }
				    void removeByIndex(List<String> list, int index, Integer element) {
				        list.remove(0);
				        list.remove(index);
				        list.remove(element);
				    }
				    void arrays(int[] values, int index) {
				        values[index++] = 1;
				        values[index++] = 2;
				        values[0] = 1;
				        values[0] = values[0] + 1;
				        values[1] = 1;
				        values[1] = 2;
				    }
				}
				""");

		assertMessages(result, "advances", "NoSuchElementException", "itself", "primitive array", "overwritten",
				"duplicate element", "Hashtable");
		assertEquals(2,
				result.findings().stream().filter(finding -> finding.message().contains("overwritten")).count());
		assertEquals(1,
				result.findings()
					.stream()
					.filter(finding -> finding.message().contains("incompatible element"))
					.count());
	}

	@Test
	void acceptsAnArrayForwardedToMatchingVarargs() {
		ToolResult result = inspect(new ReportCollectionArrayBugsTool(), """
				class Sample {
				    static void accept(int... values) {}
				    static void forward(int... values) { accept(values); }
				}
				""");

		assertNoMessages(result, "Confusing single argument", "Confusing primitive array");
	}

	@Test
	void acceptsListDuplicatesAndThirdPartyPutAccumulation() {
		ToolResult result = inspect(new ReportCollectionArrayBugsTool(), """
				import java.util.List;
				class MultiMap<K, V> { void put(K key, V value) {} }
				class Sample {
				    void strings() {
				        List<String> list = List.of();
				        list.contains("value");
				    }
				    void integers() {
				        List<Integer> list = new java.util.ArrayList<>();
				        list.add(1);
				    }
				    void use(List<String> list, MultiMap<String, Integer> values) {
				        list.add("same");
				        list.add("same");
				        values.put("key", 1);
				        values.put("key", 2);
				    }
				}
				""");

		assertNoMessages(result, "overwritten by a consecutive write");
	}

	@Test
	void acceptsSelfAdditionExercisedByAnAssertion() {
		ToolResult result = inspect(new ReportCollectionArrayBugsTool(), """
				import java.util.List;
				class Sample {
				    void assertThrows(Runnable action) {}
				    void use(List<String> values) {
				        assertThrows(() -> values.addAll(values));
				    }
				}
				""");

		assertNoMessages(result, "added to itself");
	}

	@Test
	void reportsCommonApiMisuse() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.Optional;
				class Sample {
				    int random() { return (int) Math.random(); }
				    void use(Optional<String> optional, int divisor) throws Exception {
				        new Exception("ignored");
				        new StringBuilder('x');
				        optional.get();
				        Optional.ofNullable(null);
				        "text".split("*");
				        double ratio = 1 / divisor;
				    }
				}
				""");

		assertMessages(result, "always produces zero", "Throwable", "interprets char", "presence check",
				"Optional.empty", "metacharacter", "integer division");
	}

	@Test
	void restrictsMagicConstantsToKnownJdkApisAndKeepsLambdaResults() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.ArrayList;
				import java.util.Calendar;
				import java.util.Comparator;
				import java.util.List;
				class Sample {
				    void set(int index, Object value) {}
				    void use(Calendar calendar, Thread thread) {
				        set(0, "value");
				        calendar.set(1, 2026);
				        thread.setPriority(5);
				        List<String> values = new ArrayList<>();
				        values.sort(Comparator.comparing(value -> value.substring(1)));
				    }
				}
				""");

		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		assertEquals(2, messages.stream().filter(message -> message.contains("magic constant")).count(),
				messages.toString());
		assertNoMessages(result, "Result of method call is ignored");
	}

	@Test
	void acceptsLiteralRegexCharactersAndCalendarDateOverloads() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.Calendar;
				class Sample {
				    void use(String text, Calendar calendar) {
				        text.split("-");
				        text.split("]");
				        text.split("}");
				        calendar.set(2019, 6, 1);
				        calendar.set(2019, 6, 1, 12, 0, 0);
				    }
				}
				""");
		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void acceptsNumericParsingUsedToValidateInput() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				class Sample {
				    boolean valid(String text) {
				        try { Long.parseLong(text); return true; }
				        catch (NumberFormatException e) { return false; }
				    }
				    void unused(String text) { Long.parseLong(text); }
				}
				""");
		assertEquals(1,
				result.findings()
					.stream()
					.filter(finding -> finding.message().equals("Result of method call is ignored"))
					.count());
	}

	@Test
	void recognizesElseAndFilteredOptionalGuardsWithoutAcceptingDisjunctions() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.Optional;
				class Sample {
				    String filtered(Optional<String> value) {
				        if (value.filter(String::isBlank).isPresent()) { return value.get(); }
				        return "";
				    }
				    String otherwise(Optional<String> value) {
				        if (value.isEmpty()) { return ""; } else { return value.get(); }
				    }
				    String unsafe(Optional<String> value, boolean override) {
				        if (value.isPresent() || override) { return value.get(); }
				        return "";
				    }
				    String absent(Optional<String> value) {
				        if (!value.isPresent()) { return value.get(); }
				        return "";
				    }
				}
				""");
		assertEquals(2,
				result.findings().stream().filter(finding -> finding.message().contains("presence check")).count(),
				result.findings().toString());
	}

	@Test
	void acceptsAssertedAndConstructedOptionalValuesButChecksReassignments() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.Optional;
				import static org.junit.jupiter.api.Assertions.*;
				import static org.assertj.core.api.Assertions.assertThat;
				class Sample {
				    String asserted(Optional<String> value) {
				        assertTrue(value.isPresent());
				        return value.get();
				    }
				    String fluent(Optional<String> value) {
				        assertThat(value).isPresent();
				        return value.get();
				    }
				    void assertionArguments(Optional<String> value) {
				        assertNotNull(value.get());
				        assertThat(value.get()).isEqualTo("expected");
				    }
				    String constructed(String input) {
				        Optional<String> value = Optional.of(input);
				        return value.get();
				    }
				    String reassigned(Optional<String> value) {
				        assertTrue(value.isPresent());
				        value = Optional.empty();
				        return value.get();
				    }
				    String nullable(String input) {
				        Optional<String> value = Optional.ofNullable(input);
				        return value.get();
				    }
				    String negated(Optional<String> value, boolean invalid) {
				        if (!(invalid || value.isEmpty())) return value.get();
				        return "";
				    }
				}
				""");
		assertEquals(2, result.findings().stream().filter(f -> f.message().contains("presence check")).count(),
				result.findings().toString());
	}

	@Test
	void doesNotTreatLookalikeAssertionsAsOptionalGuards() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.Optional;
				import static org.junit.jupiter.api.Assertions.*;
				class Sample {
				    void assertTrue(boolean ignored) {}
				    String use(Optional<String> value) {
				        assertTrue(value.isPresent());
				        return value.get();
				    }
				}
				""");
		assertMessages(result, "presence check");
	}

	@Test
	void checksOnlyDateTimeOperationsThatRequireSupportedUnitsOrFields() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.time.LocalDate;
				import java.time.LocalTime;
				import java.time.temporal.ChronoUnit;
				import java.time.temporal.ChronoField;
				class Sample {
				    void use(LocalDate date, LocalTime time) {
				        date.isSupported(ChronoUnit.HOURS);
				        time.truncatedTo(ChronoUnit.DAYS);
				        date.plus(1, ChronoUnit.HOURS);
				        date.with(ChronoField.HOUR_OF_DAY, 1);
				        time.get(ChronoField.DAY_OF_YEAR);
				    }
				}
				""");
		assertEquals(3, result.findings().stream().filter(f -> f.message().contains("unsupported")).count(),
				result.findings().toString());
	}

	@Test
	void acceptsImmediateBooleanAliasesOfOptionalPresence() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.Optional;
				class Sample {
				    String use(Optional<String> value, boolean createNew) {
				        createNew = value.isEmpty();
				        if (!createNew) { return value.get(); }
				        return "";
				    }
				    String initialized(Optional<String> value) {
				        boolean present = value.isPresent();
				        if (present) { return value.get(); }
				        return "";
				    }
				    String reassigned(Optional<String> value) {
				        boolean present = value.isPresent();
				        value = Optional.empty();
				        if (present) { return value.get(); }
				        return "";
				    }
				}
				""");
		assertEquals(1, result.findings().stream().filter(f -> f.message().contains("presence check")).count(),
				result.findings().toString());
	}

	@Test
	void distinguishesSwitchExpressionValuesFromIgnoredStatementResults() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				class Sample {
				    String value(int key, String text) {
				        return switch (key) { default -> text.substring(1); };
				    }
				    void ignored(int key, String text) {
				        switch (key) { default -> text.substring(1); }
				    }
				}
				""");
		assertEquals(1,
				result.findings().stream().filter(f -> f.message().equals("Result of method call is ignored")).count(),
				result.findings().toString());
	}

	@Test
	void recognizesShortCircuitAndDominatingOptionalGuards() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.Optional;
				import java.util.OptionalLong;
				class Sample {
				    boolean shortCircuit(Optional<String> value) {
				        return value.isEmpty() || value.get().isBlank();
				    }
				    String earlyExit(Optional<String> value) {
				        if (value.isEmpty()) return "";
				        return value.get();
				    }
				    long continueExit(Iterable<OptionalLong> values) {
				        for (OptionalLong value : values) {
				            if (value.isEmpty()) continue;
				            return switch ((int) value.getAsLong()) {
				                default -> value.getAsLong();
				            };
				        }
				        return 0;
				    }
				    long conditional(OptionalLong value) {
				        return value.isPresent() ? value.getAsLong() : 0;
				    }
				}
				""");

		assertNoMessages(result, "Optional.get() is called without a matching presence check");
	}

	@Test
	void acceptsObjectAllocationsReturnedBySwitchExpressionArms() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				record Relation(String method, boolean negated) {}
				class Sample {
				    Object relation(int operator) {
				        Object relation = switch (operator) {
				            case 1 -> new Relation("isAfter", false);
				            default -> new IllegalArgumentException();
				        };
				        return relation;
				    }
				    Object throwingRelation(int operator) {
				        return switch (operator) {
				            case 1 -> new Relation("isAfter", false);
				            default -> throw new IllegalArgumentException();
				        };
				    }
				}
				""");

		assertNoMessages(result, "Result of object allocation is ignored",
				"Throwable is instantiated but never thrown");
	}

	@Test
	void requiresKnownPureReceiversBeforeReportingIgnoredResults() {
		ToolResult result = inspect(new ReportApiMisuseBugsTool(), """
				import java.util.Optional;
				import java.util.function.Supplier;
				class Editor { void replace(String value) {} }
				class Sample {
				    void use(String text, Optional<String> optional, Editor editor) {
				        text.trim();
				        optional.map(String::trim);
				        editor.replace("replacement");
				        Supplier<IllegalArgumentException> supplier =
				                () -> new IllegalArgumentException("later");
				    }
				}
				""");

		long ignoredResults = result.findings()
			.stream()
			.filter(finding -> finding.message().equals("Result of method call is ignored"))
			.count();
		assertTrue(ignoredResults == 2, () -> "Expected only the known-pure calls, got " + result.findings());
		assertNoMessages(result, "Result of object allocation is ignored",
				"Throwable is instantiated but never thrown");
	}

	@Test
	void doesNotTreatListIndexAsAnElement() {
		ToolResult result = inspect(new ReportCollectionArrayBugsTool(), """
				import java.util.List;
				class Sample {
				    String first(List<String> names) { return names.get(0); }
				}
				""");

		assertNoMessages(result, "Suspicious collection method call uses an incompatible element type");
	}

	@Test
	void reportsFormatAndStringBugs() {
		ToolResult result = inspect(new ReportFormatStringBugsTool(), """
				import java.text.MessageFormat;
				import java.time.format.DateTimeFormatter;
				class Sample {
				    void use(String value) {
				        DateTimeFormatter.ofPattern("yyyy-MM-dd'");
				        new MessageFormat("{");
				        String.format("%", value);
				        String.format("prefix " + value, value);
				        String joined = "hello world" + "welcome";
				    }
				}
				""");

		assertMessages(result, "Incorrect DateTimeFormat", "Incorrect MessageFormat", "Malformed");
		assertNoMessages(result, "concatenation", "Whitespace may be missing");
	}

	@Test
	void acceptsEncodedStringsAndDistinctFormattingDialects() {
		ToolResult result = inspect(new ReportFormatStringBugsTool(), """
				import java.text.MessageFormat;
				import java.text.SimpleDateFormat;
				class Sample {
				    String token = "prefix" + "123456789";
				    String encoded = "SGVsbG8" + "gd29ybGQ=";
				    void use() {
				        new SimpleDateFormat("yyyy-MM-dd ZZZZZZ");
				        MessageFormat.format("Progress {0}: 100%", 1);
				        format("100%");
				    }
				    void format(String value) {}
				}
				""");
		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void distinguishesFormatArgumentsFromLocaleOverloads() {
		ToolResult result = inspect(new ReportFormatStringBugsTool(), """
				import java.util.Locale;
				class Sample {
				    void use(String format, Locale locale) {
				        String.format(format, "100%");
				        System.out.printf(format, "100%");
				        String.format(locale, "%");
				        System.out.printf(Locale.ROOT, "%");
				    }
				}
				""");
		assertTrue(result.findings().size() == 2, result.findings().toString());
		assertTrue(result.findings().stream().allMatch(finding -> finding.message().contains("Malformed")));
	}

	@Test
	void ignoresVariablesShadowingFormattingClassNames() {
		ToolResult result = inspect(new ReportFormatStringBugsTool(), """
				class Custom {
				    Custom out;
				    void format(String value) {}
				    void printf(String value) {}
				}
				class Sample {
				    void use(Custom String, Custom System) {
				        String.format("100%");
				        System.out.printf("100%");
				        java.lang.String.format("%");
				    }
				}
				""");
		assertEquals(1, result.findings().size(), result.findings().toString());
		assertMessages(result, "Malformed");
	}

	@Test
	void reportsDeclarationContractBugs() {
		ToolResult result = inspect(new ReportDeclarationContractBugsTool(), """
				import java.lang.annotation.*;
				@interface Contract { String value(); }
				class Utility { static void work() {} }
				class Sample {
				    int first;
				    int second;
				    Sample(Sample source) { this.first = source.first; }
				    void main(String[] args) {}
				    @SafeVarargs static <T> void collect(T... values) { values[0] = null; }
				    @Contract("null -> false") boolean contract(String left, String right) { return false; }
				    void use() { new Utility(); }
				}
				""");

		assertMessages(result, "Confusing main", "@SafeVarargs", "parameter count", "Copy constructor",
				"utility class");
	}

	@Test
	void acceptsAUtilityShapedSingletonSelfInstantiation() {
		ToolResult result = inspect(new ReportDeclarationContractBugsTool(), """
				class Singleton {
				    static final Singleton INSTANCE = new Singleton();
				    private Singleton() {}
				}
				""");

		assertNoMessages(result, "Instantiation of utility class");
	}

	@Test
	void reportsMismatchedStateUsage() {
		ToolResult result = inspect(new ReportStateUsageBugsTool(), """
				import java.util.ArrayList;
				import java.util.List;
				class Sample {
				    void resize(int width, int height) {}
				    void use(int height) {
				        StringBuilder builder = new StringBuilder();
				        builder.append("x");
				        List<String> values = new ArrayList<>();
				        int size = values.size();
				        int[] array = new int[2];
				        array[0] = 1;
				        int width = 0;
				        width = height;
				        resize(height, width);
				    }
				}
				""");

		assertMessages(result, "StringBuilder", "collection", "array", "variable/parameter name");
	}

	@Test
	void acceptsExternallyOwnedOrExposedState() {
		ToolResult result = inspect(new ReportStateUsageBugsTool(), """
				import java.util.ArrayList;
				import java.util.HashMap;
				import java.util.HashSet;
				import java.util.List;
				import java.util.Map;
				import java.util.Set;
				class Sample {
				    int count(List<String> values) { return values.size(); }
				    void add(List<String> values) { values.add("x"); }
				    List<String> build() {
				        List<String> result = new ArrayList<>();
				        result.add("x");
				        return result;
				    }
				    int supplied() {
				        List<String> result = List.of("x");
				        return result.size();
				    }
				    Map<String, String> sharedMap() { return new HashMap<>(); }
				    Map<String, Set<String>> registry() { return new HashMap<>(); }
				    void updateSharedState() {
				        Map<String, String> values = sharedMap();
				        values.put("key", "value");
				        Set<String> subscribers = registry()
				                .computeIfAbsent("topic", ignored -> new HashSet<>());
				        subscribers.add("subscriber");
				    }
				}
				""");

		assertNoMessages(result, "Mismatched query and update of collection");
	}

	@Test
	void acceptsSuppliedArraysAndArrayReadModifyWriteOperations() {
		ToolResult result = inspect(new ReportStateUsageBugsTool(), """
				class Sample {
				    int[] supplied() { return new int[] { 1 }; }
				    void updateOnly() {
				        int[] supplied = supplied();
				        supplied[0] = 2;
				    }
				    void updateElements(int[][] supplied) {
				        for (int[] values : supplied) values[0] = 2;
				    }
				    int fallback(int width, int height) {
				        if (width == 0) width = height;
				        return width;
				    }
				    int use() {
				        int[] values = supplied();
				        int first = values[0];
				        int[] totals = new int[1];
				        totals[0]++;
				        totals[0] += first;
				        return totals[0];
				    }
				}
				""");

		assertNoMessages(result, "Mismatched read and write of array");
	}

	@Test
	void reportsNullabilityBugs() {
		ToolResult result = inspect(new ReportNullabilityBugsTool(), """
				@interface NotNull {}
				class Sample {
				    @NotNull String value;
				    @NotNull String result() { return null; }
				    void accept(@NotNull String input) {}
				    void use() {
				        accept(null);
				        String local = null;
				        local.trim();
				    }
				}
				""");

		assertMessages(result, "Non-null field", "Return of null", "non-null parameter", "definitely-null");
	}

	@Test
	void acceptsValidationFieldsAndGuardedNullableLocals() {
		ToolResult result = inspect(new ReportNullabilityBugsTool(), """
				import jakarta.validation.constraints.NotNull;
				class Sample {
				    @NotNull String entityValue;
				    @NotNull int primitiveValue;
				    void use(boolean choose) {
				        String first = null;
				        if (first != null) {
				            first.trim();
				        }
				        String second = null;
				        if (second == null || second.isEmpty()) {
				            System.out.println("empty");
				        }
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Probable-bug reports must not change source");
		return result;
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

	private static void assertNoMessages(ToolResult result, String... unwanted) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String message : unwanted) {
			assertFalse(messages.contains(message), () -> "Unexpected '" + message + "' in " + messages);
		}
	}

}

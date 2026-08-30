package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Simplifies common redundant java.time conversions, field access, and comparisons. */
public final class SimplifyRedundantJavaTimeOperationTool implements InspectionTool {

	private static final Set<String> TIME_TYPES = Set.of("Instant", "LocalDate", "LocalDateTime", "LocalTime",
			"OffsetDateTime", "OffsetTime", "ZonedDateTime");

	private static final Set<String> COMPARABLE_TIME_TYPES = Set.of("Instant", "LocalDate", "LocalDateTime",
			"LocalTime");

	private static final Map<String, String> FIELD_METHODS = Map.ofEntries(Map.entry("YEAR", "getYear"),
			Map.entry("MONTH_OF_YEAR", "getMonthValue"), Map.entry("DAY_OF_MONTH", "getDayOfMonth"),
			Map.entry("DAY_OF_YEAR", "getDayOfYear"), Map.entry("HOUR_OF_DAY", "getHour"),
			Map.entry("MINUTE_OF_HOUR", "getMinute"), Map.entry("SECOND_OF_MINUTE", "getSecond"),
			Map.entry("NANO_OF_SECOND", "getNano"));

	@Override
	public String id() {
		return "simplify-redundant-java-time-operation";
	}

	@Override
	public String description() {
		return "Simplify redundant java.time operations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<CallCandidate> calls = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> callCandidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		List<ComparisonCandidate> comparisons = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(binary -> comparisonCandidate(context, binary))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (CallCandidate candidate : calls) {
			findings.add(Finding.at(candidate.call(), "Simplify redundant java.time operation"));
			if (applyFixes) {
				context.editor().replace(candidate.call().getRange().orElseThrow(), candidate.replacement());
			}
		}
		for (ComparisonCandidate candidate : comparisons) {
			findings.add(Finding.at(candidate.binary(), "Replace java.time compareTo() comparison"));
			if (applyFixes) {
				context.editor().replace(candidate.binary().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<CallCandidate> callCandidate(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty() || call.getArguments().size() != 1 || AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		if ("from".equals(call.getNameAsString())) {
			String owner = ExpressionToolSupport.simpleName(call.getScope().orElseThrow().toString());
			String argumentType = ExpressionToolSupport.visibleSimpleType(context, call.getArgument(0), call)
				.orElse("");
			if (owner.equals(argumentType) && ExpressionToolSupport.knownType(context.compilationUnit(),
					call.getScope().orElseThrow().toString(), "java.time", TIME_TYPES)) {
				return Optional.of(new CallCandidate(call, context.editor().text(call.getArgument(0))));
			}
		}
		if ("get".equals(call.getNameAsString())) {
			String receiverType = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call)
				.orElse("");
			Optional<String> field = chronoField(context, call.getArgument(0));
			if (field.isEmpty()) {
				return Optional.empty();
			}
			String fieldName = field.orElseThrow();
			String method = FIELD_METHODS.get(fieldName);
			if (method != null && supports(receiverType, fieldName) && ExpressionToolSupport
				.knownType(context.compilationUnit(), receiverType, "java.time", TIME_TYPES)) {
				return Optional.of(new CallCandidate(call,
						context.editor().text(call.getScope().orElseThrow()) + "." + method + "()"));
			}
		}
		return Optional.empty();
	}

	private static Optional<ComparisonCandidate> comparisonCandidate(InspectionContext context, BinaryExpr binary) {
		if (AstSupport.hasComment(context, binary)) {
			return Optional.empty();
		}
		MethodCallExpr call;
		Operator operator = binary.getOperator();
		if (binary.getLeft() instanceof MethodCallExpr method && zero(binary.getRight())) {
			call = method;
		}
		else if (zero(binary.getLeft()) && binary.getRight() instanceof MethodCallExpr method) {
			call = method;
			operator = reverse(operator);
		}
		else {
			return Optional.empty();
		}
		if (!"compareTo".equals(call.getNameAsString()) || call.getScope().isEmpty()
				|| call.getArguments().size() != 1) {
			return Optional.empty();
		}
		String receiverType = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call)
			.orElse("");
		String argumentType = ExpressionToolSupport.visibleSimpleType(context, call.getArgument(0), call).orElse("");
		if (!receiverType.equals(argumentType) || !ExpressionToolSupport.knownType(context.compilationUnit(),
				receiverType, "java.time", COMPARABLE_TIME_TYPES)) {
			return Optional.empty();
		}
		Relation relation = switch (operator) {
			case GREATER -> new Relation("isAfter", false);
			case GREATER_EQUALS -> new Relation("isBefore", true);
			case LESS -> new Relation("isBefore", false);
			case LESS_EQUALS -> new Relation("isAfter", true);
			case EQUALS -> new Relation("isEqual", false);
			case NOT_EQUALS -> new Relation("isEqual", true);
			default -> null;
		};
		if (relation == null) {
			return Optional.empty();
		}
		String replacement = context.editor().text(call.getScope().orElseThrow()) + "." + relation.method() + "("
				+ context.editor().text(call.getArgument(0)) + ")";
		if (relation.negated()) {
			replacement = "!" + replacement;
		}
		return Optional.of(new ComparisonCandidate(binary, replacement));
	}

	private static Optional<String> chronoField(InspectionContext context, Expression expression) {
		if (expression instanceof FieldAccessExpr access && ExpressionToolSupport.knownType(context.compilationUnit(),
				access.getScope().toString(), "java.time.temporal", Set.of("ChronoField"))) {
			return Optional.of(access.getNameAsString());
		}
		if (expression instanceof NameExpr name && context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> imported.isStatic()
					&& imported.getNameAsString().equals("java.time.temporal.ChronoField." + name.getNameAsString()))) {
			return Optional.of(name.getNameAsString());
		}
		return Optional.empty();
	}

	private static boolean supports(String type, String field) {
		if (field == null) {
			return false;
		}
		if (Set.of("YEAR", "MONTH_OF_YEAR", "DAY_OF_MONTH", "DAY_OF_YEAR").contains(field)) {
			return Set.of("LocalDate", "LocalDateTime", "OffsetDateTime", "ZonedDateTime").contains(type);
		}
		return Set.of("LocalTime", "LocalDateTime", "OffsetTime", "OffsetDateTime", "ZonedDateTime").contains(type);
	}

	private static boolean zero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private static BinaryExpr.Operator reverse(BinaryExpr.Operator operator) {
		return switch (operator) {
			case LESS -> BinaryExpr.Operator.GREATER;
			case LESS_EQUALS -> BinaryExpr.Operator.GREATER_EQUALS;
			case GREATER -> BinaryExpr.Operator.LESS;
			case GREATER_EQUALS -> BinaryExpr.Operator.LESS_EQUALS;
			default -> operator;
		};
	}

	private record CallCandidate(MethodCallExpr call, String replacement) {
	}

	private record ComparisonCandidate(BinaryExpr binary, String replacement) {
	}

	private record Relation(String method, boolean negated) {
	}

}

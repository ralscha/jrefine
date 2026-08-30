package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Applies conservative fixes for several local numeric-expression mistakes. */
public final class SimplifyNumericExpressionsTool implements InspectionTool {

	private static final Set<String> ZERO_TO_ZERO = Set.of("sin", "tan", "asin", "atan", "sinh", "tanh", "expm1",
			"log1p");

	private static final Set<String> ZERO_TO_ONE = Set.of("cos", "cosh", "exp");

	@Override
	public String id() {
		return "simplify-numeric-expressions";
	}

	@Override
	public String description() {
		return "Simplify NaN checks, constant Math calls, arithmetic identities, and unary signs";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> all = new ArrayList<>();
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> constantMathCandidate(context, call))
			.flatMap(Optional::stream)
			.forEach(all::add);
		context.compilationUnit().findAll(BinaryExpr.class).forEach(binary -> {
			nanCandidate(context, binary).ifPresent(all::add);
			oddnessCandidate(context, binary).ifPresent(all::add);
			pointlessCandidate(context, binary).ifPresent(all::add);
			negativeOperandCandidate(context, binary).ifPresent(all::add);
		});
		context.compilationUnit()
			.findAll(UnaryExpr.class)
			.stream()
			.map(unary -> unaryCandidate(context, unary))
			.flatMap(Optional::stream)
			.forEach(all::add);
		context.compilationUnit()
			.findAll(AssignExpr.class)
			.stream()
			.map(assignment -> compoundSignCandidate(context, assignment))
			.flatMap(Optional::stream)
			.forEach(all::add);

		Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayList<Candidate> unique = new ArrayList<>();
		all.stream().filter(candidate -> seen.add(candidate.node())).forEach(unique::add);
		List<Candidate> nonOverlapping = unique.stream()
			.filter(candidate -> unique.stream()
				.noneMatch(other -> other != candidate && other.node() != candidate.node()
						&& other.replacement() != null && other.node().isAncestorOf(candidate.node())))
			.toList();
		boolean hasFix = applyFixes && nonOverlapping.stream().anyMatch(candidate -> candidate.replacement() != null);
		List<Candidate> candidates = hasFix
				? nonOverlapping.stream().filter(candidate -> candidate.replacement() != null).toList()
				: nonOverlapping;
		ArrayList<Finding> findings = new ArrayList<>();
		int edits = 0;
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.node(), candidate.message()));
			if (applyFixes && candidate.replacement() != null) {
				context.editor().replace(candidate.node().getRange().orElseThrow(), candidate.replacement());
				edits++;
			}
		}
		return new ToolResult(findings, applyFixes && edits > 0);
	}

	private static Optional<Candidate> constantMathCandidate(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty() || call.getArguments().size() != 1 || AstSupport.hasComment(context, call)
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), call.getScope().orElseThrow().toString(),
						"java.lang", Set.of("Math", "StrictMath"))) {
			return Optional.empty();
		}
		Expression argument = call.getArgument(0);
		String replacement = null;
		if (positiveZero(argument) && ZERO_TO_ZERO.contains(call.getNameAsString())) {
			replacement = "0.0";
		}
		if (positiveZero(argument) && ZERO_TO_ONE.contains(call.getNameAsString())) {
			replacement = "1.0";
		}
		if (one(argument) && Set.of("sqrt", "cbrt").contains(call.getNameAsString())) {
			replacement = "1.0";
		}
		if (one(argument) && Set.of("log", "log10").contains(call.getNameAsString())) {
			replacement = "0.0";
		}
		return replacement == null ? Optional.empty()
				: Optional.of(new Candidate(call, "Replace constant Math call with its value", replacement));
	}

	private static Optional<Candidate> nanCandidate(InspectionContext context, BinaryExpr binary) {
		Operator operator = binary.getOperator();
		if (!Set
			.of(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.LESS,
					BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS)
			.contains(operator)) {
			return Optional.empty();
		}
		Optional<String> leftOwner = nanOwner(context, binary.getLeft());
		Optional<String> rightOwner = nanOwner(context, binary.getRight());
		if (leftOwner.isEmpty() && rightOwner.isEmpty()) {
			return Optional.empty();
		}
		String owner = leftOwner.or(() -> rightOwner).orElseThrow();
		Expression other = leftOwner.isPresent() ? binary.getRight() : binary.getLeft();
		String otherType = NumericSupport.typeOf(context, other, binary).map(NumericSupport::simpleName).orElse("");
		if ("Float".equals(owner) && ("double".equals(otherType) || "Double".equals(otherType))) {
			owner = "Double";
		}
		String replacement = null;
		if (operator == BinaryExpr.Operator.EQUALS || operator == BinaryExpr.Operator.NOT_EQUALS) {
			String check = owner + ".isNaN(" + context.editor().text(other) + ")";
			replacement = operator == BinaryExpr.Operator.NOT_EQUALS ? "!" + check : check;
		}
		return Optional
			.of(new Candidate(binary, "Comparison with " + owner + ".NaN never tests numeric equality", replacement));
	}

	private static Optional<String> nanOwner(InspectionContext context, Expression expression) {
		if (expression instanceof FieldAccessExpr field && "NaN".equals(field.getNameAsString())) {
			for (String owner : Set.of("Double", "Float")) {
				if (ExpressionToolSupport.knownType(context.compilationUnit(), field.getScope().toString(), "java.lang",
						Set.of(owner))) {
					return Optional.of(owner);
				}
			}
		}
		if (expression instanceof NameExpr name && "NaN".equals(name.getNameAsString())) {
			for (String owner : Set.of("Double", "Float")) {
				boolean imported = context.compilationUnit()
					.getImports()
					.stream()
					.anyMatch(
							value -> value.isStatic() && (value.getNameAsString().equals("java.lang." + owner + ".NaN")
									|| value.isAsterisk() && value.getNameAsString().equals("java.lang." + owner)));
				if (imported) {
					return Optional.of(owner);
				}
			}
		}
		return Optional.empty();
	}

	private static Optional<Candidate> oddnessCandidate(InspectionContext context, BinaryExpr comparison) {
		if (comparison.getOperator() != BinaryExpr.Operator.EQUALS) {
			return Optional.empty();
		}
		BinaryExpr remainder = null;
		if (comparison.getLeft() instanceof BinaryExpr binary && one(comparison.getRight())) {
			remainder = binary;
		}
		if (comparison.getRight() instanceof BinaryExpr binary && one(comparison.getLeft())) {
			remainder = binary;
		}
		if (remainder == null || remainder.getOperator() != BinaryExpr.Operator.REMAINDER
				|| !integerValue(remainder.getRight(), 2) || AstSupport.hasComment(context, comparison)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(comparison, "Use a sign-safe oddness check",
				"(" + context.editor().text(remainder) + " != 0)"));
	}

	private static Optional<Candidate> pointlessCandidate(InspectionContext context, BinaryExpr binary) {
		if (AstSupport.hasComment(context, binary) || !numeric(context, binary.getLeft(), binary)
				|| !numeric(context, binary.getRight(), binary)) {
			return Optional.empty();
		}
		Expression left = binary.getLeft();
		Expression right = binary.getRight();
		boolean integral = NumericSupport.typeOf(context, binary, binary)
			.filter(NumericSupport::isIntegral)
			.isPresent();
		Expression replacement = null;
		String literal = null;
		switch (binary.getOperator()) {
			case PLUS -> {
				if (integral && zero(right)) {
					replacement = left;
				}
				else if (integral && zero(left)) {
					replacement = right;
				}
			}
			case MINUS -> {
				if (integral && zero(right)) {
					replacement = left;
				}
				else if (integral && left.equals(right) && ExpressionToolSupport.stable(left)) {
					literal = zeroFor(context, binary);
				}
			}
			case MULTIPLY -> {
				if (one(right)) {
					replacement = left;
				}
				else if (one(left)) {
					replacement = right;
				}
				else if (integral && zero(right) && ExpressionToolSupport.stable(left)) {
					literal = zeroFor(context, binary);
				}
				else if (integral && zero(left) && ExpressionToolSupport.stable(right)) {
					literal = zeroFor(context, binary);
				}
			}
			case DIVIDE -> {
				if (one(right)) {
					replacement = left;
				}
			}
			case REMAINDER -> {
				if (integral && one(right) && ExpressionToolSupport.stable(left)) {
					literal = zeroFor(context, binary);
				}
			}
			default -> {
				return Optional.empty();
			}
		}
		if (replacement == null && literal == null) {
			return Optional.empty();
		}
		String text = literal != null ? literal : preservingTypeText(context, binary, replacement);
		return Optional.of(new Candidate(binary, "Simplify pointless arithmetic expression", text));
	}

	private static Optional<Candidate> negativeOperandCandidate(InspectionContext context, BinaryExpr binary) {
		if (AstSupport.hasComment(context, binary) || !(binary.getRight() instanceof UnaryExpr unary)
				|| unary.getOperator() != UnaryExpr.Operator.MINUS) {
			return Optional.empty();
		}
		String operator;
		if (binary.getOperator() == BinaryExpr.Operator.PLUS) {
			operator = " - ";
		}
		else if (binary.getOperator() == BinaryExpr.Operator.MINUS) {
			operator = " + ";
		}
		else {
			return Optional.empty();
		}
		return Optional.of(new Candidate(binary, "Remove unnecessary unary minus",
				context.editor().text(binary.getLeft()) + operator + context.editor().text(unary.getExpression())));
	}

	private static Optional<Candidate> unaryCandidate(InspectionContext context, UnaryExpr unary) {
		if (AstSupport.hasComment(context, unary)) {
			return Optional.empty();
		}
		if (unary.getOperator() == UnaryExpr.Operator.PLUS) {
			if (!numeric(context, unary.getExpression(), unary)) {
				return Optional.empty();
			}
			return Optional.of(new Candidate(unary, "Remove unary plus",
					preservingTypeText(context, unary, unary.getExpression())));
		}
		if (unary.getOperator() == UnaryExpr.Operator.MINUS && unary.getExpression() instanceof UnaryExpr inner
				&& inner.getOperator() == UnaryExpr.Operator.MINUS) {
			return Optional.of(new Candidate(unary, "Remove unnecessary unary minus",
					preservingTypeText(context, unary, inner.getExpression())));
		}
		return Optional.empty();
	}

	private static Optional<Candidate> compoundSignCandidate(InspectionContext context, AssignExpr assignment) {
		if (AstSupport.hasComment(context, assignment) || !(assignment.getValue() instanceof UnaryExpr unary)
				|| unary.getOperator() != UnaryExpr.Operator.MINUS) {
			return Optional.empty();
		}
		String operator;
		if (assignment.getOperator() == AssignExpr.Operator.PLUS) {
			operator = " -= ";
		}
		else if (assignment.getOperator() == AssignExpr.Operator.MINUS) {
			operator = " += ";
		}
		else {
			return Optional.empty();
		}
		return Optional.of(new Candidate(assignment, "Remove unnecessary unary minus",
				context.editor().text(assignment.getTarget()) + operator
						+ context.editor().text(unary.getExpression())));
	}

	private static boolean numeric(InspectionContext context, Expression expression, Node use) {
		return NumericSupport.typeOf(context, expression, use).filter(NumericSupport::isNumeric).isPresent();
	}

	private static String zeroFor(InspectionContext context, BinaryExpr binary) {
		return switch (NumericSupport.typeOf(context, binary, binary).orElse("int")) {
			case "long" -> "0L";
			case "float" -> "0.0F";
			case "double" -> "0.0";
			default -> "0";
		};
	}

	private static String preservingTypeText(InspectionContext context, Expression original, Expression replacement) {
		String text = context.editor().text(replacement);
		String originalType = NumericSupport.typeOf(context, original, original)
			.map(NumericSupport::simpleName)
			.orElse("");
		String replacementType = NumericSupport.typeOf(context, replacement, original)
			.map(NumericSupport::simpleName)
			.orElse("");
		if (NumericSupport.isNumeric(originalType) && NumericSupport.isNumeric(replacementType)
				&& !originalType.equals(replacementType)) {
			return "(" + originalType + ") " + text;
		}
		return text;
	}

	private static boolean zero(Expression expression) {
		return numericValue(expression, 0.0);
	}

	private static boolean one(Expression expression) {
		return numericValue(expression, 1.0);
	}

	private static boolean positiveZero(Expression expression) {
		return !(expression instanceof UnaryExpr) && zero(expression);
	}

	private static boolean numericValue(Expression expression, double expected) {
		if (expression instanceof UnaryExpr unary && (unary.getOperator() == UnaryExpr.Operator.MINUS
				|| unary.getOperator() == UnaryExpr.Operator.PLUS)) {
			Optional<Double> value = literalValue(unary.getExpression());
			return value.isPresent() && sameNumericValue(
					unary.getOperator() == UnaryExpr.Operator.MINUS ? -value.orElseThrow() : value.orElseThrow(),
					expected);
		}
		return literalValue(expression).filter(value -> sameNumericValue(value, expected)).isPresent();
	}

	private static boolean sameNumericValue(double left, double right) {
		if (Double.compare(left, right) == 0) {
			return true;
		}
		return Double.compare(Math.abs(left), 0.0) == 0 && Double.compare(Math.abs(right), 0.0) == 0;
	}

	private static Optional<Double> literalValue(Expression expression) {
		if (expression instanceof IntegerLiteralExpr literal) {
			return Optional.of(literal.asNumber().doubleValue());
		}
		if (expression instanceof LongLiteralExpr literal) {
			return Optional.of(literal.asNumber().doubleValue());
		}
		if (!(expression instanceof DoubleLiteralExpr literal)) {
			return Optional.empty();
		}
		String value = literal.getValue();
		value = value.replace("_", "").toLowerCase(Locale.ROOT);
		if (value.endsWith("l") || value.endsWith("f") || value.endsWith("d")) {
			value = value.substring(0, value.length() - 1);
		}
		try {
			return Optional.of(Double.parseDouble(value));
		}
		catch (NumberFormatException ignored) {
			return Optional.empty();
		}
	}

	private static boolean integerValue(Expression expression, long expected) {
		if (expression instanceof IntegerLiteralExpr literal) {
			return literal.asNumber().longValue() == expected;
		}
		if (expression instanceof LongLiteralExpr literal) {
			return literal.asNumber().longValue() == expected;
		}
		return false;
	}

	private record Candidate(Node node, String message, String replacement) {
	}

}

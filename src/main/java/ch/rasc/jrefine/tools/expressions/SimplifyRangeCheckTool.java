package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Collapses discrete integral range checks that admit or reject exactly one value. */
public final class SimplifyRangeCheckTool implements InspectionTool {

	private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);

	private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

	private static final Set<String> OPERAND_TYPES = Set.of("byte", "short", "int", "long", "char", "Byte", "Short",
			"Integer", "Long", "Character");

	@Override
	public String id() {
		return "simplify-range-check";
	}

	@Override
	public String description() {
		return "Collapse discrete integral range checks to equality or inequality";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(expression -> candidate(context, expression))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Simplify excessive range check"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(), context.editor().text(candidate.value())
							+ " " + candidate.operator() + " " + literal(candidate.target()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr expression) {
		boolean conjunction = expression.getOperator() == BinaryExpr.Operator.AND;
		if (!conjunction && expression.getOperator() != BinaryExpr.Operator.OR
				|| AstSupport.hasComment(context, expression)) {
			return Optional.empty();
		}
		Optional<Comparison> left = comparison(expression.getLeft());
		Optional<Comparison> right = comparison(expression.getRight());
		if (left.isEmpty() || right.isEmpty() || !sameValue(left.orElseThrow().value(), right.orElseThrow().value())) {
			return Optional.empty();
		}
		Optional<Domain> domain = domain(context, left.orElseThrow().value(), expression);
		if (domain.isEmpty()) {
			return Optional.empty();
		}
		AllowedValues allowed = new AllowedValues(domain.orElseThrow().minimum(), domain.orElseThrow().maximum());
		allowed.restrict(left.orElseThrow(), conjunction);
		allowed.restrict(right.orElseThrow(), conjunction);
		Optional<BigInteger> target = allowed.onlyValue();
		return target
			.map(value -> new Candidate(expression, left.orElseThrow().value(), conjunction ? "==" : "!=", value));
	}

	private static Optional<Comparison> comparison(Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (!(currentExpression instanceof BinaryExpr binary) || !comparisonOperator(binary.getOperator())) {
			return Optional.empty();
		}
		Optional<BigInteger> rightConstant = constant(binary.getRight());
		if (rightConstant.isPresent() && stableValue(binary.getLeft())) {
			return Optional
				.of(new Comparison(unwrap(binary.getLeft()), binary.getOperator(), rightConstant.orElseThrow()));
		}
		Optional<BigInteger> leftConstant = constant(binary.getLeft());
		if (leftConstant.isPresent() && stableValue(binary.getRight())) {
			return Optional.of(new Comparison(unwrap(binary.getRight()), reversed(binary.getOperator()),
					leftConstant.orElseThrow()));
		}
		return Optional.empty();
	}

	private static boolean comparisonOperator(BinaryExpr.Operator operator) {
		return operator == BinaryExpr.Operator.LESS || operator == BinaryExpr.Operator.LESS_EQUALS
				|| operator == BinaryExpr.Operator.GREATER || operator == BinaryExpr.Operator.GREATER_EQUALS
				|| operator == BinaryExpr.Operator.EQUALS || operator == BinaryExpr.Operator.NOT_EQUALS;
	}

	private static BinaryExpr.Operator reversed(BinaryExpr.Operator operator) {
		return switch (operator) {
			case LESS -> BinaryExpr.Operator.GREATER;
			case LESS_EQUALS -> BinaryExpr.Operator.GREATER_EQUALS;
			case GREATER -> BinaryExpr.Operator.LESS;
			case GREATER_EQUALS -> BinaryExpr.Operator.LESS_EQUALS;
			default -> operator;
		};
	}

	private static boolean stableValue(Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (currentExpression instanceof NameExpr) {
			return true;
		}
		return currentExpression instanceof FieldAccessExpr field && "length".equals(field.getNameAsString())
				&& unwrap(field.getScope()) instanceof NameExpr;
	}

	private static Optional<Domain> domain(InspectionContext context, Expression expression, BinaryExpr use) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (currentExpression instanceof FieldAccessExpr field && "length".equals(field.getNameAsString())
				&& unwrap(field.getScope()) instanceof NameExpr array
				&& TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(),
						array.getNameAsString(), use)
				&& TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), array, use)
					.filter(type -> type.endsWith("[]"))
					.isPresent()) {
			return Optional.of(new Domain(BigInteger.ZERO, INT_MAX));
		}
		if (!(currentExpression instanceof NameExpr)) {
			return Optional.empty();
		}
		if (!TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(),
				currentExpression.asNameExpr().getNameAsString(), use)) {
			return Optional.empty();
		}
		String type = TypeLookup.visibleType(context.compilationUnit(), currentExpression, use).orElse(null);
		if (type == null || !knownIntegralType(context, type)) {
			return Optional.empty();
		}
		String simple = simpleName(type);
		return switch (simple) {
			case "byte", "Byte" ->
				Optional.of(new Domain(BigInteger.valueOf(Byte.MIN_VALUE), BigInteger.valueOf(Byte.MAX_VALUE)));
			case "short", "Short" ->
				Optional.of(new Domain(BigInteger.valueOf(Short.MIN_VALUE), BigInteger.valueOf(Short.MAX_VALUE)));
			case "char", "Character" ->
				Optional.of(new Domain(BigInteger.ZERO, BigInteger.valueOf(Character.MAX_VALUE)));
			case "int", "Integer" -> Optional.of(new Domain(INT_MIN, INT_MAX));
			case "long", "Long" ->
				Optional.of(new Domain(BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)));
			default -> Optional.empty();
		};
	}

	private static boolean knownIntegralType(InspectionContext context, String type) {
		String simple = simpleName(type);
		if (!OPERAND_TYPES.contains(simple)) {
			return false;
		}
		if (Set.of("byte", "short", "int", "long", "char").contains(simple)) {
			return true;
		}
		return TypeLookup.isKnownJavaLangType(context.compilationUnit(), type,
				Set.of("Byte", "Short", "Integer", "Long", "Character"));
	}

	private static Optional<BigInteger> constant(Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (currentExpression instanceof IntegerLiteralExpr integer) {
			return parseInteger(integer.getValue(), 32);
		}
		if (currentExpression instanceof LongLiteralExpr value) {
			return parseInteger(value.getValue(), 64);
		}
		if (currentExpression instanceof CharLiteralExpr character) {
			return Optional.of(BigInteger.valueOf(character.asChar()));
		}
		if (currentExpression instanceof UnaryExpr unary && (unary.getOperator() == UnaryExpr.Operator.PLUS
				|| unary.getOperator() == UnaryExpr.Operator.MINUS)) {
			Optional<BigInteger> operand = constant(unary.getExpression());
			if (operand.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(unary.getOperator() == UnaryExpr.Operator.MINUS ? operand.orElseThrow().negate()
					: operand.orElseThrow());
		}
		return Optional.empty();
	}

	private static Optional<BigInteger> parseInteger(String source, int bits) {
		String value = source.replace("_", "");
		if (value.endsWith("l") || value.endsWith("L")) {
			value = value.substring(0, value.length() - 1);
		}
		try {
			BigInteger parsed;
			boolean nonDecimal = false;
			if (value.startsWith("0x") || value.startsWith("0X")) {
				parsed = new BigInteger(value.substring(2), 16);
				nonDecimal = true;
			}
			else if (value.startsWith("0b") || value.startsWith("0B")) {
				parsed = new BigInteger(value.substring(2), 2);
				nonDecimal = true;
			}
			else if (value.length() > 1 && value.startsWith("0")) {
				parsed = new BigInteger(value.substring(1), 8);
				nonDecimal = true;
			}
			else {
				parsed = new BigInteger(value);
			}
			if (nonDecimal) {
				if (parsed.bitLength() > bits) {
					return Optional.empty();
				}
				if (parsed.testBit(bits - 1)) {
					parsed = parsed.subtract(BigInteger.ONE.shiftLeft(bits));
				}
			}
			return Optional.of(parsed);
		}
		catch (NumberFormatException ignored) {
			return Optional.empty();
		}
	}

	private static boolean sameValue(Expression left, Expression right) {
		return unwrap(left).toString().equals(unwrap(right).toString());
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression instanceof EnclosedExpr enclosed) {
			currentExpression = enclosed.getInner();
		}
		return currentExpression;
	}

	private static String simpleName(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

	private static String literal(BigInteger value) {
		if (value.compareTo(INT_MIN) < 0 || value.compareTo(INT_MAX) > 0) {
			return value + "L";
		}
		return value.toString();
	}

	private static final class AllowedValues {

		private BigInteger minimum;

		private BigInteger maximum;

		private final Set<BigInteger> excluded = new HashSet<>();

		private AllowedValues(BigInteger minimum, BigInteger maximum) {
			this.minimum = minimum;
			this.maximum = maximum;
		}

		private void restrict(Comparison comparison, boolean expected) {
			Operator operator = comparison.operator();
			BigInteger constant = comparison.constant();
			switch (operator) {
				case EQUALS -> {
					if (expected) {
						this.exact(constant);
					}
					else {
						excluded.add(constant);
					}
				}
				case NOT_EQUALS -> {
					if (expected) {
						excluded.add(constant);
					}
					else {
						this.exact(constant);
					}
				}
				case GREATER -> {
					if (expected) {
						this.lower(constant.add(BigInteger.ONE));
					}
					else {
						this.upper(constant);
					}
				}
				case GREATER_EQUALS -> {
					if (expected) {
						this.lower(constant);
					}
					else {
						this.upper(constant.subtract(BigInteger.ONE));
					}
				}
				case LESS -> {
					if (expected) {
						this.upper(constant.subtract(BigInteger.ONE));
					}
					else {
						this.lower(constant);
					}
				}
				case LESS_EQUALS -> {
					if (expected) {
						this.upper(constant);
					}
					else {
						this.lower(constant.add(BigInteger.ONE));
					}
				}
				default -> throw new IllegalArgumentException("Not a comparison operator: " + operator);
			}
		}

		private void exact(BigInteger value) {
			this.lower(value);
			this.upper(value);
		}

		private void lower(BigInteger value) {
			if (value.compareTo(minimum) > 0) {
				minimum = value;
			}
		}

		private void upper(BigInteger value) {
			if (value.compareTo(maximum) < 0) {
				maximum = value;
			}
		}

		private Optional<BigInteger> onlyValue() {
			if (minimum.compareTo(maximum) > 0) {
				return Optional.empty();
			}
			long removed = excluded.stream()
				.filter(value -> value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0)
				.distinct()
				.count();
			BigInteger size = maximum.subtract(minimum).add(BigInteger.ONE).subtract(BigInteger.valueOf(removed));
			if (!size.equals(BigInteger.ONE)) {
				return Optional.empty();
			}
			BigInteger value = minimum;
			while (excluded.contains(value)) {
				value = value.add(BigInteger.ONE);
			}
			return Optional.of(value);
		}

	}

	private record Domain(BigInteger minimum, BigInteger maximum) {
	}

	private record Comparison(Expression value, BinaryExpr.Operator operator, BigInteger constant) {
	}

	private record Candidate(BinaryExpr expression, Expression value, String operator, BigInteger target) {
	}

}

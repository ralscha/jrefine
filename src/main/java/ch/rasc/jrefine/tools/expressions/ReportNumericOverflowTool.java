package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reports compile-time arithmetic whose Java result overflows its primitive
 * representation.
 */
public final class ReportNumericOverflowTool implements InspectionTool {

	@Override
	public String id() {
		return "report-numeric-overflow";
	}

	@Override
	public String description() {
		return "Report constant numeric expressions that overflow";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.filter(expression -> integer(expression).map(Evaluation::overflow).orElse(false)
					|| floating(expression).map(EvaluationDouble::overflow).orElse(false))
			.forEach(expression -> findings
				.add(Finding.at(expression, "Constant numeric expression overflows its primitive type")));
		context.compilationUnit()
			.findAll(UnaryExpr.class)
			.stream()
			.filter(expression -> integer(expression).map(Evaluation::overflow).orElse(false))
			.forEach(expression -> findings
				.add(Finding.at(expression, "Constant numeric expression overflows its primitive type")));
		return new ToolResult(List.copyOf(findings), false);
	}

	private static Optional<Evaluation> integer(Expression expression) {
		if (expression instanceof IntegerLiteralExpr literal) {
			return Optional.of(new Evaluation(BigInteger.valueOf(literal.asNumber().longValue()), 32, false));
		}
		if (expression instanceof LongLiteralExpr literal) {
			return Optional.of(new Evaluation(BigInteger.valueOf(literal.asNumber().longValue()), 64, false));
		}
		if (expression instanceof UnaryExpr unary && (unary.getOperator() == UnaryExpr.Operator.PLUS
				|| unary.getOperator() == UnaryExpr.Operator.MINUS)) {
			return integer(unary.getExpression()).map(value -> {
				BigInteger exact = unary.getOperator() == UnaryExpr.Operator.MINUS ? value.value().negate()
						: value.value();
				boolean overflow = outside(exact, value.bits());
				return new Evaluation(wrap(exact, value.bits()), value.bits(), overflow);
			});
		}
		if (!(expression instanceof BinaryExpr binary)) {
			return Optional.empty();
		}
		Optional<Evaluation> left = integer(binary.getLeft());
		Optional<Evaluation> right = integer(binary.getRight());
		if (left.isEmpty() || right.isEmpty()) {
			return Optional.empty();
		}
		int bits = Math.max(left.orElseThrow().bits(), right.orElseThrow().bits());
		BigInteger leftValue = left.orElseThrow().value();
		BigInteger rightValue = right.orElseThrow().value();
		BigInteger exact;
		try {
			exact = switch (binary.getOperator()) {
				case PLUS -> leftValue.add(rightValue);
				case MINUS -> leftValue.subtract(rightValue);
				case MULTIPLY -> leftValue.multiply(rightValue);
				case DIVIDE -> leftValue.divide(rightValue);
				case REMAINDER -> leftValue.remainder(rightValue);
				case LEFT_SHIFT -> leftValue.shiftLeft(maskShift(rightValue, bits));
				case SIGNED_RIGHT_SHIFT -> leftValue.shiftRight(maskShift(rightValue, bits));
				case UNSIGNED_RIGHT_SHIFT -> unsigned(leftValue, bits).shiftRight(maskShift(rightValue, bits));
				default -> null;
			};
		}
		catch (ArithmeticException ignored) {
			return Optional.empty();
		}
		if (exact == null) {
			return Optional.empty();
		}
		boolean canOverflow = switch (binary.getOperator()) {
			case PLUS, MINUS, MULTIPLY, DIVIDE, LEFT_SHIFT -> true;
			default -> false;
		};
		return Optional.of(new Evaluation(wrap(exact, bits), bits, canOverflow && outside(exact, bits)));
	}

	private static Optional<EvaluationDouble> floating(Expression expression) {
		if (expression instanceof DoubleLiteralExpr literal) {
			String value = literal.getValue().replace("_", "").toLowerCase(Locale.ROOT);
			boolean floatType = value.endsWith("f");
			if (value.endsWith("f") || value.endsWith("d")) {
				value = value.substring(0, value.length() - 1);
			}
			try {
				return Optional.of(new EvaluationDouble(Double.parseDouble(value), floatType, false));
			}
			catch (NumberFormatException ignored) {
				return Optional.empty();
			}
		}
		if (expression instanceof IntegerLiteralExpr literal) {
			return Optional.of(new EvaluationDouble(literal.asNumber().doubleValue(), false, false));
		}
		if (expression instanceof LongLiteralExpr literal) {
			return Optional.of(new EvaluationDouble(literal.asNumber().doubleValue(), false, false));
		}
		if (expression instanceof UnaryExpr unary && (unary.getOperator() == UnaryExpr.Operator.PLUS
				|| unary.getOperator() == UnaryExpr.Operator.MINUS)) {
			return floating(unary.getExpression()).map(value -> new EvaluationDouble(
					unary.getOperator() == UnaryExpr.Operator.MINUS ? -value.value() : value.value(), value.floatType(),
					false));
		}
		if (!(expression instanceof BinaryExpr binary)) {
			return Optional.empty();
		}
		Optional<EvaluationDouble> left = floating(binary.getLeft());
		Optional<EvaluationDouble> right = floating(binary.getRight());
		if (left.isEmpty() || right.isEmpty() || left.orElseThrow().floatType() == right.orElseThrow().floatType()
				&& binary.getLeft() instanceof IntegerLiteralExpr && binary.getRight() instanceof IntegerLiteralExpr) {
			return Optional.empty();
		}
		boolean floatType = left.orElseThrow().floatType() || right.orElseThrow().floatType();
		double leftValue = left.orElseThrow().value();
		double rightValue = right.orElseThrow().value();
		double exact = switch (binary.getOperator()) {
			case PLUS -> leftValue + rightValue;
			case MINUS -> leftValue - rightValue;
			case MULTIPLY -> leftValue * rightValue;
			case DIVIDE -> leftValue / rightValue;
			case REMAINDER -> leftValue % rightValue;
			default -> Double.NaN;
		};
		if (Double.isNaN(exact)) {
			return Optional.empty();
		}
		double rounded = floatType ? (double) (float) exact : exact;
		return Optional.of(new EvaluationDouble(rounded, floatType,
				Double.isInfinite(rounded) && Double.isFinite(leftValue) && Double.isFinite(rightValue)));
	}

	private static boolean outside(BigInteger value, int bits) {
		return value.compareTo(BigInteger.ONE.shiftLeft(bits - 1).negate()) < 0
				|| value.compareTo(BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)) > 0;
	}

	private static BigInteger wrap(BigInteger value, int bits) {
		BigInteger currentValue = value;
		BigInteger modulus = BigInteger.ONE.shiftLeft(bits);
		currentValue = currentValue.mod(modulus);
		return currentValue.testBit(bits - 1) ? currentValue.subtract(modulus) : currentValue;
	}

	private static BigInteger unsigned(BigInteger value, int bits) {
		return value.signum() < 0 ? value.add(BigInteger.ONE.shiftLeft(bits)) : value;
	}

	private static int maskShift(BigInteger value, int bits) {
		return value.intValue() & (bits == 64 ? 63 : 31);
	}

	private record Evaluation(BigInteger value, int bits, boolean overflow) {
	}

	private record EvaluationDouble(double value, boolean floatType, boolean overflow) {
	}

}

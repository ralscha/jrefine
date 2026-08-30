package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.OptionalInt;
import java.util.Set;
import java.util.Optional;

/**
 * Reports impossible bit-mask comparisons and shift distances discarded by Java's masking
 * rules.
 */
public final class ReportBitwiseOperationIssuesTool implements InspectionTool {

	private static final Set<BinaryExpr.Operator> SHIFTS = Set.of(BinaryExpr.Operator.LEFT_SHIFT,
			BinaryExpr.Operator.SIGNED_RIGHT_SHIFT, BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT);

	@Override
	public String id() {
		return "report-bitwise-operation-issues";
	}

	@Override
	public String description() {
		return "Report incompatible bit masks and inappropriate constant shift distances";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		incompatibleMasks(context, findings);
		shifts(context, findings);
		compoundShifts(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void incompatibleMasks(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr comparison : context.compilationUnit().findAll(BinaryExpr.class)) {
			if (comparison.getOperator() != BinaryExpr.Operator.EQUALS
					&& comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
				continue;
			}
			Expression left = unwrap(comparison.getLeft());
			Expression right = unwrap(comparison.getRight());
			BinaryExpr leftMask = bitMask(left).orElse(null);
			BinaryExpr rightMask = bitMask(right).orElse(null);
			boolean maskOnLeft = leftMask != null;
			BinaryExpr masked = maskOnLeft ? leftMask : rightMask;
			Expression expectedExpression = maskOnLeft ? right : left;
			OptionalLong expected = constant(expectedExpression);
			if (masked == null || expected.isEmpty() || expected.getAsLong() < 0) {
				continue;
			}
			OptionalLong mask = mask(masked);
			if (mask.isEmpty() || mask.getAsLong() < 0) {
				continue;
			}
			boolean impossible = switch (masked.getOperator()) {
				case BINARY_AND -> (expected.getAsLong() & ~mask.getAsLong()) != 0;
				case BINARY_OR -> (expected.getAsLong() & mask.getAsLong()) != mask.getAsLong();
				default -> false;
			};
			if (impossible) {
				findings.add(Finding.at(comparison, "Incompatible bitwise mask comparison is guaranteed to be "
						+ (comparison.getOperator() == BinaryExpr.Operator.NOT_EQUALS)));
			}
		}
	}

	private static OptionalLong mask(BinaryExpr expression) {
		if (expression.getOperator() != BinaryExpr.Operator.BINARY_AND
				&& expression.getOperator() != BinaryExpr.Operator.BINARY_OR) {
			return OptionalLong.empty();
		}
		OptionalLong left = constant(expression.getLeft());
		return left.isPresent() ? left : constant(expression.getRight());
	}

	private static Optional<BinaryExpr> bitMask(Expression expression) {
		if (!(expression instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.BINARY_AND
				&& binary.getOperator() != BinaryExpr.Operator.BINARY_OR) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(binary);
	}

	private static void shifts(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr shift : context.compilationUnit().findAll(BinaryExpr.class)) {
			if (!SHIFTS.contains(shift.getOperator())) {
				continue;
			}
			OptionalLong distance = constant(shift.getRight());
			if (distance.isEmpty()) {
				continue;
			}
			OptionalInt width = shiftWidth(context, shift.getLeft(), shift);
			if (width.isEmpty()) {
				continue;
			}
			int bits = width.getAsInt();
			if (distance.getAsLong() < 0 || distance.getAsLong() >= bits) {
				findings.add(Finding.at(shift, "Shift operation uses inappropriate constant distance "
						+ distance.getAsLong() + "; Java masks it modulo " + bits));
			}
		}
	}

	private static void compoundShifts(InspectionContext context, List<Finding> findings) {
		for (AssignExpr assignment : context.compilationUnit().findAll(AssignExpr.class)) {
			if (!Set
				.of(AssignExpr.Operator.LEFT_SHIFT, AssignExpr.Operator.SIGNED_RIGHT_SHIFT,
						AssignExpr.Operator.UNSIGNED_RIGHT_SHIFT)
				.contains(assignment.getOperator())) {
				continue;
			}
			OptionalLong distance = constant(assignment.getValue());
			if (distance.isEmpty()) {
				continue;
			}
			OptionalInt width = shiftWidth(context, assignment.getTarget(), assignment);
			if (width.isEmpty()) {
				continue;
			}
			int bits = width.getAsInt();
			if (distance.getAsLong() < 0 || distance.getAsLong() >= bits) {
				findings.add(Finding.at(assignment, "Compound shift uses inappropriate constant distance "
						+ distance.getAsLong() + "; Java masks it modulo " + bits));
			}
		}
	}

	private static OptionalLong constant(Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (currentExpression instanceof IntegerLiteralExpr literal) {
			return OptionalLong.of(literal.asNumber().longValue());
		}
		if (currentExpression instanceof LongLiteralExpr literal) {
			return OptionalLong.of(literal.asNumber().longValue());
		}
		if (currentExpression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS) {
			OptionalLong value = constant(unary.getExpression());
			return value.isPresent() ? OptionalLong.of(-value.getAsLong()) : OptionalLong.empty();
		}
		return OptionalLong.empty();
	}

	private static OptionalInt shiftWidth(InspectionContext context, Expression operand,
			com.github.javaparser.ast.Node use) {
		return NumericSupport.typeOf(context, operand, use)
			.filter(NumericSupport::isIntegral)
			.map(type -> "long".equals(NumericSupport.simpleName(type)) ? 64 : 32)
			.map(OptionalInt::of)
			.orElseGet(OptionalInt::empty);
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression.isEnclosedExpr()) {
			currentExpression = currentExpression.asEnclosedExpr().getInner();
		}
		return currentExpression;
	}

}

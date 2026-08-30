package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Simplifies identity, absorbing, and idempotent bitwise expressions. */
public final class SimplifyPointlessBitwiseExpressionsTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-pointless-bitwise-expressions";
	}

	@Override
	public String description() {
		return "Simplify pointless bitwise expressions";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(SimplifyPointlessBitwiseExpressionsTool::candidate)
			.flatMap(Optional::stream)
			.filter(candidate -> !AstSupport.hasComment(context, candidate.binary()))
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && other.binary().isAncestorOf(candidate.binary())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.binary(), "Simplify pointless bitwise expression"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.binary().getRange().orElseThrow(), candidate.literalReplacement() != null
							? candidate.literalReplacement() : context.editor().text(candidate.expression()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(BinaryExpr binary) {
		Operator operator = binary.getOperator();
		if (operator != BinaryExpr.Operator.BINARY_AND && operator != BinaryExpr.Operator.BINARY_OR
				&& operator != BinaryExpr.Operator.XOR) {
			return Optional.empty();
		}
		if (binary.getLeft().equals(binary.getRight()) && stable(binary.getLeft())) {
			return Optional
				.of(new Candidate(binary, binary.getLeft(), operator == BinaryExpr.Operator.XOR ? "0" : null));
		}
		if (zero(binary.getRight())) {
			if (operator == BinaryExpr.Operator.BINARY_OR || operator == BinaryExpr.Operator.XOR) {
				return Optional.of(new Candidate(binary, binary.getLeft(), null));
			}
			if (stable(binary.getLeft())) {
				return Optional.of(new Candidate(binary, binary.getLeft(), "0"));
			}
		}
		if (zero(binary.getLeft())) {
			if (operator == BinaryExpr.Operator.BINARY_OR || operator == BinaryExpr.Operator.XOR) {
				return Optional.of(new Candidate(binary, binary.getRight(), null));
			}
			if (stable(binary.getRight())) {
				return Optional.of(new Candidate(binary, binary.getRight(), "0"));
			}
		}
		if (minusOne(binary.getRight())) {
			if (operator == BinaryExpr.Operator.BINARY_AND) {
				return Optional.of(new Candidate(binary, binary.getLeft(), null));
			}
			if (operator == BinaryExpr.Operator.BINARY_OR && stable(binary.getLeft())) {
				return Optional.of(new Candidate(binary, binary.getLeft(), "-1"));
			}
		}
		return Optional.empty();
	}

	private static boolean zero(Expression expression) {
		return expression instanceof IntegerLiteralExpr integer && integer.asNumber().longValue() == 0
				|| expression instanceof LongLiteralExpr longValue && longValue.asNumber().longValue() == 0;
	}

	private static boolean minusOne(Expression expression) {
		return expression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS
				&& (unary.getExpression() instanceof IntegerLiteralExpr integer && integer.asNumber().longValue() == 1
						|| unary.getExpression() instanceof LongLiteralExpr longValue
								&& longValue.asNumber().longValue() == 1);
	}

	private static boolean stable(Expression expression) {
		if (expression.isLiteralExpr() || expression.isNameExpr() || expression.isThisExpr()) {
			return true;
		}
		return expression.isFieldAccessExpr() && stable(expression.asFieldAccessExpr().getScope());
	}

	private record Candidate(BinaryExpr binary, Expression expression, String literalReplacement) {
	}

}

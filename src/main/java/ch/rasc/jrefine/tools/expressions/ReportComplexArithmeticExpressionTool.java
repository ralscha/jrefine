package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.List;
import java.util.Set;

/** Reports numeric arithmetic trees with more than five terms. */
public final class ReportComplexArithmeticExpressionTool implements PolicyInspectionTool {

	private static final Set<BinaryExpr.Operator> ARITHMETIC = Set.of(BinaryExpr.Operator.PLUS,
			BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY, BinaryExpr.Operator.DIVIDE,
			BinaryExpr.Operator.REMAINDER);

	@Override
	public String id() {
		return "report-complex-arithmetic-expression";
	}

	@Override
	public String description() {
		return "Report overly complex arithmetic expressions";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Finding> findings = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.filter(binary -> ARITHMETIC.contains(binary.getOperator()))
			.filter(binary -> NumericSupport.typeOf(context, binary, binary)
				.filter(NumericSupport::isNumeric)
				.isPresent())
			.filter(binary -> binary.getParentNode()
				.filter(BinaryExpr.class::isInstance)
				.map(BinaryExpr.class::cast)
				.filter(parent -> ARITHMETIC.contains(parent.getOperator()))
				.isEmpty())
			.filter(binary -> terms(binary) > 5)
			.map(binary -> Finding.at(binary, "Arithmetic expression has more than five terms"))
			.toList();
		return new ToolResult(List.copyOf(findings), false);
	}

	private static int terms(Expression expression) {
		if (expression instanceof BinaryExpr binary && ARITHMETIC.contains(binary.getOperator())) {
			return terms(binary.getLeft()) + terms(binary.getRight());
		}
		return 1;
	}

}

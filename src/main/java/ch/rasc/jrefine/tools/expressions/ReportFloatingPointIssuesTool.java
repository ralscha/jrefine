package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reports floating-point comparisons and computations with unstable or undefined results.
 */
public final class ReportFloatingPointIssuesTool implements PolicyInspectionTool {

	private static final Set<String> NON_REPRODUCIBLE = Set.of("acos", "asin", "atan", "atan2", "cbrt", "cos", "cosh",
			"exp", "expm1", "hypot", "IEEEremainder", "log", "log10", "log1p", "pow", "sin", "sinh", "sqrt", "tan",
			"tanh");

	@Override
	public String id() {
		return "report-floating-point-issues";
	}

	@Override
	public String description() {
		return "Report division by zero, floating equality, and non-reproducible Math calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			if ((binary.getOperator() == BinaryExpr.Operator.DIVIDE
					|| binary.getOperator() == BinaryExpr.Operator.REMAINDER) && zero(binary.getRight())) {
				findings.add(Finding.at(binary, "Division or remainder by zero"));
			}
			if ((binary.getOperator() == BinaryExpr.Operator.EQUALS
					|| binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
					&& (floating(context, binary.getLeft(), binary) || floating(context, binary.getRight(), binary))) {
				findings.add(Finding.at(binary, "Floating-point values are compared for exact equality"));
			}
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isPresent() && NON_REPRODUCIBLE.contains(call.getNameAsString())
					&& ExpressionToolSupport.knownType(context.compilationUnit(),
							call.getScope().orElseThrow().toString(), "java.lang", Set.of("Math"))) {
				findings
					.add(Finding.at(call, "Math call is not guaranteed to produce bit-for-bit reproducible results"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean floating(InspectionContext context, Expression expression, Node use) {
		return NumericSupport.typeOf(context, expression, use).filter(NumericSupport::isFloatingPoint).isPresent();
	}

	private static boolean zero(Expression expression) {
		Expression currentExpression = expression;
		int sign = 1;
		while (currentExpression instanceof UnaryExpr unary && (unary.getOperator() == UnaryExpr.Operator.PLUS
				|| unary.getOperator() == UnaryExpr.Operator.MINUS)) {
			if (unary.getOperator() == UnaryExpr.Operator.MINUS) {
				sign = -sign;
			}
			currentExpression = unary.getExpression();
		}
		Optional<Double> value;
		if (currentExpression instanceof IntegerLiteralExpr literal) {
			value = Optional.of(literal.asNumber().doubleValue());
		}
		else if (currentExpression instanceof LongLiteralExpr literal) {
			value = Optional.of(literal.asNumber().doubleValue());
		}
		else if (currentExpression instanceof DoubleLiteralExpr literal) {
			String spelling = literal.getValue().replace("_", "").toLowerCase(Locale.ROOT);
			if (spelling.endsWith("f") || spelling.endsWith("d")) {
				spelling = spelling.substring(0, spelling.length() - 1);
			}
			try {
				value = Optional.of(Double.parseDouble(spelling));
			}
			catch (NumberFormatException ignored) {
				value = Optional.empty();
			}
		}
		else {
			value = Optional.empty();
		}
		return value.isPresent() && Double.compare(Math.abs(sign * value.orElseThrow()), 0.0) == 0;
	}

}

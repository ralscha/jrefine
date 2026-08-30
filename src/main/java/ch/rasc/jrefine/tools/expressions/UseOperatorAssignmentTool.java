package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.AssignExpr.Operator;
import java.util.List;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Rewrites x = x op value to the corresponding compound assignment. */
public final class UseOperatorAssignmentTool implements InspectionTool {

	@Override
	public String id() {
		return "use-operator-assignment";
	}

	@Override
	public String description() {
		return "Replace repeated assignments such as x = x + y with x += y";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<AssignExpr> candidates = context.compilationUnit()
			.findAll(AssignExpr.class)
			.stream()
			.filter(assign -> assign.getOperator() == AssignExpr.Operator.ASSIGN)
			.filter(assign -> assign.getValue() instanceof BinaryExpr)
			.filter(assign -> safeTarget(assign.getTarget()))
			.filter(assign -> assign.getTarget().equals(assign.getValue().asBinaryExpr().getLeft()))
			.filter(assign -> assign.getValue().asBinaryExpr().getOperator().toAssignOperator().isPresent())
			.filter(assign -> !hasComment(context.editor().text(assign)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (AssignExpr assign : candidates) {
			BinaryExpr binary = assign.getValue().asBinaryExpr();
			Operator operator = binary.getOperator().toAssignOperator().orElseThrow();
			findings.add(Finding.at(assign, "Use compound assignment operator '" + operator.asString() + "'"));
			if (applyFixes) {
				String replacement = context.editor().text(assign.getTarget()) + " " + operator.asString() + " "
						+ context.editor().text(binary.getRight());
				context.editor().replace(assign.getRange().orElseThrow(), replacement);
				assign.setOperator(operator).setValue(binary.getRight().clone());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean safeTarget(Expression expression) {
		if (expression instanceof NameExpr || expression instanceof ThisExpr) {
			return true;
		}
		return expression instanceof FieldAccessExpr access && safeTarget(access.getScope());
	}

	private static boolean hasComment(String source) {
		return source.contains("//") || source.contains("/*");
	}

}

package ch.rasc.jrefine.tools.expressions;

import java.util.Optional;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.github.javaparser.ast.expr.Expression;

/** Reports avoidable String and mutable-string allocation patterns. */
public final class ReportStringPerformanceTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-string-performance";
	}

	@Override
	public String description() {
		return "Report regex, capacity, concatenation, and single-character String inefficiencies";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if ("append".equals(call.getNameAsString()) && call.getArguments().size() == 1
					&& call.getArgument(0) instanceof BinaryExpr binary
					&& binary.getOperator() == BinaryExpr.Operator.PLUS && call.getScope().isPresent()
					&& TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
						.map(ExpressionToolSupport::simpleName)
						.filter(type -> Set.of("StringBuilder", "StringBuffer", "Appendable").contains(type))
						.isPresent()) {
				findings
					.add(Finding.at(call, "String concatenation passed to append() creates an intermediate String"));
			}
		}
		for (AssignExpr assignment : context.compilationUnit().findAll(AssignExpr.class)) {
			if (assignment.getOperator() != AssignExpr.Operator.PLUS
					|| !knownString(context, assignment.getTarget(), assignment) || !loopAncestor(assignment)) {
				continue;
			}
			findings.add(Finding.at(assignment, "Repeated String append can use StringBuilder"));
			findings.add(Finding.at(assignment, "String concatenation in a loop creates an object per iteration"));
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean knownString(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof StringLiteralExpr) {
			return true;
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.filter(type -> ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.lang",
					Set.of("String")))
			.isPresent();
	}

	private static boolean loopAncestor(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof ForStmt || value instanceof ForEachStmt || value instanceof WhileStmt
					|| value instanceof DoStmt) {
				return true;
			}
			parent = value.getParentNode();
		}
		return false;
	}

}

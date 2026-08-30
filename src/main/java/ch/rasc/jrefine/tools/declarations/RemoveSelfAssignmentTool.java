package ch.rasc.jrefine.tools.declarations;

import java.util.List;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Removes no-op assignments of a local variable or parameter to itself. */
public final class RemoveSelfAssignmentTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-self-assignment";
	}

	@Override
	public String description() {
		return "Remove assignments of local variables or parameters to themselves";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ExpressionStmt> candidates = context.compilationUnit()
			.findAll(AssignExpr.class)
			.stream()
			.filter(assign -> assign.getOperator() == AssignExpr.Operator.ASSIGN)
			.filter(assign -> assign.getTarget() instanceof NameExpr && assign.getValue() instanceof NameExpr)
			.filter(assign -> assign.getTarget()
				.asNameExpr()
				.getNameAsString()
				.equals(assign.getValue().asNameExpr().getNameAsString()))
			.filter(assign -> assign.getParentNode().orElse(null) instanceof ExpressionStmt)
			.filter(assign -> TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(),
					assign.getTarget().asNameExpr().getNameAsString(), assign))
			.map(assign -> (ExpressionStmt) assign.getParentNode().orElseThrow())
			.filter(statement -> !AstSupport.hasComment(context, statement))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (ExpressionStmt statement : candidates) {
			findings.add(Finding.at(statement, "Remove assignment of variable to itself"));
			if (applyFixes) {
				context.editor().removeLine(statement);
				statement.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

}

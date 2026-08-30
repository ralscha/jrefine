package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.Position;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Joins an uninitialized local declaration with the immediately following assignment. */
public final class JoinDeclarationAndAssignmentTool implements InspectionTool {

	@Override
	public String id() {
		return "join-declaration-and-assignment";
	}

	@Override
	public String description() {
		return "Join adjacent local variable declarations and assignments";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		for (BlockStmt block : context.compilationUnit().findAll(BlockStmt.class)) {
			for (int index = 0; index + 1 < block.getStatements().size(); index++) {
				candidate(block.getStatement(index), block.getStatement(index + 1)).ifPresent(candidates::add);
			}
		}
		candidates.removeIf(candidate -> hasComment(context.editor().text(candidate.declarationStatement()))
				|| hasComment(context.editor().text(candidate.assignmentStatement())));
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			VariableDeclarator variable = candidate.declaration().getVariables().get(0);
			findings.add(Finding.at(candidate.declaration(),
					"Join declaration and assignment of '" + variable.getNameAsString() + "'"));
			if (applyFixes) {
				Position semicolon = candidate.declarationStatement().getRange().orElseThrow().end;
				context.editor().insert(semicolon, " = " + context.editor().text(candidate.assignment().getValue()));
				context.editor().removeLine(candidate.assignmentStatement());
				variable.setInitializer(candidate.assignment().getValue().clone());
				candidate.assignmentStatement().remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(Statement first, Statement second) {
		if (!(first instanceof ExpressionStmt declarationStatement)
				|| !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || declaration.getVariable(0).getInitializer().isPresent()
				|| !(second instanceof ExpressionStmt assignmentStatement)
				|| !(assignmentStatement.getExpression() instanceof AssignExpr assignment)
				|| assignment.getOperator() != AssignExpr.Operator.ASSIGN
				|| !(assignment.getTarget() instanceof NameExpr name)
				|| !name.getNameAsString().equals(declaration.getVariable(0).getNameAsString())) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Candidate(declarationStatement, declaration, assignmentStatement, assignment));
	}

	private record Candidate(ExpressionStmt declarationStatement, VariableDeclarationExpr declaration,
			ExpressionStmt assignmentStatement, AssignExpr assignment) {
	}

	private static boolean hasComment(String source) {
		return source.contains("//") || source.contains("/*");
	}

}

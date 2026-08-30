package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Moves an immediately following cast declaration into an instanceof pattern. */
public final class UsePatternVariableTool implements InspectionTool {

	@Override
	public String id() {
		return "use-pattern-variable";
	}

	@Override
	public int minimumJavaVersion() {
		return 16;
	}

	@Override
	public String description() {
		return "Move cast declarations into instanceof pattern variables";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(IfStmt.class)
			.stream()
			.map(statement -> candidate(context, statement))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.instanceOf(), "Use instanceof pattern variable"));
			if (applyFixes) {
				context.editor()
					.insertAfter(candidate.instanceOf().getType().getRange().orElseThrow().end,
							" " + candidate.variableName());
				context.editor().removeLine(candidate.declarationStatement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, IfStmt statement) {
		if (!(statement.getCondition() instanceof InstanceOfExpr instanceOf) || instanceOf.getPattern().isPresent()
				|| !(statement.getThenStmt() instanceof BlockStmt body) || body.getStatements().isEmpty()
				|| !(body.getStatement(0) instanceof ExpressionStmt declarationStatement)
				|| !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || !declaration.getModifiers().isEmpty()
				|| !declaration.getAnnotations().isEmpty() || AstSupport.hasComment(context, instanceOf)
				|| AstSupport.hasComment(context, declarationStatement)) {
			return Optional.empty();
		}
		VariableDeclarator variable = declaration.getVariable(0);
		if (!(variable.getInitializer().orElse(null) instanceof CastExpr cast)
				|| !cast.getType().equals(instanceOf.getType()) || !variable.getType().equals(instanceOf.getType())
				|| !cast.getExpression().equals(instanceOf.getExpression())) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(instanceOf, declarationStatement, variable.getNameAsString()));
	}

	private record Candidate(InstanceOfExpr instanceOf, ExpressionStmt declarationStatement, String variableName) {
	}

}

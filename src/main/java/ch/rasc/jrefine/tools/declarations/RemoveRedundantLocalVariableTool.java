package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;

/** Inlines a local variable that is immediately returned. */
public final class RemoveRedundantLocalVariableTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-local-variable";
	}

	@Override
	public String description() {
		return "Inline local variables that are immediately returned";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BlockStmt.class)
			.stream()
			.flatMap(block -> candidates(context, block).stream())
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.declarationStatement(), "Inline redundant local variable"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.returnedName().getRange().orElseThrow(),
							context.editor().text(candidate.initializer()));
				context.editor().removeLine(candidate.declarationStatement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static List<Candidate> candidates(InspectionContext context, BlockStmt block) {
		ArrayList<Candidate> result = new ArrayList<>();
		for (int index = 0; index + 1 < block.getStatements().size(); index++) {
			candidate(context, block.getStatement(index), block.getStatement(index + 1)).ifPresent(result::add);
		}
		return result;
	}

	private static Optional<Candidate> candidate(InspectionContext context, Statement first, Statement second) {
		if (!(first instanceof ExpressionStmt declarationStatement)
				|| !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || !(second instanceof ReturnStmt returned)
				|| !(returned.getExpression().orElse(null) instanceof NameExpr name)
				|| AstSupport.hasComment(context, declarationStatement)) {
			return Optional.empty();
		}
		VariableDeclarator variable = declaration.getVariable(0);
		Expression initializer = variable.getInitializer().orElse(null);
		if (initializer == null || !name.getNameAsString().equals(variable.getNameAsString())
				|| initializer instanceof LambdaExpr || initializer instanceof MethodReferenceExpr
				|| initializer instanceof ObjectCreationExpr creation
						&& creation.getType().getTypeArguments().filter(arguments -> arguments.isEmpty()).isPresent()) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(declarationStatement, name, initializer));
	}

	private record Candidate(ExpressionStmt declarationStatement, NameExpr returnedName, Expression initializer) {
	}

}

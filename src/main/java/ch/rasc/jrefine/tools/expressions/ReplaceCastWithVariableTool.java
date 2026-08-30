package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;

/** Reuses a prior local initialized by the same stable cast. */
public final class ReplaceCastWithVariableTool implements InspectionTool {

	@Override
	public String id() {
		return "replace-cast-with-variable";
	}

	@Override
	public String description() {
		return "Replace repeated casts with an existing local variable";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(CastExpr.class)
			.stream()
			.map(cast -> candidate(context, cast))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.cast(),
					"Replace repeated cast with '" + candidate.variable().getNameAsString() + "'"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.cast().getRange().orElseThrow(), candidate.variable().getNameAsString());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, CastExpr cast) {
		if (!(cast.getExpression() instanceof NameExpr source) || AstSupport.hasComment(context, cast)
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), source.getNameAsString(), cast)) {
			return Optional.empty();
		}
		BlockStmt block = AstSupport.ancestor(cast, BlockStmt.class).orElse(null);
		Statement useStatement = block == null ? null : directStatement(cast, block).orElse(null);
		if (block == null || useStatement == null || insideExpressionLambda(cast, block)) {
			return Optional.empty();
		}
		int useIndex = block.getStatements().indexOf(useStatement);
		return block.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> seed(variable, cast, source, block, useIndex))
			.filter(variable -> unchangedBetween(block, variable, useStatement, source.getNameAsString(),
					variable.getNameAsString()))
			.max(Comparator.comparing(variable -> variable.getBegin().orElseThrow()))
			.map(variable -> new Candidate(cast, variable));
	}

	private static boolean seed(VariableDeclarator variable, CastExpr repeated, NameExpr source, BlockStmt block,
			int useIndex) {
		if (!(variable.getInitializer().orElse(null) instanceof CastExpr initial)
				|| !(initial.getExpression() instanceof NameExpr initialSource)
				|| !initialSource.getNameAsString().equals(source.getNameAsString())
				|| !initial.getType().equals(repeated.getType()) || !variable.getType().equals(repeated.getType())
				|| AstSupport.ancestor(variable, BlockStmt.class).orElse(null) != block) {
			return false;
		}
		Statement statement = directStatement(variable, block).orElse(null);
		return statement != null && block.getStatements().indexOf(statement) < useIndex;
	}

	private static boolean unchangedBetween(BlockStmt block, VariableDeclarator variable, Statement useStatement,
			String source, String replacement) {
		Statement declaration = directStatement(variable, block).orElseThrow();
		int from = block.getStatements().indexOf(declaration) + 1;
		int to = block.getStatements().indexOf(useStatement);
		for (int index = from; index <= to; index++) {
			Statement statement = block.getStatement(index);
			if (statement.findAll(AssignExpr.class)
				.stream()
				.anyMatch(assignment -> assignment.getTarget() instanceof NameExpr name
						&& (name.getNameAsString().equals(source) || name.getNameAsString().equals(replacement)))) {
				return false;
			}
			if (index < to && statement.findAll(VariableDeclarator.class)
				.stream()
				.anyMatch(local -> local.getNameAsString().equals(source)
						|| local.getNameAsString().equals(replacement))) {
				return false;
			}
		}
		return true;
	}

	private static Optional<Statement> directStatement(Node node, BlockStmt block) {
		Node current = node;
		while (current.getParentNode().isPresent() && current.getParentNode().orElseThrow() != block) {
			current = current.getParentNode().orElseThrow();
		}
		return current instanceof Statement statement ? Optional.of(statement) : Optional.empty();
	}

	private static boolean insideExpressionLambda(Node node, BlockStmt block) {
		Node current = node;
		while (current != block && current.getParentNode().isPresent()) {
			if (current instanceof LambdaExpr) {
				return true;
			}
			current = current.getParentNode().orElseThrow();
		}
		return false;
	}

	private record Candidate(CastExpr cast, VariableDeclarator variable) {
	}

}

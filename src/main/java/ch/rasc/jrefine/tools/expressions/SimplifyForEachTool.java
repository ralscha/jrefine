package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;

/** Extracts filter/add forEach patterns into a stream collection pipeline. */
public final class SimplifyForEachTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "ArrayList", "LinkedList",
			"HashSet", "TreeSet");

	@Override
	public String id() {
		return "simplify-for-each";
	}

	@Override
	public String description() {
		return "Simplify filter/add forEach calls into stream pipelines";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String collectors = candidates.isEmpty() ? "Collectors"
				: ImportSupport.useType(context, "java.util.stream.Collectors", applyFixes);
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.call(), "Simplify forEach call with stream pipeline"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.initializer().getRange().orElseThrow(),
							context.editor().text(candidate.source()) + ".stream().filter(" + candidate.parameter()
									+ " -> " + context.editor().text(candidate.condition()) + ").collect(" + collectors
									+ ".toList())");
				context.editor().removeLine(candidate.statement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (!"forEach".equals(call.getNameAsString()) || call.getArguments().size() != 1 || call.getScope().isEmpty()
				|| !(call.getArgument(0) instanceof LambdaExpr lambda) || lambda.getParameters().size() != 1
				|| !(lambda.getBody() instanceof BlockStmt body) || body.getStatements().size() != 1
				|| !(body.getStatement(0) instanceof IfStmt conditional) || conditional.getElseStmt().isPresent()
				|| !(singleExpression(conditional.getThenStmt()) instanceof MethodCallExpr add)
				|| !"add".equals(add.getNameAsString()) || add.getArguments().size() != 1
				|| !(add.getScope().orElse(null) instanceof NameExpr target)
				|| !(add.getArgument(0) instanceof NameExpr added)
				|| !added.getNameAsString().equals(lambda.getParameter(0).getNameAsString())
				|| !(call.getParentNode().orElse(null) instanceof ExpressionStmt statement)
				|| !(statement.getParentNode().orElse(null) instanceof BlockStmt owner)
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		int index = owner.getStatements().indexOf(statement);
		if (index < 1 || !(owner.getStatement(index - 1) instanceof ExpressionStmt previous)
				|| !(previous.getExpression() instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1) {
			return Optional.empty();
		}
		VariableDeclarator variable = declaration.getVariable(0);
		if (!variable.getNameAsString().equals(target.getNameAsString())
				|| !(variable.getInitializer().orElse(null) instanceof ObjectCreationExpr initializer)
				|| !initializer.getArguments().isEmpty() || initializer.getAnonymousClassBody().isPresent()
				|| !TypeLookup.isKnownJavaUtilType(context.compilationUnit(), variable.getType().asString(),
						Set.of("List"))
				|| !TypeLookup.isKnownJavaUtilType(context.compilationUnit(), initializer.getType().asString(),
						Set.of("ArrayList"))
				|| TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
					.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, COLLECTION_TYPES))
					.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, statement, initializer, call.getScope().orElseThrow(),
				lambda.getParameter(0).getNameAsString(), conditional.getCondition()));
	}

	private static Expression singleExpression(Statement statement) {
		if (statement instanceof ExpressionStmt expression) {
			return expression.getExpression();
		}
		if (statement instanceof BlockStmt block && block.getStatements().size() == 1
				&& block.getStatement(0) instanceof ExpressionStmt expression) {
			return expression.getExpression();
		}
		return null;
	}

	private record Candidate(MethodCallExpr call, ExpressionStmt statement, ObjectCreationExpr initializer,
			Expression source, String parameter, Expression condition) {
	}

}

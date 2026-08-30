package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;

/**
 * Narrows a local variable to the stable initializer type when every use repeats the same
 * cast.
 */
public final class NarrowVariableTypeTool implements InspectionTool {

	@Override
	public String id() {
		return "narrow-variable-type";
	}

	@Override
	public String description() {
		return "Narrow local variable types to remove repeated casts";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.map(variable -> candidate(context, variable))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.variable(), "Narrow variable type to remove unnecessary casts"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.variable().getType().getRange().orElseThrow(),
							context.editor().text(candidate.casts().get(0).getType()));
				for (CastExpr cast : candidate.casts()) {
					context.editor()
						.replace(cast.getRange().orElseThrow(), context.editor().text(cast.getExpression()));
				}
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, VariableDeclarator variable) {
		if (AstSupport.ancestor(variable, FieldDeclaration.class).isPresent()
				|| AstSupport.ancestor(variable, LambdaExpr.class).isPresent()
				|| !(variable.getParentNode().orElse(null) instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || variable.getInitializer().isEmpty()
				|| variable.getType().isVarType() || AstSupport.hasComment(context, declaration)) {
			return Optional.empty();
		}
		String name = variable.getNameAsString();
		Node boundary = callableBoundary(variable).orElse(null);
		boolean shadowed = context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.anyMatch(other -> other != variable && other.getNameAsString().equals(name)
					&& callableBoundary(other).orElse(null) == boundary)
				|| context.compilationUnit()
					.findAll(Parameter.class)
					.stream()
					.anyMatch(parameter -> parameter.getNameAsString().equals(name)
							&& callableBoundary(parameter).orElse(null) == boundary);
		if (shadowed) {
			return Optional.empty();
		}
		List<NameExpr> uses = context.compilationUnit()
			.findAll(NameExpr.class)
			.stream()
			.filter(use -> use.getNameAsString().equals(name))
			.filter(use -> sameCallable(variable, use))
			.toList();
		if (uses.isEmpty()) {
			return Optional.empty();
		}
		ArrayList<CastExpr> casts = new ArrayList<>();
		for (NameExpr use : uses) {
			if (!(use.getParentNode().orElse(null) instanceof CastExpr cast) || cast.getExpression() != use
					|| cast.getType().isPrimitiveType() || AstSupport.hasComment(context, cast)) {
				return Optional.empty();
			}
			casts.add(cast);
		}
		String target = casts.getFirst().getType().asString();
		if (target.equals(variable.getType().asString())
				|| casts.stream().anyMatch(cast -> !cast.getType().asString().equals(target))
				|| !initializerHasType(context, variable, target)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(variable, List.copyOf(casts)));
	}

	private static boolean initializerHasType(InspectionContext context, VariableDeclarator variable, String target) {
		Expression initializer = variable.getInitializer().orElseThrow();
		String simpleTarget = ExpressionToolSupport.simpleName(target);
		if (initializer instanceof StringLiteralExpr) {
			return "String".equals(simpleTarget) && ExpressionToolSupport.knownType(context.compilationUnit(), target,
					"java.lang", Set.of("String"));
		}
		if (initializer instanceof ObjectCreationExpr creation) {
			return creation.getType().asString().equals(target) && creation.getAnonymousClassBody().isEmpty();
		}
		if (initializer instanceof CastExpr cast) {
			return cast.getType().asString().equals(target);
		}
		return false;
	}

	private static boolean sameCallable(VariableDeclarator variable, NameExpr use) {
		return callableBoundary(variable).orElse(null) == callableBoundary(use).orElse(null)
				&& variable.getBegin().orElseThrow().isBefore(use.getBegin().orElseThrow());
	}

	private static Optional<Node> callableBoundary(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof CallableDeclaration<?> || value instanceof InitializerDeclaration) {
				return Optional.of(value);
			}
			parent = value.getParentNode();
		}
		return Optional.empty();
	}

	private record Candidate(VariableDeclarator variable, List<CastExpr> casts) {
	}

}

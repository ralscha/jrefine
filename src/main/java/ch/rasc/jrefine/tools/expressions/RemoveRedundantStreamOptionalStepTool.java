package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.Node;

/** Removes identity and repeated operations from known Stream or Optional chains. */
public final class RemoveRedundantStreamOptionalStepTool implements InspectionTool {

	private static final Set<String> OPTIONAL_TYPES = Set.of("Optional", "OptionalInt", "OptionalLong",
			"OptionalDouble");

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "Queue", "Deque",
			"ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet");

	@Override
	public String id() {
		return "remove-redundant-stream-optional-step";
	}

	@Override
	public String description() {
		return "Remove redundant Stream and Optional call-chain steps";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && other.call().isAncestorOf(candidate.call())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.call(),
					"Remove redundant " + candidate.call().getNameAsString() + "() chain step"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.call().getRange().orElseThrow(),
							context.editor().text(candidate.call().getScope().orElseThrow()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (!Set.of("sorted", "distinct", "map", "filter").contains(call.getNameAsString()) || call.getScope().isEmpty()
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		if (Set.of("sorted", "distinct").contains(call.getNameAsString()) && call.getArguments().isEmpty()
				&& call.getScope().orElseThrow() instanceof MethodCallExpr inner
				&& inner.getNameAsString().equals(call.getNameAsString()) && inner.getArguments().isEmpty()) {
			return knownCandidate(context, call);
		}
		if (call.getArguments().size() != 1 || !(call.getArgument(0) instanceof LambdaExpr lambda)
				|| lambda.getParameters().size() != 1 || !lambda.getBody().isExpressionStmt()) {
			return Optional.empty();
		}
		Expression body = lambda.getBody().asExpressionStmt().getExpression();
		if ("map".equals(call.getNameAsString()) && body instanceof NameExpr name
				&& name.getNameAsString().equals(lambda.getParameter(0).getNameAsString())
				&& lambda.getParameter(0).getType().isUnknownType() && call.getTypeArguments().isEmpty()
				&& preservesMapTargetType(context, call)) {
			return knownCandidate(context, call);
		}
		if ("filter".equals(call.getNameAsString()) && body instanceof BooleanLiteralExpr literal && literal.getValue()
				&& (knownOptional(context, call.getScope().orElseThrow(), call) || requiresTraversal(call))) {
			return knownCandidate(context, call);
		}
		return Optional.empty();
	}

	private static Optional<Candidate> knownCandidate(InspectionContext context, MethodCallExpr call) {
		return knownPipeline(context, call.getScope().orElseThrow(), call) ? Optional.of(new Candidate(call))
				: Optional.empty();
	}

	private static boolean preservesMapTargetType(InspectionContext context, MethodCallExpr call) {
		Node parent = call.getParentNode().orElse(null);
		if (parent instanceof MethodCallExpr following && following.getScope().orElse(null) == call) {
			return true;
		}
		String target = parent instanceof VariableDeclarator variable ? variable.getType().asString()
				: parent instanceof ReturnStmt ? call.findAncestor(MethodDeclaration.class)
					.map(method -> method.getType().asString())
					.orElse("") : "";
		return TypeLookup.visibleDeclaredType(context.compilationUnit(), call.getScope().orElseThrow(), call)
			.filter(target::equals)
			.isPresent();
	}

	private static boolean requiresTraversal(MethodCallExpr call) {
		Node current = call;
		while (current.getParentNode().orElse(null) instanceof MethodCallExpr following
				&& following.getScope().orElse(null) == current) {
			if (Set
				.of("forEach", "forEachOrdered", "collect", "toList", "toArray", "reduce", "anyMatch", "allMatch",
						"noneMatch", "findFirst", "findAny")
				.contains(following.getNameAsString())) {
				return true;
			}
			if ("count".equals(following.getNameAsString())) {
				return false;
			}
			current = following;
		}
		return false;
	}

	private static boolean knownOptional(InspectionContext context, Expression expression, Node use) {
		String type = ExpressionToolSupport.visibleSimpleType(context, expression, use).orElse("");
		if (ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.util", OPTIONAL_TYPES)) {
			return true;
		}
		if (!(expression instanceof MethodCallExpr call) || call.getScope().isEmpty()) {
			return false;
		}
		Expression scope = call.getScope().orElseThrow();
		return Set.of("map", "filter", "flatMap", "or").contains(call.getNameAsString())
				&& knownOptional(context, scope, use)
				|| Set.of("of", "ofNullable", "empty").contains(call.getNameAsString())
						&& TypeLookup.visibleDeclaredType(context.compilationUnit(), scope, use).isEmpty()
						&& ExpressionToolSupport.knownType(context.compilationUnit(), scope.toString(), "java.util",
								OPTIONAL_TYPES);
	}

	private static boolean knownPipeline(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof MethodCallExpr call) {
			if (Set.of("stream", "parallelStream").contains(call.getNameAsString()) && call.getScope().isPresent()) {
				String type = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), use)
					.orElse("");
				if (ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.util", COLLECTION_TYPES)) {
					return true;
				}
			}
			if (call.getScope().isPresent()
					&& Set
						.of("map", "filter", "flatMap", "sorted", "distinct", "peek", "limit", "skip", "sequential",
								"parallel", "unordered", "onClose", "takeWhile", "dropWhile", "stream")
						.contains(call.getNameAsString())
					&& knownPipeline(context, call.getScope().orElseThrow(), use)) {
				return true;
			}
			if (call.getScope().isPresent()
					&& Set.of("of", "ofNullable", "empty", "iterate", "generate", "concat")
						.contains(call.getNameAsString())
					&& TypeLookup.visibleDeclaredType(context.compilationUnit(), call.getScope().orElseThrow(), use)
						.isEmpty()
					&& (ExpressionToolSupport.knownType(context.compilationUnit(),
							call.getScope().orElseThrow().toString(), "java.util.stream", Set.of("Stream"))
							|| ExpressionToolSupport.knownType(context.compilationUnit(),
									call.getScope().orElseThrow().toString(), "java.util", OPTIONAL_TYPES))) {
				return true;
			}
		}
		String type = ExpressionToolSupport.visibleSimpleType(context, expression, use).orElse("");
		return ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.util.stream", Set.of("Stream"))
				|| ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.util", OPTIONAL_TYPES);
	}

	private record Candidate(MethodCallExpr call) {
	}

}

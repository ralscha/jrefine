package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ch.rasc.jrefine.analysis.AstSupport;
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
		if (call.getScope().isEmpty() || !knownPipeline(context, call.getScope().orElseThrow(), call)
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		if (Set.of("sorted", "distinct").contains(call.getNameAsString()) && call.getArguments().isEmpty()
				&& call.getScope().orElseThrow() instanceof MethodCallExpr inner
				&& inner.getNameAsString().equals(call.getNameAsString()) && inner.getArguments().isEmpty()) {
			return Optional.of(new Candidate(call));
		}
		if (call.getArguments().size() != 1 || !(call.getArgument(0) instanceof LambdaExpr lambda)
				|| lambda.getParameters().size() != 1 || !lambda.getBody().isExpressionStmt()) {
			return Optional.empty();
		}
		Expression body = lambda.getBody().asExpressionStmt().getExpression();
		if ("map".equals(call.getNameAsString()) && body instanceof NameExpr name
				&& name.getNameAsString().equals(lambda.getParameter(0).getNameAsString())) {
			return Optional.of(new Candidate(call));
		}
		if ("filter".equals(call.getNameAsString()) && body instanceof BooleanLiteralExpr literal
				&& literal.getValue()) {
			return Optional.of(new Candidate(call));
		}
		return Optional.empty();
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
			if (call.getScope().isPresent() && (knownPipeline(context, call.getScope().orElseThrow(), use)
					|| ExpressionToolSupport.knownType(context.compilationUnit(),
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

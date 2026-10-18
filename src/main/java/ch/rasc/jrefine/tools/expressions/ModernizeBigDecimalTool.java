package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Reports BigDecimal choices whose numerical intent needs review. */
public final class ModernizeBigDecimalTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "modernize-bigdecimal";
	}

	@Override
	public String description() {
		return "Review BigDecimal equality, rounding, and exact double construction";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> methodCandidate(context, call))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.map(creation -> constructorCandidate(context, creation))
			.flatMap(Optional::stream)
			.forEach(candidates::add);

		return new ToolResult(
				candidates.stream().map(candidate -> Finding.at(candidate.node(), candidate.message())).toList(),
				false);
	}

	private static Optional<Candidate> methodCandidate(InspectionContext context, MethodCallExpr call) {
		if (!Set.of("equals", "divide", "setScale").contains(call.getNameAsString()) || call.getArguments().size() != 1
				|| call.getScope().isEmpty() || AstSupport.hasComment(context, call)
				|| !ExpressionToolSupport.knownBigDecimalExpression(context, call.getScope().orElseThrow(), call)) {
			return Optional.empty();
		}
		if ("equals".equals(call.getNameAsString()) && call.getArguments().size() == 1
				&& ExpressionToolSupport.knownBigDecimalExpression(context, call.getArgument(0), call)) {
			return Optional.of(new Candidate(call,
					"BigDecimal.equals() compares both value and scale; verify the intended equality"));
		}
		boolean lacksRounding = "divide".equals(call.getNameAsString()) && call.getArguments().size() == 1
				|| "setScale".equals(call.getNameAsString()) && call.getArguments().size() == 1;
		return lacksRounding ? Optional.of(new Candidate(call, "BigDecimal operation has no explicit rounding mode"))
				: Optional.empty();
	}

	private static Optional<Candidate> constructorCandidate(InspectionContext context, ObjectCreationExpr creation) {
		if (creation.getArguments().isEmpty() || creation.getArguments().size() > 2
				|| creation.getAnonymousClassBody().isPresent() || AstSupport.hasComment(context, creation)
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), creation.getType().asString(),
						"java.math", Set.of("BigDecimal"))) {
			return Optional.empty();
		}
		Expression argument = creation.getArgument(0);
		if (NumericSupport.typeOf(context, argument, creation).filter("double"::equals).isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(creation,
				"BigDecimal construction uses the exact binary double value; verify the intended decimal value"));
	}

	private record Candidate(Node node, String message) {
	}

}

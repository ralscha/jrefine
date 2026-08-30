package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Corrects scale-sensitive and unpredictable BigDecimal usage. */
public final class ModernizeBigDecimalTool implements InspectionTool {

	@Override
	public String id() {
		return "modernize-bigdecimal";
	}

	@Override
	public String description() {
		return "Correct BigDecimal equality, rounding, and double construction issues";
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

		List<Candidate> nonOverlapping = candidates.stream()
			.filter(candidate -> candidates.stream()
				.noneMatch(other -> other != candidate && other.replacement() != null
						&& other.node().isAncestorOf(candidate.node())))
			.toList();
		boolean hasFix = applyFixes && nonOverlapping.stream().anyMatch(candidate -> candidate.replacement() != null);
		List<Candidate> selected = hasFix
				? nonOverlapping.stream().filter(candidate -> candidate.replacement() != null).toList()
				: nonOverlapping;
		ArrayList<Finding> findings = new ArrayList<>();
		int edits = 0;
		for (Candidate candidate : selected) {
			findings.add(Finding.at(candidate.node(), candidate.message()));
			if (applyFixes && candidate.replacement() != null) {
				context.editor().replace(candidate.node().getRange().orElseThrow(), candidate.replacement());
				edits++;
			}
		}
		return new ToolResult(findings, applyFixes && edits > 0);
	}

	private static Optional<Candidate> methodCandidate(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty() || AstSupport.hasComment(context, call)
				|| !ExpressionToolSupport.knownBigDecimalExpression(context, call.getScope().orElseThrow(), call)) {
			return Optional.empty();
		}
		if ("equals".equals(call.getNameAsString()) && call.getArguments().size() == 1
				&& ExpressionToolSupport.knownBigDecimalExpression(context, call.getArgument(0), call)) {
			String scope = context.editor().text(call.getScope().orElseThrow());
			String argument = context.editor().text(call.getArgument(0));
			return Optional
				.of(new Candidate(call, "BigDecimal.equals() is scale-sensitive; compare numeric values instead",
						"(" + scope + ".compareTo(" + argument + ") == 0)"));
		}
		boolean lacksRounding = "divide".equals(call.getNameAsString()) && call.getArguments().size() == 1
				|| "setScale".equals(call.getNameAsString()) && call.getArguments().size() == 1;
		return lacksRounding
				? Optional.of(new Candidate(call, "BigDecimal operation has no explicit rounding mode", null))
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
		String replacement = null;
		if (creation.getArguments().size() == 1) {
			replacement = context.editor().text(creation.getType()) + ".valueOf(" + context.editor().text(argument)
					+ ")";
		}
		return Optional.of(new Candidate(creation,
				"BigDecimal construction from double has an unpredictable decimal value", replacement));
	}

	private record Candidate(Node node, String message, String replacement) {
	}

}

package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Set;

/** Expands an explicit array passed to the known Arrays.asList varargs method. */
public final class RemoveRedundantArrayCreationTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-array-creation";
	}

	@Override
	public String description() {
		return "Remove explicit arrays passed to known varargs methods";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> candidate(context, call))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : candidates) {
			ArrayCreationExpr array = (ArrayCreationExpr) call.getArgument(0);
			findings.add(Finding.at(array, "Remove redundant varargs array creation"));
			if (applyFixes) {
				String replacement = array.getInitializer()
					.orElseThrow()
					.getValues()
					.stream()
					.map(context.editor()::text)
					.reduce((left, right) -> left + ", " + right)
					.orElse("");
				context.editor().replace(array.getRange().orElseThrow(), replacement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean candidate(InspectionContext context, MethodCallExpr call) {
		if (!"asList".equals(call.getNameAsString()) || call.getArguments().size() != 1 || call.getScope().isEmpty()
				|| !(call.getArgument(0) instanceof ArrayCreationExpr array) || array.getInitializer().isEmpty()
				|| array.getLevels().size() != 1 || array.getLevels().get(0).getDimension().isPresent()
				|| AstSupport.hasComment(context, array)) {
			return false;
		}
		return ExpressionToolSupport.knownType(context.compilationUnit(), call.getScope().orElseThrow().toString(),
				"java.util", Set.of("Arrays"));
	}

}

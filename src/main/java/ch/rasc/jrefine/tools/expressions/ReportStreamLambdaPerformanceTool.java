package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reports eager arguments and stream stages whose work can be avoided. */
public final class ReportStreamLambdaPerformanceTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-stream-lambda-performance";
	}

	@Override
	public String description() {
		return "Report avoidable Stream count stages and eager arguments with lambda alternatives";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (hasLazyEquivalent(context, call) && !call.getArguments().isEmpty()
					&& call.getArgument(call.getArguments().size() - 1).findAll(MethodCallExpr.class).size() > 0) {
				findings.add(Finding.at(call, "Eager argument can use an equivalent lambda-accepting API"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean hasLazyEquivalent(InspectionContext context, MethodCallExpr call) {
		if ("orElse".equals(call.getNameAsString()) && call.getScope().isPresent()) {
			return TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
				.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, Set.of("Optional")))
				.isPresent();
		}
		return "requireNonNullElse".equals(call.getNameAsString()) && call.getScope()
			.filter(scope -> ExpressionToolSupport.knownType(context.compilationUnit(), scope.toString(), "java.util",
					Set.of("Objects")))
			.isPresent();
	}

}

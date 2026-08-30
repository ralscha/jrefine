package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Uses the Java 8 List.sort() default method instead of Collections.sort(). */
public final class UseListSortTool implements InspectionTool {

	@Override
	public String id() {
		return "use-list-sort";
	}

	@Override
	public String description() {
		return "Replace Collections.sort() with List.sort()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> "sort".equals(call.getNameAsString()) && call.getScope().isPresent())
			.filter(call -> call.getArguments().size() == 1 || call.getArguments().size() == 2)
			.filter(call -> isJavaUtilCollections(context, call.getScope().orElseThrow().toString()))
			.filter(call -> !AstSupport.hasComment(context, call))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : candidates) {
			findings.add(Finding.at(call, "Replace Collections.sort() with List.sort()"));
			if (applyFixes) {
				String comparator = call.getArguments().size() == 2 ? context.editor().text(call.getArgument(1))
						: "null";
				context.editor()
					.replace(call.getRange().orElseThrow(),
							context.editor().text(call.getArgument(0)) + ".sort(" + comparator + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean isJavaUtilCollections(InspectionContext context, String scope) {
		if ("java.util.Collections".equals(scope)) {
			return true;
		}
		if (!"Collections".equals(scope)) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && ("java.util.Collections".equals(imported.getNameAsString())
					|| imported.isAsterisk() && "java.util".equals(imported.getNameAsString())));
	}

}

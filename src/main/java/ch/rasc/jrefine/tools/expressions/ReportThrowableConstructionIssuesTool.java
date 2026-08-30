package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reports initCause calls where a known exception constructor accepts the cause directly.
 */
public final class ReportThrowableConstructionIssuesTool implements InspectionTool {

	private static final Set<String> JAVA_LANG_TYPES = Set.of("Error", "Exception", "ReflectiveOperationException",
			"RuntimeException", "Throwable");

	@Override
	public String id() {
		return "report-throwable-construction-issues";
	}

	@Override
	public String description() {
		return "Report initCause calls replaceable by a known Throwable cause constructor";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"initCause".equals(call.getNameAsString()) || call.getArguments().size() != 1
					|| !(call.getScope().orElse(null) instanceof ObjectCreationExpr creation)
					|| !knownCauseConstructor(context, creation)) {
				continue;
			}
			findings.add(Finding.at(call, "Unnecessary initCause() call; pass the cause to the exception constructor"));
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean knownCauseConstructor(InspectionContext context, ObjectCreationExpr creation) {
		String type = creation.getType().asString();
		boolean known = TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, JAVA_LANG_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.io", Set.of("IOException"))
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.security",
						Set.of("GeneralSecurityException"));
		if (!known || creation.getArguments().size() > 1) {
			return false;
		}
		return creation.getArguments().isEmpty() || stringExpression(context, creation.getArgument(0), creation);
	}

	private static boolean stringExpression(InspectionContext context, Expression expression, ObjectCreationExpr use) {
		return expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr
				|| TypeLookup.visibleType(context.compilationUnit(), expression, use)
					.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
					.isPresent();
	}

}

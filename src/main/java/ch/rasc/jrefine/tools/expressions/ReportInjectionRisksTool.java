package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.StringExpressionSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reports SQL, command, and native-library names demonstrably assembled from runtime
 * data.
 */
public final class ReportInjectionRisksTool implements InspectionTool {

	private static final Set<String> STATEMENT_TYPES = Set.of("Statement", "PreparedStatement", "CallableStatement");

	private static final Set<String> STATEMENT_METHODS = Set.of("execute", "executeQuery", "executeUpdate",
			"executeLargeUpdate", "addBatch");

	private static final Set<String> CONNECTION_METHODS = Set.of("prepareStatement", "prepareCall", "nativeSQL");

	@Override
	public String id() {
		return "report-injection-risks";
	}

	@Override
	public String description() {
		return "Report SQL, process commands, and native library names built from runtime data";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getArguments().isEmpty()
					|| !StringExpressionSupport.isDefinitelyDynamic(context, call.getArgument(0), call)) {
				continue;
			}
			if (runtimeExec(context, call)) {
				findings.add(Finding.at(call, "Runtime.exec() command is built from runtime data"));
			}
			else if (systemLoad(context, call)) {
				findings.add(Finding.at(call, "Native library name is built from runtime data"));
			}
			else if (jdbcStatement(context, call)) {
				findings.add(Finding.at(call, "SQL passed to Statement is built from runtime data"));
			}
			else if (jdbcConnection(context, call)) {
				findings.add(Finding.at(call, "SQL passed to Connection.prepare*() is built from runtime data"));
			}
		}
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			if (!TypeLookup.isKnownJavaLangType(context.compilationUnit(), creation.getType().asString(),
					Set.of("ProcessBuilder")) || creation.getArguments().isEmpty()) {
				continue;
			}
			if (creation.getArguments()
				.stream()
				.anyMatch(argument -> StringExpressionSupport.isDefinitelyDynamic(context, argument, creation))) {
				findings.add(Finding.at(creation, "ProcessBuilder command is built from runtime data"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean runtimeExec(InspectionContext context, MethodCallExpr call) {
		if (!"exec".equals(call.getNameAsString()) || call.getScope().isEmpty()) {
			return false;
		}
		Expression scope = call.getScope().orElseThrow();
		if (scope instanceof MethodCallExpr factory && "getRuntime".equals(factory.getNameAsString())
				&& factory.getScope()
					.filter(owner -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), owner.toString(),
							Set.of("Runtime")))
					.isPresent()) {
			return true;
		}
		return TypeLookup.visibleType(context.compilationUnit(), scope, call)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("Runtime")))
			.isPresent();
	}

	private static boolean systemLoad(InspectionContext context, MethodCallExpr call) {
		return Set.of("load", "loadLibrary").contains(call.getNameAsString()) && call.getScope()
			.filter(owner -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), owner.toString(),
					Set.of("System")))
			.isPresent();
	}

	private static boolean jdbcStatement(InspectionContext context, MethodCallExpr call) {
		return STATEMENT_METHODS.contains(call.getNameAsString())
				&& receiverType(context, call, "java.sql", STATEMENT_TYPES);
	}

	private static boolean jdbcConnection(InspectionContext context, MethodCallExpr call) {
		return CONNECTION_METHODS.contains(call.getNameAsString())
				&& receiverType(context, call, "java.sql", Set.of("Connection"));
	}

	private static boolean receiverType(InspectionContext context, MethodCallExpr call, String packageName,
			Set<String> types) {
		return call.getScope()
			.flatMap(scope -> TypeLookup.visibleType(context.compilationUnit(), scope, call))
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, packageName, types))
			.isPresent();
	}

}

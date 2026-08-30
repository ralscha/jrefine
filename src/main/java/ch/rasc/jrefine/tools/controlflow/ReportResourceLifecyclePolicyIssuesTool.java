package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reports explicit lifecycle patterns that merit project-level API policy review. */
public final class ReportResourceLifecyclePolicyIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-resource-lifecycle-policy-issues";
	}

	@Override
	public String description() {
		return "Report redundant try-with-resources closes and direct DriverManager connection acquisition";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		driverManager(context, findings);
		redundantCloses(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void driverManager(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> "getConnection".equals(call.getNameAsString()))
			.filter(call -> call.getScope()
				.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), "java.sql",
						Set.of("DriverManager")))
				.isPresent())
			.forEach(call -> findings.add(Finding.at(call,
					"Direct DriverManager connection acquisition bypasses a configurable DataSource")));
	}

	private static void redundantCloses(InspectionContext context, List<Finding> findings) {
		for (TryStmt statement : context.compilationUnit().findAll(TryStmt.class)) {
			Set<String> resources = resourceNames(statement);
			if (resources.isEmpty() || statement.getTryBlock().getStatements().isEmpty()) {
				continue;
			}
			Statement last = statement.getTryBlock().getStatements().getLast().orElseThrow();
			if (!(last instanceof ExpressionStmt expressionStatement)
					|| !(expressionStatement.getExpression() instanceof MethodCallExpr call)
					|| !"close".equals(call.getNameAsString()) || !call.getArguments().isEmpty()
					|| !(call.getScope().orElse(null) instanceof NameExpr receiver)
					|| !resources.contains(receiver.getNameAsString())) {
				continue;
			}
			findings.add(Finding.at(call, "Final explicit close() is redundant for this try-with-resources variable"));
		}
	}

	private static Set<String> resourceNames(TryStmt statement) {
		HashSet<String> result = new HashSet<>();
		for (Expression resource : statement.getResources()) {
			if (resource instanceof VariableDeclarationExpr declaration) {
				declaration.getVariables().stream().map(variable -> variable.getNameAsString()).forEach(result::add);
			}
			else if (resource instanceof NameExpr name) {
				result.add(name.getNameAsString());
			}
		}
		return result;
	}

}

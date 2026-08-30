package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import com.github.javaparser.ast.Node;

/** Removes explicit type arguments from known JDK methods with reliable inference. */
public final class RemoveRedundantTypeArgumentsTool implements InspectionTool {

	private static final Map<String, Set<String>> METHODS = Map.of("Arrays", Set.of("asList"), "Collections",
			Set.of("emptyList", "emptySet", "emptyMap", "singletonList", "singleton"), "List", Set.of("of", "copyOf"),
			"Set", Set.of("of", "copyOf"), "Optional", Set.of("of", "ofNullable", "empty"), "Stream",
			Set.of("of", "empty"));

	@Override
	public String id() {
		return "remove-redundant-type-arguments";
	}

	@Override
	public String description() {
		return "Remove inferable explicit method type arguments";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> all = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> candidate(context, call))
			.toList();
		List<MethodCallExpr> candidates = all.stream()
			.filter(call -> all.stream().noneMatch(other -> other != call && other.isAncestorOf(call)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : candidates) {
			findings.add(Finding.at(call, "Remove redundant method type arguments"));
			if (applyFixes) {
				String source = context.editor().text(call);
				int nameAt = source.indexOf(call.getNameAsString());
				int open = source.lastIndexOf('<', nameAt);
				int close = source.lastIndexOf('>', nameAt);
				if (open < 0 || close < open) {
					throw new IllegalStateException("Could not locate type arguments");
				}
				context.editor()
					.replace(call.getRange().orElseThrow(), source.substring(0, open) + source.substring(close + 1));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean candidate(InspectionContext context, MethodCallExpr call) {
		if (call.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isEmpty() || call.getScope().isEmpty()
				|| AstSupport.hasComment(context, call) || !hasTargetType(call)) {
			return false;
		}
		String owner = ExpressionToolSupport.simpleName(call.getScope().orElseThrow().toString());
		if (!METHODS.getOrDefault(owner, Set.of()).contains(call.getNameAsString())) {
			return false;
		}
		String packageName = "Stream".equals(owner) ? "java.util.stream" : "java.util";
		return ExpressionToolSupport.knownType(context.compilationUnit(), call.getScope().orElseThrow().toString(),
				packageName, Set.of(owner));
	}

	private static boolean hasTargetType(MethodCallExpr call) {
		Node current = call;
		while (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed
				&& enclosed.getInner() == current) {
			current = enclosed;
		}
		Node parent = current.getParentNode().orElse(null);
		if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == current) {
			return !variable.getType().isVarType();
		}
		if (parent instanceof AssignExpr assignment && assignment.getValue() == current) {
			return true;
		}
		return parent instanceof ReturnStmt statement
				&& AstSupport.ancestor(statement, MethodDeclaration.class).isPresent();
	}

}

package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports private method parameters supplied with one literal at every visible call site.
 */
public final class ReportConstantParameterTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-constant-parameter";
	}

	@Override
	public String description() {
		return "Report private method parameters that always receive the same literal";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			constantParameters(context, method, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void constantParameters(InspectionContext context, MethodDeclaration method,
			List<Finding> findings) {
		if (!method.isPrivate() || method.getParameters().isEmpty()) {
			return;
		}
		ClassOrInterfaceDeclaration owner = method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		if (owner == null || owner.findAncestor(TypeDeclaration.class).isPresent()
				|| owner.getMethodsByName(method.getNameAsString()).size() != 1
				|| owner.findAll(MethodReferenceExpr.class)
					.stream()
					.anyMatch(reference -> reference.getIdentifier().equals(method.getNameAsString()))) {
			return;
		}
		List<MethodCallExpr> calls = owner.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> call.getNameAsString().equals(method.getNameAsString()))
			.toList();
		if (calls.isEmpty()
				|| calls.stream()
					.anyMatch(call -> !directlyWithin(call, owner)
							|| call.getArguments().size() != method.getParameters().size()
							|| call.getScope().isPresent() && !call.getScope().orElseThrow().isThisExpr())) {
			return;
		}
		for (int index = 0; index < method.getParameters().size(); index++) {
			String constant = null;
			boolean same = true;
			for (MethodCallExpr call : calls) {
				if (!(call.getArgument(index) instanceof LiteralExpr literal)) {
					same = false;
					break;
				}
				String source = context.editor().text(literal);
				if (constant == null) {
					constant = source;
				}
				else if (!constant.equals(source)) {
					same = false;
					break;
				}
			}
			if (same) {
				findings.add(Finding.at(method.getParameter(index), "Parameter '"
						+ method.getParameter(index).getNameAsString() + "' always receives " + constant));
			}
		}
	}

	private static boolean directlyWithin(Node node, ClassOrInterfaceDeclaration owner) {
		Node current = node;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current == owner) {
				return true;
			}
			if (current instanceof TypeDeclaration<?>
					|| current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
				return false;
			}
		}
		return false;
	}

}

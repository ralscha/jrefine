package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ReferenceType;
import java.util.List;
import java.util.Optional;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Set;

/** Removes throws clauses from private methods whose bodies cannot throw. */
public final class RemoveRedundantThrowsTool implements InspectionTool {

	private static final Set<String> SERIALIZATION_METHODS = Set.of("readObject", "readObjectNoData", "writeObject",
			"readResolve", "writeReplace");

	@Override
	public String id() {
		return "remove-redundant-throws";
	}

	@Override
	public String description() {
		return "Remove provably unused private-method throws clauses";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodDeclaration> candidates = context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(method -> redundant(context, method))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodDeclaration method : candidates) {
			findings.add(Finding.at(method.getThrownExceptions().get(0), "Remove redundant throws clause"));
			if (applyFixes) {
				removeThrowsClause(context, method);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean redundant(InspectionContext context, MethodDeclaration method) {
		if (!method.isPrivate() || method.getBody().isEmpty() || method.getThrownExceptions().isEmpty()
				|| method.getJavadocComment().isPresent() || SERIALIZATION_METHODS.contains(method.getNameAsString())
				|| method.getThrownExceptions().stream().anyMatch(type -> AstSupport.hasComment(context, type))) {
			return false;
		}
		BlockStmt body = method.getBody().orElseThrow();
		if (body.findAll(ThrowStmt.class).stream().anyMatch(node -> directlyIn(node, method))
				|| body.findAll(MethodCallExpr.class).stream().anyMatch(node -> directlyIn(node, method))
				|| body.findAll(ObjectCreationExpr.class).stream().anyMatch(node -> directlyIn(node, method))) {
			return false;
		}
		return context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> call.getNameAsString().equals(method.getNameAsString()))
			.filter(call -> !method.isAncestorOf(call))
			.noneMatch(RemoveRedundantThrowsTool::insideTryBlock);
	}

	private static boolean directlyIn(Node node, MethodDeclaration method) {
		Optional<Node> current = node.getParentNode();
		while (current.isPresent()) {
			Node parent = current.orElseThrow();
			if (parent == method) {
				return true;
			}
			if (parent instanceof LambdaExpr || parent instanceof CallableDeclaration<?>) {
				return false;
			}
			current = parent.getParentNode();
		}
		return false;
	}

	private static boolean insideTryBlock(MethodCallExpr call) {
		Optional<Node> current = call.getParentNode();
		while (current.isPresent()) {
			Node node = current.orElseThrow();
			if (node instanceof TryStmt statement && statement.getTryBlock().isAncestorOf(call)
					&& !statement.getCatchClauses().isEmpty()) {
				return true;
			}
			if (node instanceof CallableDeclaration<?> || node instanceof LambdaExpr) {
				return false;
			}
			current = node.getParentNode();
		}
		return false;
	}

	private static void removeThrowsClause(InspectionContext context, MethodDeclaration method) {
		ReferenceType first = method.getThrownExceptions().get(0);
		ReferenceType last = method.getThrownExceptions().get(method.getThrownExceptions().size() - 1);
		JavaToken throwsToken = AstSupport.previousSignificant(first.getTokenRange().orElseThrow().getBegin());
		if (!"throws".equals(throwsToken.getText())) {
			throw new IllegalStateException("Expected throws keyword before declared exception");
		}
		context.editor()
			.removeWithLeadingWhitespace(
					new Range(throwsToken.getRange().orElseThrow().begin, last.getRange().orElseThrow().end));
	}

}

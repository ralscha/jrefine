package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reports exact copies of static methods and repeated side-effect-free expressions. */
public final class ReportDuplicateCodeTool implements PolicyInspectionTool {

	private static final Set<BinaryExpr.Operator> REUSABLE_OPERATORS = Set.of(BinaryExpr.Operator.PLUS,
			BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY, BinaryExpr.Operator.DIVIDE,
			BinaryExpr.Operator.REMAINDER, BinaryExpr.Operator.BINARY_AND, BinaryExpr.Operator.BINARY_OR,
			BinaryExpr.Operator.XOR, BinaryExpr.Operator.LEFT_SHIFT, BinaryExpr.Operator.SIGNED_RIGHT_SHIFT,
			BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT);

	@Override
	public String id() {
		return "report-duplicate-code";
	}

	@Override
	public String description() {
		return "Report copies of static method bodies and repeated reusable expressions";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		copiedStaticMethods(context, findings);
		repeatedExpressions(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void copiedStaticMethods(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration owner : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			List<MethodDeclaration> methods = owner.getMethods();
			for (int index = 0; index < methods.size(); index++) {
				MethodDeclaration method = methods.get(index);
				if (method.getBody().isEmpty() || method.getBody().orElseThrow().isEmpty()) {
					continue;
				}
				for (int referenceIndex = 0; referenceIndex < methods.size(); referenceIndex++) {
					MethodDeclaration reference = methods.get(referenceIndex);
					if (reference == method || !reference.isStatic() || method.isStatic() && referenceIndex > index
							|| reference.getBody().isEmpty() || !sameSignatureShape(reference, method)
							|| !reference.getBody().orElseThrow().equals(method.getBody().orElseThrow())) {
						continue;
					}
					findings.add(Finding.at(method,
							"Method body duplicates existing static method '" + reference.getNameAsString() + "'"));
					break;
				}
			}
		}
	}

	private static boolean sameSignatureShape(MethodDeclaration left, MethodDeclaration right) {
		if (!left.getType().equals(right.getType()) || left.getParameters().size() != right.getParameters().size()) {
			return false;
		}
		for (int index = 0; index < left.getParameters().size(); index++) {
			if (!left.getParameter(index).getType().equals(right.getParameter(index).getType())) {
				return false;
			}
		}
		return true;
	}

	private static void repeatedExpressions(InspectionContext context, List<Finding> findings) {
		for (Statement statement : context.compilationUnit().findAll(Statement.class)) {
			if (expressionBoundary(statement).isEmpty()) {
				continue;
			}
			Map<String, BinaryExpr> first = new HashMap<>();
			Map<String, Integer> counts = new HashMap<>();
			for (BinaryExpr expression : statement.findAll(BinaryExpr.class)) {
				if (!REUSABLE_OPERATORS.contains(expression.getOperator())
						|| nearestStatement(expression).orElse(null) != statement || !sideEffectFree(expression)) {
					continue;
				}
				String key = expression.toString();
				BinaryExpr previous = first.putIfAbsent(key, expression);
				int count = counts.merge(key, 1, Integer::sum);
				if (count >= 3 && previous != null && !previous.isAncestorOf(expression)
						&& !expression.isAncestorOf(previous)) {
					findings.add(Finding.at(expression, "Multiple occurrences of the same reusable expression"));
				}
			}
		}
	}

	private static Optional<Statement> nearestStatement(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof Statement statement) {
				return Optional.of(statement);
			}
			parent = value.getParentNode();
		}
		return Optional.empty();
	}

	private static Optional<Node> expressionBoundary(Node node) {
		Optional<Node> parent = Optional.of(node);
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof CallableDeclaration<?> || value instanceof ConstructorDeclaration
					|| value instanceof InitializerDeclaration) {
				return Optional.of(value);
			}
			parent = value.getParentNode();
		}
		return Optional.empty();
	}

	private static boolean sideEffectFree(Expression expression) {
		if (!expression.findAll(MethodCallExpr.class).isEmpty()
				|| !expression.findAll(ObjectCreationExpr.class).isEmpty()
				|| !expression.findAll(AssignExpr.class).isEmpty()) {
			return false;
		}
		return expression.findAll(UnaryExpr.class)
			.stream()
			.noneMatch(unary -> unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
					|| unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
					|| unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT
					|| unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT);
	}

}

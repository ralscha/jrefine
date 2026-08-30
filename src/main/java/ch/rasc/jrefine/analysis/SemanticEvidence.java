package ch.rasc.jrefine.analysis;

import ch.rasc.jrefine.api.InspectionContext;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.Optional;
import java.util.Set;

/**
 * Conservative, reusable semantic evidence for transformations that would otherwise rely
 * only on matching syntax. A negative result means "not proven", never that the opposite
 * is true.
 */
public final class SemanticEvidence {

	private static final Set<UnaryExpr.Operator> MUTATIONS = Set.of(UnaryExpr.Operator.PREFIX_INCREMENT,
			UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
			UnaryExpr.Operator.POSTFIX_DECREMENT);

	private SemanticEvidence() {
	}

	/**
	 * Proves that a name resolves to a captured local/parameter and is never reassigned.
	 */
	public static boolean isEffectivelyFinalLocalOrParameter(InspectionContext context, String name, Node use) {
		return TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name, use)
				&& !isReassigned(context.compilationUnit(), name);
	}

	/** Reports explicit assignment and increment/decrement writes to a simple name. */
	public static boolean isReassigned(Node root, String name) {
		return root.findAll(AssignExpr.class)
			.stream()
			.anyMatch(assignment -> assignment.getTarget() instanceof NameExpr target
					&& target.getNameAsString().equals(name))
				|| root.findAll(UnaryExpr.class)
					.stream()
					.anyMatch(unary -> MUTATIONS.contains(unary.getOperator())
							&& unary.getExpression() instanceof NameExpr target
							&& target.getNameAsString().equals(name));
	}

	/** Proves a method-reference receiver is evaluated safely at lambda creation time. */
	public static boolean isStableMethodReferenceReceiver(InspectionContext context, Expression expression, Node use) {
		if (expression.isThisExpr() || expression.isSuperExpr()) {
			return true;
		}
		if (!(expression instanceof NameExpr name)) {
			return false;
		}
		String value = name.getNameAsString();
		return !value.isEmpty() && Character.isUpperCase(value.charAt(0))
				|| isEffectivelyFinalLocalOrParameter(context, value, use);
	}

	/**
	 * Proves that removing explicit lambda parameter types retains a direct target type.
	 */
	public static boolean hasDirectLambdaTargetType(LambdaExpr lambda) {
		Optional<Node> parent = lambda.getParentNode();
		while (parent.isPresent()) {
			Node node = parent.orElseThrow();
			if (node instanceof CastExpr || node instanceof VariableDeclarator || node instanceof AssignExpr
					|| node instanceof ReturnStmt) {
				return true;
			}
			if (node instanceof MethodCallExpr || node instanceof ObjectCreationExpr) {
				return false;
			}
			parent = node.getParentNode();
		}
		return false;
	}

}

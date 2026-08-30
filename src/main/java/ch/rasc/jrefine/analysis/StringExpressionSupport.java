package ch.rasc.jrefine.analysis;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.api.InspectionContext;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Conservative evidence that a string-like expression depends on runtime data. */
public final class StringExpressionSupport {

	private StringExpressionSupport() {
	}

	/**
	 * Returns true only when the expression is demonstrably derived from a parameter,
	 * method call, object construction, array access, or another demonstrably dynamic
	 * local initializer.
	 */
	public static boolean isDefinitelyDynamic(InspectionContext context, Expression expression, Node use) {
		return isDefinitelyDynamic(context, expression, use, new HashSet<>());
	}

	private static boolean isDefinitelyDynamic(InspectionContext context, Expression expression, Node use,
			Set<Node> visiting) {
		if (!visiting.add(expression)) {
			return false;
		}
		try {
			if (expression.isLiteralExpr() || expression.isClassExpr() || expression.isThisExpr()
					|| expression.isSuperExpr()) {
				return false;
			}
			if (expression instanceof EnclosedExpr enclosed) {
				return isDefinitelyDynamic(context, enclosed.getInner(), use, visiting);
			}
			if (expression instanceof CastExpr cast) {
				return isDefinitelyDynamic(context, cast.getExpression(), use, visiting);
			}
			if (expression instanceof UnaryExpr unary) {
				return isDefinitelyDynamic(context, unary.getExpression(), use, visiting);
			}
			if (expression instanceof BinaryExpr binary) {
				return isDefinitelyDynamic(context, binary.getLeft(), use, visiting)
						|| isDefinitelyDynamic(context, binary.getRight(), use, visiting);
			}
			if (expression instanceof ConditionalExpr conditional) {
				return isDefinitelyDynamic(context, conditional.getThenExpr(), use, visiting)
						|| isDefinitelyDynamic(context, conditional.getElseExpr(), use, visiting);
			}
			if (expression instanceof ArrayInitializerExpr initializer) {
				return initializer.getValues()
					.stream()
					.anyMatch(value -> isDefinitelyDynamic(context, value, use, visiting));
			}
			if (expression instanceof ArrayCreationExpr creation) {
				return creation.getInitializer()
					.map(initializer -> initializer.getValues()
						.stream()
						.anyMatch(value -> isDefinitelyDynamic(context, value, use, visiting)))
					.orElse(false);
			}
			if (expression instanceof NameExpr name) {
				Optional<VariableDeclarator> variable = visibleVariable(context, name.getNameAsString(), use);
				if (variable.isPresent()) {
					if (safeScalar(context, variable.orElseThrow().getType().asString())) {
						return false;
					}
					if (variable.orElseThrow().getType().isVarType()
							&& variable.orElseThrow().getInitializer().filter(BinaryExpr.class::isInstance).isEmpty()) {
						return false;
					}
					return variable.orElseThrow()
						.getInitializer()
						.map(initializer -> isDefinitelyDynamic(context, initializer, variable.orElseThrow(), visiting))
						.orElse(false);
				}
				return visibleParameter(context, name.getNameAsString(), use)
					.filter(parameter -> !safeScalar(context, parameter.getType().asString()))
					.isPresent();
			}
			if (expression.isFieldAccessExpr()) {
				return false;
			}
			return expression.isMethodCallExpr() || expression.isObjectCreationExpr() || expression.isArrayAccessExpr()
					|| expression.isAssignExpr() || expression.isLambdaExpr() || expression.isMethodReferenceExpr();
		}
		finally {
			visiting.remove(expression);
		}
	}

	private static Optional<VariableDeclarator> visibleVariable(InspectionContext context, String name, Node use) {
		return context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> variable.getNameAsString().equals(name))
			.filter(variable -> variable.findAncestor(FieldDeclaration.class).isEmpty())
			.filter(variable -> before(variable, use))
			.filter(variable -> scopeContains(variable, use))
			.max(Comparator.comparingInt(StringExpressionSupport::depth)
				.thenComparingInt(StringExpressionSupport::position));
	}

	private static Optional<Parameter> visibleParameter(InspectionContext context, String name, Node use) {
		return context.compilationUnit()
			.findAll(Parameter.class)
			.stream()
			.filter(parameter -> parameter.getNameAsString().equals(name))
			.filter(parameter -> parameter.getParentNode().filter(owner -> owner.isAncestorOf(use)).isPresent())
			.max(Comparator.comparingInt(StringExpressionSupport::depth));
	}

	private static boolean safeScalar(InspectionContext context, String type) {
		String simple = TypeLookup.simpleName(type);
		if (Set.of("boolean", "byte", "short", "int", "long", "float", "double").contains(simple)) {
			return true;
		}
		return TypeLookup.isKnownJavaLangType(context.compilationUnit(), type,
				Set.of("Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double"))
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.math",
						Set.of("BigInteger", "BigDecimal"));
	}

	private static boolean scopeContains(VariableDeclarator variable, Node use) {
		Optional<ForEachStmt> forEach = variable.findAncestor(ForEachStmt.class)
			.filter(loop -> loop.getVariable().isAncestorOf(variable));
		if (forEach.isPresent()) {
			return forEach.orElseThrow().getBody().isAncestorOf(use);
		}
		Optional<ForStmt> forLoop = variable.findAncestor(ForStmt.class)
			.filter(loop -> loop.getInitialization()
				.stream()
				.anyMatch(initializer -> initializer.isAncestorOf(variable)));
		if (forLoop.isPresent()) {
			return forLoop.orElseThrow().isAncestorOf(use);
		}
		Optional<TryStmt> tryStatement = variable.findAncestor(TryStmt.class)
			.filter(statement -> statement.getResources()
				.stream()
				.anyMatch(resource -> resource.isAncestorOf(variable)));
		if (tryStatement.isPresent()) {
			return tryStatement.orElseThrow().getTryBlock().isAncestorOf(use);
		}
		return variable.findAncestor(BlockStmt.class).filter(block -> block.isAncestorOf(use)).isPresent();
	}

	private static boolean before(Node left, Node right) {
		Position first = left.getBegin().orElse(Position.HOME);
		Position second = right.getBegin().orElse(Position.HOME);
		return first.line < second.line || first.line == second.line && first.column < second.column;
	}

	private static int depth(Node node) {
		int result = 0;
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			result++;
			parent = parent.orElseThrow().getParentNode();
		}
		return result;
	}

	private static int position(Node node) {
		Position position = node.getBegin().orElse(Position.HOME);
		return position.line * 100_000 + position.column;
	}

}

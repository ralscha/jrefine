package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reports wrappers around known functional objects and direct invocation of cast lambdas.
 */
public final class ReportFunctionalExpressionRedundancyTool implements InspectionTool {

	private static final Map<String, Signature> SIGNATURES = Map.ofEntries(
			Map.entry("Runnable", new Signature("java.lang", "run", 0)),
			Map.entry("Callable", new Signature("java.util.concurrent", "call", 0)),
			Map.entry("Comparator", new Signature("java.util", "compare", 2)),
			Map.entry("Supplier", new Signature("java.util.function", "get", 0)),
			Map.entry("BooleanSupplier", new Signature("java.util.function", "getAsBoolean", 0)),
			Map.entry("IntSupplier", new Signature("java.util.function", "getAsInt", 0)),
			Map.entry("LongSupplier", new Signature("java.util.function", "getAsLong", 0)),
			Map.entry("DoubleSupplier", new Signature("java.util.function", "getAsDouble", 0)),
			Map.entry("Consumer", new Signature("java.util.function", "accept", 1)),
			Map.entry("BiConsumer", new Signature("java.util.function", "accept", 2)),
			Map.entry("Function", new Signature("java.util.function", "apply", 1)),
			Map.entry("BiFunction", new Signature("java.util.function", "apply", 2)),
			Map.entry("UnaryOperator", new Signature("java.util.function", "apply", 1)),
			Map.entry("BinaryOperator", new Signature("java.util.function", "apply", 2)),
			Map.entry("Predicate", new Signature("java.util.function", "test", 1)),
			Map.entry("BiPredicate", new Signature("java.util.function", "test", 2)));

	@Override
	public String id() {
		return "report-functional-expression-redundancy";
	}

	@Override
	public String description() {
		return "Report redundant functional wrappers and direct lambda invocation";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodReferenceExpr reference : context.compilationUnit().findAll(MethodReferenceExpr.class)) {
			NameExpr scope = receiverName(reference.getScope()).orElse(null);
			if (directlyInvoked(reference) || scope == null
					|| !sameFunctionalTarget(context, reference, scope, reference.getIdentifier(), 0)) {
				continue;
			}
			findings.add(Finding.at(reference, "Method reference wraps the same functional object and can be folded"));
		}
		for (LambdaExpr lambda : context.compilationUnit().findAll(LambdaExpr.class)) {
			if (directlyInvoked(lambda) || lambda.getExpressionBody().isEmpty()
					|| !(unwrap(lambda.getExpressionBody().orElseThrow()) instanceof MethodCallExpr call)
					|| !(call.getScope()
						.map(ReportFunctionalExpressionRedundancyTool::unwrap)
						.orElse(null) instanceof NameExpr scope)
					|| !forwarded(lambda, call) || !sameFunctionalTarget(context, lambda, scope, call.getNameAsString(),
							call.getArguments().size())) {
				continue;
			}
			findings.add(Finding.at(lambda, "Lambda wraps the same functional object and can be folded"));
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			CastExpr cast = directFunctionalCast(call).orElse(null);
			if (cast == null || !(unwrap(cast.getExpression()) instanceof LambdaExpr lambda)) {
				continue;
			}
			Signature signature = signature(context, cast.getType().asString()).orElse(null);
			if (signature != null && signature.method().equals(call.getNameAsString())
					&& signature.arity() == call.getArguments().size()) {
				findings.add(Finding.at(call, "Functional expression is defined only to invoke it directly"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean sameFunctionalTarget(InspectionContext context, Expression wrapper, NameExpr scope,
			String method, int arityHint) {
		String scopeType = TypeLookup.visibleType(context.compilationUnit(), scope, wrapper).orElse(null);
		String targetType = targetType(context, wrapper).orElse(null);
		Signature scopeSignature = signature(context, scopeType).orElse(null);
		Signature targetSignature = signature(context, targetType).orElse(null);
		if (scopeSignature == null || targetSignature == null
				|| !TypeLookup.simpleName(scopeType).equals(TypeLookup.simpleName(targetType))
				|| !scopeSignature.method().equals(method)) {
			return false;
		}
		return arityHint == 0 || scopeSignature.arity() == arityHint;
	}

	private static boolean forwarded(LambdaExpr lambda, MethodCallExpr call) {
		if (lambda.getParameters().size() != call.getArguments().size()) {
			return false;
		}
		for (int index = 0; index < lambda.getParameters().size(); index++) {
			if (!(call.getArgument(index) instanceof NameExpr name)
					|| !name.getNameAsString().equals(lambda.getParameter(index).getNameAsString())) {
				return false;
			}
		}
		return true;
	}

	private static Optional<String> targetType(InspectionContext context, Expression expression) {
		Node current = expression;
		Optional<Node> parent = current.getParentNode();
		while (parent.isPresent() && parent.orElseThrow() instanceof EnclosedExpr) {
			current = parent.orElseThrow();
			parent = current.getParentNode();
		}
		if (parent.orElse(null) instanceof CastExpr cast
				&& (cast.getExpression() == current || cast.getExpression().isAncestorOf(expression))) {
			return Optional.of(cast.getType().asString());
		}
		if (parent.orElse(null) instanceof VariableDeclarator variable && variable.getInitializer()
			.filter(initializer -> initializer.isAncestorOf(expression) || initializer == expression)
			.isPresent()) {
			return Optional.of(variable.getType().asString());
		}
		if (parent.orElse(null) instanceof AssignExpr assignment
				&& (assignment.getValue() == current || assignment.getValue().isAncestorOf(expression))
				&& assignment.getTarget() instanceof NameExpr target) {
			return TypeLookup.visibleType(context.compilationUnit(), target, assignment);
		}
		if (parent.orElse(null) instanceof ReturnStmt returned && returned.getExpression()
			.filter(value -> value.isAncestorOf(expression) || value == expression)
			.isPresent()) {
			return AstSupport.ancestor(returned, MethodDeclaration.class).map(method -> method.getType().asString());
		}
		return Optional.empty();
	}

	private static Optional<CastExpr> directFunctionalCast(MethodCallExpr call) {
		if (call.getScope().isEmpty()) {
			return Optional.empty();
		}
		Expression scope = unwrap(call.getScope().orElseThrow());
		return scope instanceof CastExpr cast ? Optional.of(cast) : Optional.empty();
	}

	private static Optional<NameExpr> receiverName(Expression scope) {
		if (scope instanceof NameExpr name) {
			return Optional.of(name);
		}
		String source = scope.toString();
		if (!source.isEmpty() && Character.isJavaIdentifierStart(source.charAt(0))
				&& source.chars().skip(1).allMatch(Character::isJavaIdentifierPart)) {
			return Optional.of(new NameExpr(source));
		}
		return Optional.empty();
	}

	private static boolean directlyInvoked(Expression expression) {
		Node current = expression;
		Optional<Node> parent = current.getParentNode();
		while (parent.isPresent()
				&& (parent.orElseThrow() instanceof EnclosedExpr || parent.orElseThrow() instanceof CastExpr)) {
			current = parent.orElseThrow();
			parent = current.getParentNode();
		}
		Node wrapper = current;
		return parent.orElse(null) instanceof MethodCallExpr call
				&& call.getScope().filter(scope -> scope == wrapper || scope.isAncestorOf(expression)).isPresent();
	}

	private static Optional<Signature> signature(InspectionContext context, String type) {
		if (type == null) {
			return Optional.empty();
		}
		String simple = TypeLookup.simpleName(type);
		Signature signature = SIGNATURES.get(simple);
		if (signature == null
				|| !TypeLookup.isKnownType(context.compilationUnit(), type, signature.packageName(), Set.of(simple))) {
			return Optional.empty();
		}
		return Optional.of(signature);
	}

	private static Expression unwrap(Expression expression) {
		Expression current = expression;
		while (current instanceof EnclosedExpr enclosed) {
			current = enclosed.getInner();
		}
		return current;
	}

	private record Signature(String packageName, String method, int arity) {
	}

}

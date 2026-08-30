package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reports exception designs and handling policies that commonly hide the original
 * failure.
 */
public final class ReportExceptionContractIssuesTool implements PolicyInspectionTool {

	private static final Set<String> IGNORED_NAMES = Set.of("ignored", "ignore", "expected", "unused");

	private static final Set<String> JAVA_LANG_EXCEPTIONS = Set.of("ArithmeticException",
			"ArrayIndexOutOfBoundsException", "ClassCastException", "Exception", "IllegalArgumentException",
			"IllegalStateException", "IndexOutOfBoundsException", "NullPointerException", "RuntimeException",
			"SecurityException", "Throwable", "UnsupportedOperationException", "Error", "AssertionError",
			"LinkageError");

	@Override
	public String id() {
		return "report-exception-contract-issues";
	}

	@Override
	public String description() {
		return "Report broad, stateful, nested, or causality-losing exception handling policies";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		exceptionDeclarations(context, findings);
		catches(context, findings);
		tryStatements(context, findings);
		throwsClauses(context, findings);
		noArgumentExceptions(context, findings);
		throwableSuppliers(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void exceptionDeclarations(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (type.isInterface()) {
				continue;
			}
			if (type.getExtendedTypes()
				.stream()
				.anyMatch(parent -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), parent.asString(),
						Set.of("Throwable")))) {
				findings.add(Finding.at(type, "Exception type directly extends Throwable; extend Exception or Error"));
			}
			if (exceptionClass(context, type)) {
				type.getFields()
					.stream()
					.filter(field -> !field.isFinal())
					.forEach(field -> findings.add(Finding.at(field, "Non-final field makes exception state mutable")));
			}
		}
	}

	private static void catches(InspectionContext context, List<Finding> findings) {
		for (CatchClause clause : context.compilationUnit().findAll(CatchClause.class)) {
			String parameter = clause.getParameter().getNameAsString();
			if (broadException(context, clause.getParameter().getType())) {
				findings.add(Finding.at(clause.getParameter(),
						"Overly broad catch clause intercepts Exception or Throwable"));
			}
			List<NameExpr> references = clause.getBody()
				.findAll(NameExpr.class)
				.stream()
				.filter(name -> name.getNameAsString().equals(parameter))
				.filter(name -> directlyWithinCatch(name, clause))
				.toList();
			if (references.isEmpty() && !clause.getBody().getStatements().isEmpty()
					&& !restoresInterrupt(context, clause) && !IGNORED_NAMES.contains(parameter)) {
				findings
					.add(Finding.at(clause, "Catch block may ignore the caught exception and its diagnostic context"));
			}
			for (InstanceOfExpr expression : clause.getBody().findAll(InstanceOfExpr.class)) {
				if (directlyWithinCatch(expression, clause) && expression.getExpression() instanceof NameExpr name
						&& name.getNameAsString().equals(parameter)) {
					findings.add(Finding.at(expression,
							"instanceof on a catch parameter often belongs in separate catch clauses"));
				}
			}
			for (ThrowStmt statement : clause.getBody().findAll(ThrowStmt.class)) {
				if (!directlyWithinCatch(statement, clause)
						|| !(statement.getExpression() instanceof ObjectCreationExpr creation)
						|| creation.findAll(NameExpr.class)
							.stream()
							.anyMatch(name -> name.getNameAsString().equals(parameter))) {
					continue;
				}
				findings.add(Finding.at(statement,
						"Exception thrown from catch does not retain the caught exception as its cause"));
			}
		}
	}

	private static void tryStatements(InspectionContext context, List<Finding> findings) {
		for (TryStmt statement : context.compilationUnit().findAll(TryStmt.class)) {
			if (hasContainingTry(statement)) {
				findings.add(Finding.at(statement, "Nested try statement makes exception ownership harder to follow"));
			}
			for (ThrowStmt thrown : statement.getTryBlock().findAll(ThrowStmt.class)) {
				if (!directlyWithinTry(thrown, statement)) {
					continue;
				}
				String type = thrownType(context, thrown);
				if (type != null && statement.getCatchClauses()
					.stream()
					.flatMap(clause -> catchTypes(clause).stream())
					.anyMatch(caught -> catches(caught, type))) {
					findings
						.add(Finding.at(thrown, "Exception thrown in try block is caught by that same try statement"));
				}
			}
		}
	}

	private static void throwsClauses(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			inspectThrows(context, method.getThrownExceptions(), findings);
		}
		for (ConstructorDeclaration constructor : context.compilationUnit().findAll(ConstructorDeclaration.class)) {
			inspectThrows(context, constructor.getThrownExceptions(), findings);
		}
	}

	private static void inspectThrows(InspectionContext context, List<ReferenceType> thrown, List<Finding> findings) {
		for (ReferenceType type : thrown) {
			if (TypeLookup.isKnownJavaLangType(context.compilationUnit(), type.asString(),
					Set.of("Exception", "Throwable"))) {
				findings.add(Finding.at(type, "Overly broad throws clause exposes Exception or Throwable"));
			}
			else if (TypeLookup.isKnownJavaLangType(context.compilationUnit(), type.asString(),
					Set.of("RuntimeException", "Error"))) {
				findings.add(Finding.at(type, "Unchecked exception is redundantly declared in the throws clause"));
			}
		}
	}

	private static void noArgumentExceptions(InspectionContext context, List<Finding> findings) {
		Set<String> local = context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> exceptionClass(context, type))
			.map(type -> type.getNameAsString())
			.collect(java.util.stream.Collectors.toSet());
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			String simple = TypeLookup.simpleName(creation.getType().asString());
			boolean known = TypeLookup.isKnownJavaLangType(context.compilationUnit(), creation.getType().asString(),
					JAVA_LANG_EXCEPTIONS) || local.contains(simple) || simple.endsWith("Exception")
					|| simple.endsWith("Error");
			if (known && creation.getArguments().isEmpty()) {
				findings.add(Finding.at(creation, "Exception is created without a message or cause"));
			}
		}
	}

	private static void throwableSuppliers(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"orElseThrow".equals(call.getNameAsString()) || call.getArguments().size() != 1
					|| !(call.getArgument(0) instanceof LambdaExpr lambda) || !optionalReceiver(context, call)
					|| !supplierNeverReturnsThrowable(lambda)) {
				continue;
			}
			findings
				.add(Finding.at(lambda, "Throwable supplier throws or returns null instead of returning an exception"));
		}
	}

	private static boolean supplierNeverReturnsThrowable(LambdaExpr lambda) {
		if (lambda.getExpressionBody().filter(NullLiteralExpr.class::isInstance).isPresent()) {
			return true;
		}
		if (!lambda.getBody().isBlockStmt()) {
			return false;
		}
		List<ReturnStmt> returns = lambda.getBody()
			.asBlockStmt()
			.findAll(ReturnStmt.class)
			.stream()
			.filter(statement -> directlyWithinLambda(statement, lambda))
			.toList();
		if (!returns.isEmpty()) {
			return returns.stream()
				.allMatch(statement -> statement.getExpression().filter(NullLiteralExpr.class::isInstance).isPresent());
		}
		return lambda.getBody()
			.asBlockStmt()
			.findAll(ThrowStmt.class)
			.stream()
			.anyMatch(statement -> directlyWithinLambda(statement, lambda));
	}

	private static boolean optionalReceiver(InspectionContext context, MethodCallExpr operation) {
		Expression scope = operation.getScope().orElse(null);
		if (scope instanceof MethodCallExpr factory
				&& Set.of("empty", "of", "ofNullable").contains(factory.getNameAsString())
				&& factory.getScope()
					.filter(owner -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), owner.toString(),
							Set.of("Optional")))
					.isPresent()) {
			return true;
		}
		return scope != null && visibleType(context, scope, operation)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, Set.of("Optional")))
			.isPresent();
	}

	private static Optional<String> visibleType(InspectionContext context, Expression expression, Node use) {
		Optional<String> type = TypeLookup.visibleType(context.compilationUnit(), expression, use);
		if (type.isPresent()) {
			return type;
		}
		String name = expression instanceof NameExpr simple ? simple.getNameAsString()
				: expression instanceof FieldAccessExpr access ? access.getNameAsString() : null;
		if (name == null || expression instanceof NameExpr
				&& TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name, use)) {
			return Optional.empty();
		}
		TypeDeclaration<?> owner = use.findAncestor(TypeDeclaration.class).orElse(null);
		return owner == null ? Optional.empty()
				: owner.getFields()
					.stream()
					.filter(field -> field.getVariables()
						.stream()
						.anyMatch(variable -> variable.getNameAsString().equals(name)))
					.findFirst()
					.map(field -> field.getElementType().asString());
	}

	private static boolean exceptionClass(InspectionContext context, ClassOrInterfaceDeclaration type) {
		return type.getExtendedTypes().stream().anyMatch(parent -> {
			String simple = TypeLookup.simpleName(parent.asString());
			return TypeLookup.isKnownJavaLangType(context.compilationUnit(), parent.asString(),
					Set.of("Throwable", "Exception", "RuntimeException", "Error")) || simple.endsWith("Exception")
					|| simple.endsWith("Error");
		});
	}

	private static boolean broadException(InspectionContext context, Type type) {
		return !(type instanceof UnionType) && TypeLookup.isKnownJavaLangType(context.compilationUnit(),
				type.asString(), Set.of("Exception", "Throwable"));
	}

	private static boolean restoresInterrupt(InspectionContext context, CatchClause clause) {
		if (!TypeLookup.isKnownJavaLangType(context.compilationUnit(), clause.getParameter().getType().asString(),
				Set.of("InterruptedException"))) {
			return false;
		}
		return clause.getBody()
			.findAll(MethodCallExpr.class)
			.stream()
			.anyMatch(call -> "interrupt".equals(call.getNameAsString()) && call.getArguments().isEmpty()
					&& call.getScope()
						.filter(scope -> scope instanceof MethodCallExpr current
								&& "currentThread".equals(current.getNameAsString())
								&& current.getScope()
									.filter(owner -> TypeLookup.isKnownJavaLangType(context.compilationUnit(),
											owner.toString(), Set.of("Thread")))
									.isPresent())
						.isPresent());
	}

	private static List<String> catchTypes(CatchClause clause) {
		Type type = clause.getParameter().getType();
		if (type instanceof UnionType union) {
			return union.getElements().stream().map(ReferenceType::asString).toList();
		}
		return List.of(type.asString());
	}

	private static String thrownType(InspectionContext context, ThrowStmt statement) {
		Expression expression = statement.getExpression();
		if (expression instanceof ObjectCreationExpr creation) {
			return creation.getType().asString();
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, statement).orElse(null);
	}

	private static boolean catches(String caught, String thrown) {
		String expected = TypeLookup.simpleName(caught);
		String actual = TypeLookup.simpleName(thrown);
		if (expected.equals(actual) || "Throwable".equals(expected)) {
			return true;
		}
		if ("Exception".equals(expected)) {
			return !actual.endsWith("Error") && !"Throwable".equals(actual);
		}
		return "RuntimeException".equals(expected) && (actual.endsWith("RuntimeException") || Set
			.of("ArithmeticException", "ClassCastException", "IllegalArgumentException", "IllegalStateException",
					"IndexOutOfBoundsException", "NullPointerException", "SecurityException",
					"UnsupportedOperationException")
			.contains(actual));
	}

	private static boolean hasContainingTry(TryStmt statement) {
		Node current = statement;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof TryStmt) {
				return true;
			}
			if (current instanceof LambdaExpr || current instanceof CallableDeclaration<?>
					|| current instanceof TypeDeclaration<?>) {
				return false;
			}
		}
		return false;
	}

	private static boolean directlyWithinTry(Node node, TryStmt statement) {
		Node current = node;
		while (current != statement.getTryBlock()) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof TryStmt || parent instanceof LambdaExpr
					|| parent instanceof CallableDeclaration<?> || parent instanceof TypeDeclaration<?>) {
				return false;
			}
			current = parent;
		}
		return true;
	}

	private static boolean directlyWithinCatch(Node node, CatchClause clause) {
		Node current = node;
		while (current != clause) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof CatchClause && parent != clause || parent instanceof LambdaExpr
					|| parent instanceof CallableDeclaration<?> || parent instanceof TypeDeclaration<?>) {
				return false;
			}
			current = parent;
		}
		return true;
	}

	private static boolean directlyWithinLambda(Node node, LambdaExpr lambda) {
		Node current = node;
		while (current != lambda) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof LambdaExpr && parent != lambda
					|| parent instanceof CallableDeclaration<?> || parent instanceof TypeDeclaration<?>) {
				return false;
			}
			current = parent;
		}
		return true;
	}

}

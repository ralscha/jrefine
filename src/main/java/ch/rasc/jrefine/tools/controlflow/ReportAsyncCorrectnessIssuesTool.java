package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reports source-visible lifecycle and error-observation gaps in asynchronous code. */
public final class ReportAsyncCorrectnessIssuesTool implements PolicyInspectionTool {

	private static final Set<String> FUTURE_TYPES = Set.of("Future", "CompletionStage", "CompletableFuture",
			"ScheduledFuture");

	private static final Set<String> EXECUTOR_TYPES = Set.of("ExecutorService", "ScheduledExecutorService");

	private static final Set<String> CONTINUATIONS = Set.of("thenApply", "thenApplyAsync", "thenAccept",
			"thenAcceptAsync", "thenRun", "thenRunAsync", "thenCompose", "thenComposeAsync", "thenCombine",
			"thenCombineAsync", "thenAcceptBoth", "thenAcceptBothAsync", "runAfterBoth", "runAfterBothAsync",
			"applyToEither", "applyToEitherAsync", "acceptEither", "acceptEitherAsync", "runAfterEither",
			"runAfterEitherAsync", "exceptionally", "exceptionallyAsync", "exceptionallyCompose",
			"exceptionallyComposeAsync", "handle", "handleAsync", "whenComplete", "whenCompleteAsync",
			"completeOnTimeout", "orTimeout");

	private static final Set<String> OBSERVATION_METHODS = Set.of("get", "join", "getNow", "resultNow", "exceptionNow",
			"cancel", "isDone", "isCancelled", "state", "whenComplete", "whenCompleteAsync", "handle", "handleAsync",
			"exceptionally", "exceptionallyAsync", "exceptionallyCompose", "exceptionallyComposeAsync");

	private static final Set<String> LIFECYCLE_METHODS = Set.of("shutdown", "shutdownNow", "close");

	@Override
	public String id() {
		return "report-async-correctness-issues";
	}

	@Override
	public String description() {
		return "Report ignored asynchronous results, unobserved futures, executor leaks, and ScopedValue candidates";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		ignoredAsyncResults(context, findings);
		futureVariables(context, findings);
		executorVariables(context, findings);
		if (context.targetJava().supports(25)) {
			scopedValueCandidates(context, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void ignoredAsyncResults(InspectionContext context, List<Finding> findings) {
		for (ExpressionStmt statement : context.compilationUnit().findAll(ExpressionStmt.class)) {
			if (!(statement.getExpression() instanceof MethodCallExpr call)
					|| statement.getParentNode().orElse(null) instanceof LambdaExpr
					|| !asyncReturningCall(context, call)) {
				continue;
			}
			findings
				.add(Finding.at(call, "Asynchronous result is ignored; retain or observe it so failures are not lost"));
		}
	}

	private static void futureVariables(InspectionContext context, List<Finding> findings) {
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (variable.findAncestor(FieldDeclaration.class).isPresent() || !futureVariable(context, variable)) {
				continue;
			}
			CallableDeclaration<?> owner = callable(variable);
			if (owner == null || transferredOrObserved(owner, variable.getNameAsString())) {
				continue;
			}
			findings.add(Finding.at(variable, "Future result is never observed, handled, returned, or transferred"));
		}
	}

	private static boolean futureVariable(InspectionContext context, VariableDeclarator variable) {
		if (!variable.getType().isVarType() && TypeLookup.isKnownType(context.compilationUnit(),
				variable.getType().asString(), "java.util.concurrent", FUTURE_TYPES)) {
			return true;
		}
		return variable.getType().isVarType() && variable.getInitializer()
			.filter(MethodCallExpr.class::isInstance)
			.map(MethodCallExpr.class::cast)
			.filter(call -> asyncReturningCall(context, call))
			.isPresent();
	}

	private static boolean transferredOrObserved(CallableDeclaration<?> owner, String name) {
		return owner.findAll(MethodReferenceExpr.class)
			.stream()
			.anyMatch(reference -> reference.getScope().toString().equals(name)
					&& OBSERVATION_METHODS.contains(reference.getIdentifier()))
				|| owner.findAll(NameExpr.class)
					.stream()
					.filter(use -> use.getNameAsString().equals(name))
					.anyMatch(ReportAsyncCorrectnessIssuesTool::observedOrTransferred);
	}

	private static boolean observedOrTransferred(NameExpr use) {
		Node parent = use.getParentNode().orElse(null);
		if (parent instanceof MethodCallExpr call) {
			if (call.getScope().filter(scope -> scope == use).isPresent()) {
				MethodCallExpr outermost = call;
				boolean observed = OBSERVATION_METHODS.contains(call.getNameAsString())
						|| CONTINUATIONS.contains(call.getNameAsString());
				while (outermost.getParentNode().orElse(null) instanceof MethodCallExpr next
						&& next.getScope().orElse(null) == outermost) {
					outermost = next;
					observed = observed || OBSERVATION_METHODS.contains(next.getNameAsString())
							|| CONTINUATIONS.contains(next.getNameAsString());
				}
				return observed || expressionTransferred(outermost);
			}
			return call.getArguments().stream().anyMatch(argument -> argument == use);
		}
		if (parent instanceof MethodReferenceExpr reference && reference.getScope().isNameExpr()
				&& reference.getScope().asNameExpr().getNameAsString().equals(use.getNameAsString())
				&& OBSERVATION_METHODS.contains(reference.getIdentifier())) {
			return true;
		}
		if (parent instanceof ObjectCreationExpr creation) {
			return creation.getArguments().stream().anyMatch(argument -> argument == use);
		}
		if (parent instanceof ReturnStmt) {
			return true;
		}
		if (parent instanceof AssignExpr assignment) {
			return assignment.getValue() == use;
		}
		if (parent instanceof VariableDeclarator variable) {
			return variable.getInitializer().filter(initializer -> initializer == use).isPresent();
		}
		return use.findAncestor(ReturnStmt.class)
			.filter(statement -> statement.getExpression()
				.filter(expression -> expression.isAncestorOf(use))
				.isPresent())
			.isPresent();
	}

	private static boolean expressionTransferred(Expression expression) {
		Node parent = expression.getParentNode().orElse(null);
		if (parent instanceof ReturnStmt) {
			return true;
		}
		if (parent instanceof AssignExpr assignment) {
			return assignment.getValue() == expression;
		}
		if (parent instanceof VariableDeclarator variable) {
			return variable.getInitializer().filter(value -> value == expression).isPresent();
		}
		if (parent instanceof MethodCallExpr call) {
			return call.getArguments().stream().anyMatch(argument -> argument == expression);
		}
		if (parent instanceof ObjectCreationExpr creation) {
			return creation.getArguments().stream().anyMatch(argument -> argument == expression);
		}
		return false;
	}

	private static void executorVariables(InspectionContext context, List<Finding> findings) {
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (variable.findAncestor(FieldDeclaration.class).isPresent() || !executorVariable(context, variable)
					|| tryResource(variable)) {
				continue;
			}
			CallableDeclaration<?> owner = callable(variable);
			if (owner == null || executorClosedOrTransferred(owner, variable.getNameAsString())) {
				continue;
			}
			findings.add(Finding.at(variable,
					"ExecutorService is created without shutdown(), close(), or ownership transfer"));
		}
	}

	private static boolean executorVariable(InspectionContext context, VariableDeclarator variable) {
		if (!variable.getType().isVarType() && TypeLookup.isKnownType(context.compilationUnit(),
				variable.getType().asString(), "java.util.concurrent", EXECUTOR_TYPES)) {
			return localExecutorCreation(context, variable);
		}
		return variable.getType().isVarType() && localExecutorCreation(context, variable);
	}

	private static boolean localExecutorCreation(InspectionContext context, VariableDeclarator variable) {
		return variable.getInitializer()
			.filter(MethodCallExpr.class::isInstance)
			.map(MethodCallExpr.class::cast)
			.filter(call -> call.getNameAsString().startsWith("new"))
			.filter(call -> staticOwner(context, call, "java.util.concurrent", "Executors"))
			.isPresent();
	}

	private static boolean tryResource(VariableDeclarator variable) {
		return variable.findAncestor(TryStmt.class)
			.filter(statement -> statement.getResources()
				.stream()
				.anyMatch(resource -> resource == variable.getParentNode().orElse(null)
						|| resource.isAncestorOf(variable)))
			.isPresent();
	}

	private static boolean executorClosedOrTransferred(CallableDeclaration<?> owner, String name) {
		for (NameExpr use : owner.findAll(NameExpr.class)) {
			if (!use.getNameAsString().equals(name)) {
				continue;
			}
			Node parent = use.getParentNode().orElse(null);
			if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == use).isPresent()
					&& LIFECYCLE_METHODS.contains(call.getNameAsString())) {
				return true;
			}
			if (observedOrTransferred(use) && !(parent instanceof MethodCallExpr call
					&& call.getScope().filter(scope -> scope == use).isPresent())) {
				return true;
			}
		}
		return false;
	}

	private static void scopedValueCandidates(InspectionContext context, List<Finding> findings) {
		for (FieldDeclaration field : context.compilationUnit().findAll(FieldDeclaration.class)) {
			if (!field.isStatic() || !field.isFinal()) {
				continue;
			}
			for (VariableDeclarator variable : field.getVariables()) {
				if (!TypeLookup.isKnownJavaLangType(context.compilationUnit(), variable.getType().asString(),
						Set.of("ThreadLocal"))) {
					continue;
				}
				List<MethodCallExpr> uses = threadLocalUses(context, variable.getNameAsString());
				if (!uses.isEmpty()
						&& uses.stream()
							.allMatch(call -> Set.of("get", "set", "remove").contains(call.getNameAsString()))
						&& stackDisciplined(uses, variable.getNameAsString())) {
					findings
						.add(Finding.at(variable, "Stack-confined ThreadLocal can use ScopedValue on target Java 25"));
				}
			}
		}
	}

	private static List<MethodCallExpr> threadLocalUses(InspectionContext context, String name) {
		List<NameExpr> references = context.compilationUnit()
			.findAll(NameExpr.class)
			.stream()
			.filter(use -> use.getNameAsString().equals(name))
			.toList();
		if (references.stream()
			.anyMatch(use -> !(use.getParentNode().orElse(null) instanceof MethodCallExpr call
					&& call.getScope().filter(scope -> scope == use).isPresent()))) {
			return List.of();
		}
		return references.stream().map(use -> (MethodCallExpr) use.getParentNode().orElseThrow()).distinct().toList();
	}

	private static boolean stackDisciplined(List<MethodCallExpr> uses, String name) {
		List<CallableDeclaration<?>> owners = uses.stream()
			.map(ReportAsyncCorrectnessIssuesTool::callable)
			.filter(java.util.Objects::nonNull)
			.distinct()
			.toList();
		return !owners.isEmpty() && owners.stream().allMatch(owner -> {
			List<MethodCallExpr> sets = uses.stream()
				.filter(call -> callable(call) == owner)
				.filter(call -> "set".equals(call.getNameAsString()))
				.toList();
			return !sets.isEmpty() && sets.stream()
				.allMatch(set -> owner.findAll(TryStmt.class)
					.stream()
					.filter(statement -> before(set, statement) || statement.getTryBlock().isAncestorOf(set))
					.anyMatch(statement -> statement.getFinallyBlock()
						.stream()
						.flatMap(block -> block.findAll(MethodCallExpr.class).stream())
						.anyMatch(call -> "remove".equals(call.getNameAsString())
								&& call.getScope().map(Object::toString).filter(name::equals).isPresent())));
		});
	}

	private static boolean asyncReturningCall(InspectionContext context, MethodCallExpr call) {
		if ("submit".equals(call.getNameAsString()) && call.getScope().isPresent()
				&& TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
					.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent",
							EXECUTOR_TYPES))
					.isPresent()) {
			return true;
		}
		if (Set.of("runAsync", "supplyAsync").contains(call.getNameAsString())
				&& staticOwner(context, call, "java.util.concurrent", "CompletableFuture")) {
			return true;
		}
		if (!CONTINUATIONS.contains(call.getNameAsString()) || call.getScope().isEmpty()) {
			return false;
		}
		Expression receiver = call.getScope().orElseThrow();
		if (TypeLookup.visibleType(context.compilationUnit(), receiver, call)
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent",
					Set.of("CompletionStage", "CompletableFuture")))
			.isPresent()) {
			return true;
		}
		return receiver instanceof MethodCallExpr previous && asyncReturningCall(context, previous);
	}

	private static boolean staticOwner(InspectionContext context, MethodCallExpr call, String packageName,
			String type) {
		return call.getScope()
			.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), packageName,
					Set.of(type)))
			.isPresent();
	}

	private static CallableDeclaration<?> callable(Node node) {
		return node.findAncestor(CallableDeclaration.class).orElse(null);
	}

	private static boolean before(Node first, Node second) {
		Position left = first.getBegin().orElse(Position.HOME);
		Position right = second.getBegin().orElse(Position.HOME);
		return left.line < right.line || left.line == right.line && left.column < right.column;
	}

}

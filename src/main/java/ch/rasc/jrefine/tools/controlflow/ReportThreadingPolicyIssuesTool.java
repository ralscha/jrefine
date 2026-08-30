package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.AstSupport;
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
 * Reports source-local thread coordination and monitor policies worth explicit review.
 */
public final class ReportThreadingPolicyIssuesTool implements PolicyInspectionTool {

	private static final Set<String> ATOMIC_TYPES = Set.of("AtomicBoolean", "AtomicInteger", "AtomicIntegerArray",
			"AtomicLong", "AtomicLongArray", "AtomicMarkableReference", "AtomicReference", "AtomicReferenceArray",
			"AtomicStampedReference", "DoubleAccumulator", "DoubleAdder", "LongAccumulator", "LongAdder");

	@Override
	public String id() {
		return "report-threading-policy-issues";
	}

	@Override
	public String description() {
		return "Report risky thread lifecycle, monitor, wait/signal, and busy-wait policies";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		declarations(context, findings);
		methodCalls(context, findings);
		synchronizedStatements(context, findings);
		busyWaits(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void declarations(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (!type.isInterface() && type.getExtendedTypes()
				.stream()
				.anyMatch(parent -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), parent.asString(),
						Set.of("Thread")))) {
				findings.add(Finding.at(type, "Class directly extends Thread; prefer a task submitted to an executor"));
			}
			for (MethodDeclaration method : type.getMethods()) {
				if (method.isSynchronized()) {
					findings.add(Finding.at(method, "Synchronized method exposes its whole body as a monitor policy"));
				}
			}
			for (FieldDeclaration field : type.getFields()) {
				if (TypeLookup.isKnownJavaLangType(context.compilationUnit(), field.getElementType().asString(),
						Set.of("ThreadLocal", "InheritableThreadLocal")) && (!field.isStatic() || !field.isFinal())) {
					findings.add(Finding.at(field, "ThreadLocal field should normally be declared static final"));
				}
			}
		}
	}

	private static void methodCalls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			String method = call.getNameAsString();
			if ("wait".equals(method) && call.getArguments().isEmpty()) {
				findings.add(Finding.at(call, "wait() has no timeout and can block indefinitely"));
			}
			if (Set.of("await", "awaitUninterruptibly").contains(method) && call.getArguments().isEmpty()
					&& conditionReceiver(context, call)) {
				findings.add(Finding.at(call, "Condition await has no timeout and can block indefinitely"));
			}
			if ("notify".equals(method) && call.getArguments().isEmpty()) {
				findings.add(
						Finding.at(call, "notify() wakes an arbitrary waiter; review whether notifyAll() is required"));
			}
			if ("signal".equals(method) && call.getArguments().isEmpty() && conditionReceiver(context, call)) {
				findings.add(Finding.at(call,
						"Condition.signal() wakes one waiter; review whether signalAll() is required"));
			}
			if ("yield".equals(method) && staticOwner(context, call, "java.lang", "Thread")) {
				findings.add(Finding.at(call, "Thread.yield() provides no portable scheduling guarantee"));
			}
			if ("setPriority".equals(method) && threadReceiver(context, call)) {
				findings.add(Finding.at(call, "Thread.setPriority() has platform-dependent scheduling behavior"));
			}
			if (insideLockedContext(call) && sourceLocalNativeMethod(call)) {
				findings.add(Finding.at(call, "Native method is called while a Java monitor is held"));
			}
		}
	}

	private static void synchronizedStatements(InspectionContext context, List<Finding> findings) {
		for (SynchronizedStmt statement : context.compilationUnit().findAll(SynchronizedStmt.class)) {
			Expression monitor = statement.getExpression();
			if (hasSynchronizedAncestor(statement)) {
				findings.add(Finding.at(statement, "Nested synchronized statement increases lock-ordering risk"));
			}
			if (monitor instanceof ThisExpr) {
				findings.add(Finding.at(monitor, "Synchronization on this exposes the monitor to external code"));
			}
			else if (getClassMonitor(monitor)) {
				findings.add(
						Finding.at(monitor, "Synchronization on getClass() can use different monitors for subclasses"));
			}
			else if (monitor instanceof NameExpr name && TypeLookup.isVisibleLocalOrParameterIncludingCaptured(
					context.compilationUnit(), name.getNameAsString(), statement)) {
				findings
					.add(Finding.at(monitor, "Synchronization on a local variable or parameter has fragile ownership"));
			}
			FieldDeclaration monitorField = visibleField(context, monitor, statement).orElse(null);
			if (monitorField != null && monitorField.isStatic()) {
				findings.add(Finding.at(monitor, "Synchronization uses a static field as its exposed monitor"));
			}
			if (instanceMonitor(context, monitor, statement, monitorField) && accessesStaticField(context, statement)) {
				findings.add(Finding.at(statement, "Static field is accessed while locking only instance data"));
			}
		}
	}

	private static void busyWaits(InspectionContext context, List<Finding> findings) {
		for (WhileStmt loop : context.compilationUnit().findAll(WhileStmt.class)) {
			boolean empty = loop.getBody() instanceof EmptyStmt || loop.getBody() instanceof BlockStmt block
					&& block.getStatements().isEmpty() && !AstSupport.hasComment(context, block);
			if (empty && readsSourceLocalField(context, loop.getCondition(), loop)) {
				findings.add(Finding.at(loop, "Busy wait spins on a field without blocking or backoff"));
			}
		}
	}

	private static boolean getClassMonitor(Expression monitor) {
		if (!(monitor instanceof MethodCallExpr call)) {
			return false;
		}
		return "getClass".equals(call.getNameAsString()) && call.getArguments().isEmpty() && (call.getScope().isEmpty()
				|| call.getScope().filter(scope -> scope instanceof ThisExpr).isPresent());
	}

	private static boolean instanceMonitor(InspectionContext context, Expression monitor, Node use,
			FieldDeclaration field) {
		if (monitor instanceof ThisExpr) {
			return true;
		}
		if (monitor instanceof NameExpr name && TypeLookup
			.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name.getNameAsString(), use)) {
			return true;
		}
		return field != null && !field.isStatic();
	}

	private static boolean accessesStaticField(InspectionContext context, SynchronizedStmt statement) {
		ClassOrInterfaceDeclaration owner = statement.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		if (owner == null) {
			return false;
		}
		Set<String> fields = owner.getFields()
			.stream()
			.filter(FieldDeclaration::isStatic)
			.filter(field -> !constantField(context, field))
			.filter(field -> !TypeLookup.isKnownType(context.compilationUnit(), field.getElementType().asString(),
					"java.util.concurrent.atomic", ATOMIC_TYPES))
			.flatMap(field -> field.getVariables().stream())
			.map(variable -> variable.getNameAsString())
			.collect(java.util.stream.Collectors.toSet());
		if (fields.isEmpty()) {
			return false;
		}
		return statement.getBody()
			.findAll(NameExpr.class)
			.stream()
			.filter(name -> directlyWithin(name, statement))
			.anyMatch(name -> fields.contains(name.getNameAsString()) && !TypeLookup
				.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name.getNameAsString(), name))
				|| statement.getBody()
					.findAll(FieldAccessExpr.class)
					.stream()
					.filter(access -> directlyWithin(access, statement))
					.anyMatch(access -> fields.contains(access.getNameAsString()) && sourceLocalScope(access, owner));
	}

	private static boolean constantField(InspectionContext context, FieldDeclaration field) {
		return field.isFinal() && (field.getElementType().isPrimitiveType() || TypeLookup
			.isKnownJavaLangType(context.compilationUnit(), field.getElementType().asString(), Set.of("String")));
	}

	private static boolean readsSourceLocalField(InspectionContext context, Expression expression, Node use) {
		ClassOrInterfaceDeclaration owner = use.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		if (owner == null) {
			return false;
		}
		Set<String> fields = owner.getFields()
			.stream()
			.flatMap(field -> field.getVariables().stream())
			.map(variable -> variable.getNameAsString())
			.collect(java.util.stream.Collectors.toSet());
		return expression.findAll(NameExpr.class)
			.stream()
			.anyMatch(name -> fields.contains(name.getNameAsString()) && !TypeLookup
				.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name.getNameAsString(), name))
				|| expression.findAll(FieldAccessExpr.class)
					.stream()
					.anyMatch(access -> fields.contains(access.getNameAsString()) && sourceLocalScope(access, owner));
	}

	private static boolean conditionReceiver(InspectionContext context, MethodCallExpr call) {
		return call.getScope()
			.flatMap(scope -> visibleType(context, scope, call))
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent.locks",
					Set.of("Condition")))
			.isPresent();
	}

	private static boolean threadReceiver(InspectionContext context, MethodCallExpr call) {
		Expression scope = call.getScope().orElse(null);
		if (scope instanceof MethodCallExpr current && "currentThread".equals(current.getNameAsString())
				&& staticOwner(context, current, "java.lang", "Thread")) {
			return true;
		}
		return scope != null && visibleType(context, scope, call)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("Thread")))
			.isPresent();
	}

	private static Optional<String> visibleType(InspectionContext context, Expression expression, Node use) {
		Optional<String> type = TypeLookup.visibleType(context.compilationUnit(), expression, use);
		if (type.isPresent()) {
			return type;
		}
		return visibleField(context, expression, use).map(field -> field.getElementType().asString());
	}

	private static Optional<FieldDeclaration> visibleField(InspectionContext context, Expression expression, Node use) {
		TypeDeclaration<?> owner = use.findAncestor(TypeDeclaration.class).orElse(null);
		String name = expression instanceof NameExpr simple ? simple.getNameAsString()
				: expression instanceof FieldAccessExpr access && owner != null && sourceLocalScope(access, owner)
						? access.getNameAsString() : null;
		if (name == null || expression instanceof NameExpr
				&& TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name, use)) {
			return Optional.empty();
		}
		if (owner == null) {
			return Optional.empty();
		}
		return owner.getFields()
			.stream()
			.filter(field -> field.getVariables()
				.stream()
				.anyMatch(variable -> variable.getNameAsString().equals(name)))
			.findFirst();
	}

	private static boolean sourceLocalScope(FieldAccessExpr access, TypeDeclaration<?> owner) {
		return access.getScope() instanceof ThisExpr || access.getScope().toString().equals(owner.getNameAsString());
	}

	private static boolean staticOwner(InspectionContext context, MethodCallExpr call, String packageName,
			String owner) {
		return call.getScope()
			.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), packageName,
					Set.of(owner)))
			.isPresent();
	}

	private static boolean hasSynchronizedAncestor(Node node) {
		Node current = node;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof SynchronizedStmt) {
				return true;
			}
			if (current instanceof LambdaExpr || current instanceof CallableDeclaration<?>
					|| current instanceof TypeDeclaration<?>) {
				return false;
			}
		}
		return false;
	}

	private static boolean insideLockedContext(Node node) {
		Node current = node;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof SynchronizedStmt) {
				return true;
			}
			if (current instanceof LambdaExpr || current instanceof TypeDeclaration<?>) {
				return false;
			}
			if (current instanceof MethodDeclaration method) {
				return method.isSynchronized();
			}
			if (current instanceof CallableDeclaration<?>) {
				return false;
			}
		}
		return false;
	}

	private static boolean sourceLocalNativeMethod(MethodCallExpr call) {
		if (call.getScope().filter(scope -> !(scope instanceof ThisExpr)).isPresent()) {
			return false;
		}
		ClassOrInterfaceDeclaration owner = call.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		if (owner == null) {
			return false;
		}
		List<MethodDeclaration> matches = owner.getMethodsByName(call.getNameAsString())
			.stream()
			.filter(method -> method.getParameters().size() == call.getArguments().size())
			.toList();
		return !matches.isEmpty() && matches.stream().allMatch(MethodDeclaration::isNative);
	}

	private static boolean directlyWithin(Node node, SynchronizedStmt statement) {
		Node current = node;
		while (current != statement) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof LambdaExpr || parent instanceof CallableDeclaration<?>
					|| parent instanceof TypeDeclaration<?>) {
				return false;
			}
			current = parent;
		}
		return true;
	}

}

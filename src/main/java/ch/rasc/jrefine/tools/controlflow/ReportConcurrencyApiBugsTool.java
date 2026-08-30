package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reports mechanically unsafe uses of monitors, locks, threads, and volatile fields. */
public final class ReportConcurrencyApiBugsTool implements InspectionTool {

	private static final Set<String> LOCK_TYPES = Set.of("Lock", "ReentrantLock", "ReadWriteLock",
			"ReentrantReadWriteLock");

	private static final Set<String> UPDATER_TYPES = Set.of("AtomicIntegerFieldUpdater", "AtomicLongFieldUpdater",
			"AtomicReferenceFieldUpdater");

	@Override
	public String id() {
		return "report-concurrency-api-bugs";
	}

	@Override
	public String description() {
		return "Report unsafe monitor, Lock, Thread, ThreadLocal, and volatile-field operations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		methodCalls(context, findings);
		threadCreations(context, findings);
		fields(context, findings);
		synchronizedStatements(context, findings);
		volatileOperations(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void methodCalls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			String method = call.getNameAsString();
			if (conditionReceiver(context, call)) {
				if (method.startsWith("await") && !insideLoop(call)) {
					findings.add(Finding.at(call, "Condition.await() is not called in a loop"));
				}
				if (Set.of("wait", "notify", "notifyAll").contains(method)) {
					findings
						.add(Finding.at(call, "Object wait/notify method is called on a Condition; use await/signal"));
				}
			}
			if ("set".equals(method) && call.getArguments().size() == 1
					&& call.getArgument(0) instanceof NullLiteralExpr
					&& receiverType(context, call, "java.lang", Set.of("ThreadLocal"))) {
				findings.add(Finding.at(call, "ThreadLocal.set(null) should be remove()"));
			}
			if ("wait".equals(method) && call.getArguments().isEmpty()) {
				waitCall(context, call, findings);
			}
			if (Set.of("notify", "notifyAll").contains(method) && call.getArguments().isEmpty() && !ownsMonitor(call)) {
				findings
					.add(Finding.at(call, "wait()/notify() call is not in a synchronized context owning its monitor"));
			}
			if (Set.of("stop", "suspend", "resume").contains(method) && threadReceiver(context, call)) {
				findings.add(Finding.at(call, "Unsafe deprecated Thread." + method + "() call"));
			}
			if ("sleep".equals(method) && staticOwner(context, call, "java.lang", "Thread")
					&& insideSynchronizedContext(call)) {
				findings.add(Finding.at(call, "Thread.sleep() is called while holding a monitor"));
			}
			if ("start".equals(method) && call.findAncestor(ConstructorDeclaration.class).isPresent()
					&& threadReceiver(context, call)) {
				findings.add(Finding.at(call, "Thread is started during object construction"));
			}
			if ("runFinalizersOnExit".equals(method) && staticOwner(context, call, "java.lang", "System")) {
				findings.add(Finding.at(call, "Call to removed System.runFinalizersOnExit()"));
			}
			if (Set.of("lock", "lockInterruptibly").contains(method) && lockReceiver(context, call)) {
				String issue = lockIssue(call);
				if (issue != null) {
					findings.add(Finding.at(call, issue));
				}
			}
		}
	}

	private static void waitCall(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		if (!insideLoop(call)) {
			findings.add(Finding.at(call, "wait() is not called in a loop"));
		}
		if (!insideConditionOrLoop(call)) {
			findings.add(Finding.at(call, "Unconditional wait() call"));
		}
		if (!ownsMonitor(call)) {
			findings.add(Finding.at(call, "wait()/notify() call is not in a synchronized context owning its monitor"));
		}
		if (synchronizedAncestors(call) > 1) {
			findings.add(Finding.at(call, "wait() is called while holding two monitors"));
		}
	}

	private static void threadCreations(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			if (!TypeLookup.isKnownJavaLangType(context.compilationUnit(), creation.getType().asString(), Set
				.of("Thread")) || creation
					.getAnonymousClassBody()
					.filter(body -> body.stream()
						.filter(MethodDeclaration.class::isInstance)
						.map(MethodDeclaration.class::cast)
						.anyMatch(method -> "run".equals(method.getNameAsString()) && method.getParameters().isEmpty()))
					.isPresent()) {
				continue;
			}
			if (creation.getArguments().isEmpty()
					|| creation.getArguments().size() == 1 && creation.getArgument(0) instanceof StringLiteralExpr) {
				findings.add(Finding.at(creation, "Thread is instantiated with the default no-op run() method"));
			}
		}
	}

	private static void fields(InspectionContext context, List<Finding> findings) {
		for (FieldDeclaration field : context.compilationUnit().findAll(FieldDeclaration.class)) {
			String type = field.getElementType().asString();
			if (TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent.atomic", UPDATER_TYPES)
					&& (!field.isStatic() || !field.isFinal())) {
				findings.add(Finding.at(field, "AtomicFieldUpdater field should be static final"));
			}
			if (TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent",
					Set.of("ThreadLocalRandom"))) {
				findings.add(Finding.at(field, "ThreadLocalRandom instance should not be stored in a field"));
			}
			if (field.isVolatile()
					&& field.getVariables().stream().anyMatch(variable -> variable.getType().isArrayType())) {
				findings
					.add(Finding.at(field, "Volatile array only makes the array reference volatile, not its elements"));
			}
		}
	}

	private static void synchronizedStatements(InspectionContext context, List<Finding> findings) {
		for (SynchronizedStmt statement : context.compilationUnit().findAll(SynchronizedStmt.class)) {
			Expression monitor = statement.getExpression();
			if (statement.getBody().getStatements().isEmpty() && !AstSupport.hasComment(context, statement.getBody())) {
				findings.add(Finding.at(statement, "Empty synchronized statement"));
			}
			String monitorType = visibleType(context, monitor, statement).orElse("");
			if (TypeLookup.isKnownType(context.compilationUnit(), monitorType, "java.util.concurrent.locks",
					LOCK_TYPES)) {
				findings.add(Finding.at(monitor, "Synchronization on a Lock object does not acquire that Lock"));
			}
			VariableDeclarator variable = visibleVariable(context, monitor, statement).orElse(null);
			if (monitor instanceof StringLiteralExpr
					|| variable != null && variable.getInitializer().orElse(null) instanceof StringLiteralExpr) {
				findings.add(Finding.at(monitor, "Synchronization uses an object initialized from a shared literal"));
			}
			FieldDeclaration field = visibleField(context, monitor, statement).orElse(null);
			if (field != null && !field.isFinal()) {
				findings.add(Finding.at(monitor, "Synchronization uses a non-final field whose monitor can change"));
			}
		}
	}

	private static void volatileOperations(InspectionContext context, List<Finding> findings) {
		Map<String, FieldDeclaration> volatileFields = new HashMap<>();
		for (FieldDeclaration field : context.compilationUnit().findAll(FieldDeclaration.class)) {
			if (field.isVolatile()) {
				field.getVariables().forEach(variable -> volatileFields.put(variable.getNameAsString(), field));
			}
		}
		for (UnaryExpr unary : context.compilationUnit().findAll(UnaryExpr.class)) {
			if (Set
				.of(UnaryExpr.Operator.POSTFIX_DECREMENT, UnaryExpr.Operator.POSTFIX_INCREMENT,
						UnaryExpr.Operator.PREFIX_DECREMENT, UnaryExpr.Operator.PREFIX_INCREMENT)
				.contains(unary.getOperator())
					&& volatileFieldName(context, unary.getExpression(), unary, volatileFields) != null) {
				findings.add(Finding.at(unary, "Non-atomic increment or decrement of volatile field"));
			}
		}
		for (AssignExpr assignment : context.compilationUnit().findAll(AssignExpr.class)) {
			String field = volatileFieldName(context, assignment.getTarget(), assignment, volatileFields);
			if (field == null) {
				continue;
			}
			if (assignment.getOperator() != AssignExpr.Operator.ASSIGN || assignment.getValue()
				.findAll(NameExpr.class)
				.stream()
				.anyMatch(name -> name.getNameAsString().equals(field) && !TypeLookup
					.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), field, name))) {
				findings.add(Finding.at(assignment, "Non-atomic read-modify-write operation on volatile field"));
			}
		}
	}

	private static String volatileFieldName(InspectionContext context, Expression expression, Node use,
			Map<String, FieldDeclaration> fields) {
		String name = expression instanceof NameExpr simple ? simple.getNameAsString()
				: expression instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr
						? access.getNameAsString() : null;
		if (name == null || !fields.containsKey(name)) {
			return null;
		}
		if (expression instanceof NameExpr
				&& TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name, use)) {
			return null;
		}
		return name;
	}

	private static boolean conditionReceiver(InspectionContext context, MethodCallExpr call) {
		return receiverType(context, call, "java.util.concurrent.locks", Set.of("Condition"));
	}

	private static boolean lockReceiver(InspectionContext context, MethodCallExpr call) {
		return receiverType(context, call, "java.util.concurrent.locks", LOCK_TYPES);
	}

	private static boolean threadReceiver(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty()) {
			return false;
		}
		Expression scope = call.getScope().orElseThrow();
		if (scope instanceof MethodCallExpr current && "currentThread".equals(current.getNameAsString())
				&& staticOwner(context, current, "java.lang", "Thread")) {
			return true;
		}
		if (scope instanceof ObjectCreationExpr creation) {
			return TypeLookup.isKnownJavaLangType(context.compilationUnit(), creation.getType().asString(),
					Set.of("Thread"));
		}
		return visibleType(context, scope, call)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("Thread")))
			.isPresent();
	}

	private static boolean receiverType(InspectionContext context, MethodCallExpr call, String packageName,
			Set<String> types) {
		return call.getScope()
			.flatMap(scope -> visibleType(context, scope, call))
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, packageName, types))
			.isPresent();
	}

	private static Optional<String> visibleType(InspectionContext context, Expression expression, Node use) {
		Optional<String> lexical = TypeLookup.visibleType(context.compilationUnit(), expression, use);
		if (lexical.isPresent()) {
			return lexical;
		}
		return visibleField(context, expression, use).map(field -> field.getElementType().asString());
	}

	private static Optional<FieldDeclaration> visibleField(InspectionContext context, Expression expression, Node use) {
		String name = expression instanceof NameExpr simple ? simple.getNameAsString()
				: expression instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr
						? access.getNameAsString() : null;
		if (name == null || expression instanceof NameExpr
				&& TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name, use)) {
			return Optional.empty();
		}
		TypeDeclaration<?> owner = use.findAncestor(TypeDeclaration.class).orElse(null);
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

	private static Optional<VariableDeclarator> visibleVariable(InspectionContext context, Expression expression,
			Node use) {
		if (!(expression instanceof NameExpr name)) {
			return Optional.empty();
		}
		return context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> variable.getNameAsString().equals(name.getNameAsString()))
			.filter(variable -> variable.findAncestor(FieldDeclaration.class).isEmpty())
			.filter(variable -> before(variable, use))
			.filter(variable -> variable.findAncestor(BlockStmt.class)
				.filter(block -> block.isAncestorOf(use))
				.isPresent())
			.max((left,
					right) -> left.getBegin().orElse(Position.HOME).compareTo(right.getBegin().orElse(Position.HOME)));
	}

	private static boolean staticOwner(InspectionContext context, MethodCallExpr call, String packageName,
			String owner) {
		return call.getScope()
			.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), packageName,
					Set.of(owner)))
			.isPresent();
	}

	private static boolean insideLoop(Node node) {
		return hasAncestorBeforeBoundary(node, Set.of(ForStmt.class, ForEachStmt.class, WhileStmt.class, DoStmt.class));
	}

	private static boolean insideConditionOrLoop(Node node) {
		return hasAncestorBeforeBoundary(node, Set.of(ForStmt.class, ForEachStmt.class, WhileStmt.class, DoStmt.class,
				IfStmt.class, ConditionalExpr.class));
	}

	private static boolean hasAncestorBeforeBoundary(Node node, Set<Class<?>> types) {
		Node current = node;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof CallableDeclaration<?> || current instanceof TypeDeclaration<?>) {
				return false;
			}
			Node ancestor = current;
			if (types.stream().anyMatch(type -> type.isInstance(ancestor))) {
				return true;
			}
		}
		return false;
	}

	private static boolean ownsMonitor(MethodCallExpr call) {
		String receiver = call.getScope()
			.map(Object::toString)
			.map(ReportConcurrencyApiBugsTool::normalizeMonitor)
			.orElse("this");
		Node current = call;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof SynchronizedStmt statement
					&& normalizeMonitor(statement.getExpression().toString()).equals(receiver)) {
				return true;
			}
			if (current instanceof MethodDeclaration method) {
				return method.isSynchronized() && !method.isStatic() && "this".equals(receiver);
			}
			if (current instanceof CallableDeclaration<?> || current instanceof TypeDeclaration<?>) {
				return false;
			}
		}
		return false;
	}

	private static String normalizeMonitor(String value) {
		String result = value.replace("(", "").replace(")", "").replace(" ", "");
		return result.startsWith("this.") ? result.substring("this.".length()) : result;
	}

	private static int synchronizedAncestors(Node node) {
		int result = 0;
		Node current = node;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof SynchronizedStmt) {
				result++;
			}
			if (current instanceof CallableDeclaration<?> || current instanceof TypeDeclaration<?>) {
				return result;
			}
		}
		return result;
	}

	private static boolean insideSynchronizedContext(Node node) {
		if (synchronizedAncestors(node) > 0) {
			return true;
		}
		return node.findAncestor(MethodDeclaration.class).filter(MethodDeclaration::isSynchronized).isPresent();
	}

	private static String lockIssue(MethodCallExpr lockCall) {
		String receiver = lockCall.getScope()
			.map(Object::toString)
			.map(ReportConcurrencyApiBugsTool::normalizeMonitor)
			.orElse("");
		if (receiver.isEmpty()) {
			return "Lock is acquired without a matching unlock() in a finally block";
		}
		TryStmt enclosing = lockCall.findAncestor(TryStmt.class).orElse(null);
		if (enclosing != null && enclosing.getTryBlock().isAncestorOf(lockCall) && unlocks(enclosing, receiver)) {
			return "Lock is acquired inside the try block; acquire it before the try so finally only unlocks after successful acquisition";
		}
		CallableDeclaration<?> owner = lockCall.findAncestor(CallableDeclaration.class).orElse(null);
		if (owner == null) {
			return "Lock is acquired without a matching unlock() in a finally block";
		}
		boolean safe = owner.findAll(TryStmt.class)
			.stream()
			.filter(statement -> statement.getFinallyBlock().isPresent())
			.filter(statement -> after(lockCall, statement))
			.anyMatch(statement -> unlocks(statement, receiver));
		return safe ? null : "Lock is acquired without a matching unlock() in a finally block";
	}

	private static boolean unlocks(TryStmt statement, String receiver) {
		return statement.getFinallyBlock()
			.stream()
			.flatMap(block -> block.findAll(MethodCallExpr.class).stream())
			.anyMatch(call -> "unlock".equals(call.getNameAsString()) && call.getArguments().isEmpty()
					&& call.getScope()
						.map(Object::toString)
						.map(ReportConcurrencyApiBugsTool::normalizeMonitor)
						.filter(receiver::equals)
						.isPresent());
	}

	private static boolean before(Node first, Node second) {
		Position left = first.getBegin().orElse(Position.HOME);
		Position right = second.getBegin().orElse(Position.HOME);
		return left.line < right.line || left.line == right.line && left.column < right.column;
	}

	private static boolean after(Node first, Node second) {
		return before(first, second);
	}

}

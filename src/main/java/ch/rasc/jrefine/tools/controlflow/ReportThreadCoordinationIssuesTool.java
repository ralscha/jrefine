package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reports source-local wait/notify and Condition coordination contracts. */
public final class ReportThreadCoordinationIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-thread-coordination-issues";
	}

	@Override
	public String description() {
		return "Report unmatched wait/notify and await/signal coordination contracts";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			coordinationInType(context, type, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void coordinationInType(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		Map<String, EnumMap<CoordinationKind, List<MethodCallExpr>>> groups = new HashMap<>();
		for (MethodCallExpr call : type.findAll(MethodCallExpr.class)) {
			if (call.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) != type) {
				continue;
			}
			CoordinationKind kind = kind(context, call);
			String key = kind == null ? null : receiverKey(context, type, call);
			if (key == null) {
				continue;
			}
			groups.computeIfAbsent(key, ignored -> new EnumMap<>(CoordinationKind.class))
				.computeIfAbsent(kind, ignored -> new ArrayList<>())
				.add(call);
			if (kind == CoordinationKind.NOTIFY && !stateChangedBeforeNotification(context, type, call, key)) {
				findings.add(Finding.at(call,
						"notify()/notifyAll() has no visible guarded-state change before notification"));
			}
		}
		groups.forEach((key, calls) -> {
			unmatched(calls, CoordinationKind.WAIT, CoordinationKind.NOTIFY,
					"wait() has no corresponding notify()/notifyAll() on this source-local monitor", findings);
			unmatched(calls, CoordinationKind.NOTIFY, CoordinationKind.WAIT,
					"notify()/notifyAll() has no corresponding wait() on this source-local monitor", findings);
			unmatched(calls, CoordinationKind.AWAIT, CoordinationKind.SIGNAL,
					"Condition await has no corresponding signal()/signalAll() on this source-local field", findings);
			unmatched(calls, CoordinationKind.SIGNAL, CoordinationKind.AWAIT,
					"Condition signal has no corresponding await() on this source-local field", findings);
		});
	}

	private static void unmatched(Map<CoordinationKind, List<MethodCallExpr>> calls, CoordinationKind present,
			CoordinationKind counterpart, String message, List<Finding> findings) {
		List<MethodCallExpr> occurrences = calls.getOrDefault(present, List.of());
		if (!occurrences.isEmpty() && calls.getOrDefault(counterpart, List.of()).isEmpty()) {
			findings.add(Finding.at(occurrences.getFirst(), message));
		}
	}

	private static CoordinationKind kind(InspectionContext context, MethodCallExpr call) {
		return switch (call.getNameAsString()) {
			case "wait" -> call.getArguments().size() <= 2 ? CoordinationKind.WAIT : null;
			case "notify", "notifyAll" -> call.getArguments().isEmpty() ? CoordinationKind.NOTIFY : null;
			case "await", "awaitNanos", "awaitUntil", "awaitUninterruptibly" ->
				conditionReceiver(context, call) ? CoordinationKind.AWAIT : null;
			case "signal", "signalAll" ->
				call.getArguments().isEmpty() && conditionReceiver(context, call) ? CoordinationKind.SIGNAL : null;
			default -> null;
		};
	}

	private static boolean conditionReceiver(InspectionContext context, MethodCallExpr call) {
		return call.getScope()
			.flatMap(scope -> TypeLookup.visibleType(context.compilationUnit(), scope, call))
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent.locks",
					Set.of("Condition")))
			.isPresent();
	}

	private static String receiverKey(InspectionContext context, ClassOrInterfaceDeclaration type,
			MethodCallExpr call) {
		if (call.getScope().isEmpty() || call.getScope().orElseThrow() instanceof ThisExpr) {
			return "this";
		}
		return fieldName(context, type, call.getScope().orElseThrow(), call).map(name -> "field:" + name).orElse(null);
	}

	private static Optional<String> fieldName(InspectionContext context, ClassOrInterfaceDeclaration type,
			Expression expression, Node use) {
		String name = expression instanceof NameExpr simple ? simple.getNameAsString()
				: expression instanceof FieldAccessExpr access && (access.getScope() instanceof ThisExpr
						|| access.getScope().toString().equals(type.getNameAsString())) ? access.getNameAsString()
								: null;
		if (name == null || expression instanceof NameExpr
				&& TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name, use)) {
			return Optional.empty();
		}
		return type.getFields()
			.stream()
			.flatMap(field -> field.getVariables().stream())
			.filter(variable -> variable.getNameAsString().equals(name))
			.findFirst()
			.map(variable -> name);
	}

	private static boolean stateChangedBeforeNotification(InspectionContext context, ClassOrInterfaceDeclaration type,
			MethodCallExpr call, String receiverKey) {
		Node region = owningSynchronizedRegion(context, type, call, receiverKey).orElse(null);
		if (region == null) {
			return true;
		}
		boolean assignment = region.findAll(AssignExpr.class)
			.stream()
			.filter(candidate -> directlyWithin(candidate, region, type))
			.filter(candidate -> before(candidate, call))
			.anyMatch(candidate -> containsFieldTarget(context, type, candidate.getTarget(), candidate));
		if (assignment) {
			return true;
		}
		return region.findAll(UnaryExpr.class)
			.stream()
			.filter(candidate -> directlyWithin(candidate, region, type))
			.filter(candidate -> before(candidate, call))
			.filter(candidate -> Set
				.of(UnaryExpr.Operator.POSTFIX_DECREMENT, UnaryExpr.Operator.POSTFIX_INCREMENT,
						UnaryExpr.Operator.PREFIX_DECREMENT, UnaryExpr.Operator.PREFIX_INCREMENT)
				.contains(candidate.getOperator()))
			.anyMatch(candidate -> fieldName(context, type, candidate.getExpression(), candidate).isPresent());
	}

	private static Optional<Node> owningSynchronizedRegion(InspectionContext context, ClassOrInterfaceDeclaration type,
			MethodCallExpr call, String receiverKey) {
		Node current = call;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof SynchronizedStmt statement) {
				String monitor = statement.getExpression() instanceof ThisExpr ? "this"
						: fieldName(context, type, statement.getExpression(), statement).map(name -> "field:" + name)
							.orElse(null);
				if (receiverKey.equals(monitor)) {
					return Optional.of(statement.getBody());
				}
			}
			if (current instanceof MethodDeclaration method) {
				if (method.isSynchronized() && !method.isStatic() && "this".equals(receiverKey)) {
					return method.getBody().map(Node.class::cast);
				}
				return Optional.empty();
			}
			if (current instanceof CallableDeclaration<?>
					|| current != type && current instanceof ClassOrInterfaceDeclaration) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	private static boolean containsFieldTarget(InspectionContext context, ClassOrInterfaceDeclaration type,
			Expression target, Node use) {
		if (fieldName(context, type, target, use).isPresent()) {
			return true;
		}
		return target.findAll(NameExpr.class).stream().anyMatch(name -> fieldName(context, type, name, use).isPresent())
				|| target.findAll(FieldAccessExpr.class)
					.stream()
					.anyMatch(access -> fieldName(context, type, access, use).isPresent());
	}

	private static boolean directlyWithin(Node node, Node region, ClassOrInterfaceDeclaration type) {
		Node current = node;
		while (current != region) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof CallableDeclaration<?>
					|| parent instanceof ClassOrInterfaceDeclaration && parent != type) {
				return false;
			}
			current = parent;
		}
		return true;
	}

	private static boolean before(Node left, Node right) {
		Position leftPosition = left.getBegin().orElse(Position.HOME);
		Position rightPosition = right.getBegin().orElse(Position.HOME);
		return leftPosition.line < rightPosition.line
				|| leftPosition.line == rightPosition.line && leftPosition.column < rightPosition.column;
	}

	private enum CoordinationKind {

		WAIT, NOTIFY, AWAIT, SIGNAL

	}

}

package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
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
import java.util.List;
import java.util.Set;

/**
 * Reports source-local fields whose synchronization policy is internally inconsistent.
 */
public final class ReportSynchronizationConsistencyIssuesTool implements PolicyInspectionTool {

	private static final Set<String> ATOMIC_TYPES = Set.of("AtomicBoolean", "AtomicInteger", "AtomicIntegerArray",
			"AtomicLong", "AtomicLongArray", "AtomicMarkableReference", "AtomicReference", "AtomicReferenceArray",
			"AtomicStampedReference", "DoubleAccumulator", "DoubleAdder", "LongAccumulator", "LongAdder");

	private static final Set<String> TEXT_STATE_TYPES = Set.of("Collator", "DateFormat", "DecimalFormat",
			"MessageFormat", "NumberFormat", "SimpleDateFormat");

	private static final Set<String> CALENDAR_TYPES = Set.of("Calendar", "GregorianCalendar");

	private static final Set<String> COLLECTION_TYPES = Set.of("ArrayDeque", "ArrayList", "HashMap", "HashSet",
			"IdentityHashMap", "LinkedHashMap", "LinkedHashSet", "LinkedList", "TreeMap", "TreeSet", "WeakHashMap");

	private static final Set<String> MUTATING_METHODS = Set.of("add", "addAll", "clear", "compute", "computeIfAbsent",
			"computeIfPresent", "merge", "offer", "poll", "pop", "push", "put", "putAll", "remove", "removeAll",
			"removeIf", "replace", "replaceAll", "retainAll", "set");

	@Override
	public String id() {
		return "report-synchronization-consistency-issues";
	}

	@Override
	public String description() {
		return "Report mixed synchronized access and unsafe shared static state";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			for (FieldDeclaration field : type.getFields()) {
				for (VariableDeclarator variable : field.getVariables()) {
					fieldConsistency(context, type, field, variable, findings);
				}
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void fieldConsistency(InspectionContext context, ClassOrInterfaceDeclaration type,
			FieldDeclaration field, VariableDeclarator variable, List<Finding> findings) {
		List<FieldUse> uses = fieldUses(context, type, variable.getNameAsString());
		boolean locked = uses.stream().anyMatch(FieldUse::locked);
		boolean unlocked = uses.stream().anyMatch(use -> !use.locked());
		boolean written = uses.stream().anyMatch(FieldUse::write);

		if (!field.isFinal() && !field.isVolatile() && !atomicField(context, variable) && locked && unlocked
				&& written) {
			findings.add(Finding.at(field, "Field is accessed in both synchronized and unsynchronized contexts"));
		}
		if (!field.isPrivate() && locked) {
			findings.add(Finding.at(field,
					"Non-private field is accessed in a synchronized context and can be bypassed externally"));
		}
		if (field.isStatic() && nonThreadSafeType(context, variable)) {
			boolean unsafeAccess = uses.stream()
				.filter(use -> !use.locked())
				.anyMatch(use -> intrinsicallyUnsafeType(context, variable) || use.write());
			if (unsafeAccess) {
				findings.add(Finding.at(field, "Non-thread-safe static field is accessed without synchronization"));
			}
		}
	}

	private static List<FieldUse> fieldUses(InspectionContext context, ClassOrInterfaceDeclaration type, String field) {
		ArrayList<FieldUse> result = new ArrayList<>();
		type.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) == type)
			.filter(name -> name.findAncestor(MethodDeclaration.class).isPresent())
			.filter(name -> name.getNameAsString().equals(field))
			.filter(name -> !TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), field,
					name))
			.forEach(name -> result.add(new FieldUse(locked(name), writeAccess(name))));
		type.findAll(FieldAccessExpr.class)
			.stream()
			.filter(access -> access.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) == type)
			.filter(access -> access.findAncestor(MethodDeclaration.class).isPresent())
			.filter(access -> access.getNameAsString().equals(field))
			.filter(access -> access.getScope() instanceof ThisExpr
					|| access.getScope().toString().equals(type.getNameAsString()))
			.forEach(access -> result.add(new FieldUse(locked(access), writeAccess(access))));
		return result;
	}

	private static boolean locked(Node use) {
		Node current = use;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof SynchronizedStmt) {
				return true;
			}
			if (current instanceof MethodDeclaration method) {
				return method.isSynchronized();
			}
			if (current instanceof ClassOrInterfaceDeclaration) {
				return false;
			}
		}
		return false;
	}

	private static boolean writeAccess(Node use) {
		AssignExpr assignment = use.findAncestor(AssignExpr.class).orElse(null);
		if (assignment != null && (assignment.getTarget() == use || assignment.getTarget().isAncestorOf(use))) {
			return true;
		}
		UnaryExpr unary = use.findAncestor(UnaryExpr.class).orElse(null);
		if (unary != null && (unary.getExpression() == use || unary.getExpression().isAncestorOf(use))
				&& Set
					.of(UnaryExpr.Operator.POSTFIX_DECREMENT, UnaryExpr.Operator.POSTFIX_INCREMENT,
							UnaryExpr.Operator.PREFIX_DECREMENT, UnaryExpr.Operator.PREFIX_INCREMENT)
					.contains(unary.getOperator())) {
			return true;
		}
		MethodCallExpr call = use.findAncestor(MethodCallExpr.class).orElse(null);
		return call != null && call.getScope().filter(scope -> scope == use || scope.isAncestorOf(use)).isPresent()
				&& MUTATING_METHODS.contains(call.getNameAsString());
	}

	private static boolean atomicField(InspectionContext context, VariableDeclarator variable) {
		return TypeLookup.isKnownType(context.compilationUnit(), variable.getType().asString(),
				"java.util.concurrent.atomic", ATOMIC_TYPES);
	}

	private static boolean nonThreadSafeType(InspectionContext context, VariableDeclarator variable) {
		String type = variable.getType().asString();
		return TypeLookup.isKnownType(context.compilationUnit(), type, "java.text", TEXT_STATE_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.util", CALENDAR_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.util", COLLECTION_TYPES);
	}

	private static boolean intrinsicallyUnsafeType(InspectionContext context, VariableDeclarator variable) {
		String type = variable.getType().asString();
		return TypeLookup.isKnownType(context.compilationUnit(), type, "java.text", TEXT_STATE_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.util", CALENDAR_TYPES);
	}

	private record FieldUse(boolean locked, boolean write) {
	}

}

package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.stmt.Statement;

/** Reports iterator, collection, array, varargs, and Properties misuse. */
public final class ReportCollectionArrayBugsTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "Iterable", "List", "Set", "Queue",
			"Deque", "ArrayList", "LinkedList", "HashSet", "TreeSet", "ArrayDeque", "Vector");

	private static final Set<String> LIST_TYPES = Set.of("List", "AbstractList", "ArrayList", "LinkedList", "Stack",
			"Vector");

	private static final Set<String> ELEMENT_METHODS = Set.of("add", "contains", "remove", "indexOf", "lastIndexOf",
			"containsKey", "containsValue");

	private static final Set<String> PROPERTIES_HASHTABLE_METHODS = Set.of("put", "putIfAbsent", "putAll", "get");

	private static final Set<String> MAP_TYPES = Set.of("Map", "SortedMap", "NavigableMap", "AbstractMap", "HashMap",
			"LinkedHashMap", "TreeMap", "EnumMap", "IdentityHashMap", "WeakHashMap", "Hashtable", "Properties");

	private static final Set<String> CONCURRENT_MAP_TYPES = Set.of("ConcurrentMap", "ConcurrentNavigableMap",
			"ConcurrentHashMap", "ConcurrentSkipListMap");

	private static final Set<String> SET_TYPES = Set.of("Set", "SortedSet", "NavigableSet", "AbstractSet", "HashSet",
			"LinkedHashSet", "TreeSet", "EnumSet");

	private static final Set<String> CONCURRENT_SET_TYPES = Set.of("CopyOnWriteArraySet", "ConcurrentSkipListSet");

	@Override
	public String id() {
		return "report-collection-array-bugs";
	}

	@Override
	public String description() {
		return "Report iterator, collection, array, varargs, and Properties misuse";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		iteratorContracts(context, findings);
		selfAddition(context, findings);
		varargs(context, findings);
		overwrittenElements(context, findings);
		emptyContainers(context, findings);
		sortedCollections(context, findings);
		suspiciousCollectionCalls(context, findings);
		properties(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void iteratorContracts(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			boolean iterator = type.getImplementedTypes()
				.stream()
				.anyMatch(parent -> Set.of("Iterator", "ListIterator").contains(parent.getNameAsString()));
			if (!iterator) {
				continue;
			}
			for (MethodDeclaration method : type.getMethods()) {
				if (Set.of("hasNext", "hasPrevious").contains(method.getNameAsString()) && method.getBody().isPresent()
						&& method.getBody()
							.orElseThrow()
							.findAll(MethodCallExpr.class)
							.stream()
							.anyMatch(call -> Set.of("next", "previous").contains(call.getNameAsString())
									&& (call.getScope().isEmpty() || call.getScope().orElseThrow().isThisExpr()))) {
					findings.add(Finding.at(method, "Iterator availability method advances the iterator"));
				}
				if ("next".equals(method
					.getNameAsString()) && method.getParameters().isEmpty() && method.getBody().isPresent() && method
						.getBody()
						.orElseThrow()
						.findAll(ThrowStmt.class)
						.stream()
						.noneMatch(
								statement -> statement.getExpression().toString().contains("NoSuchElementException"))) {
					findings
						.add(Finding.at(method, "Iterator.next() implementation cannot throw NoSuchElementException"));
				}
			}
		}
	}

	private static void selfAddition(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isEmpty() || call.getArguments().isEmpty()) {
				continue;
			}
			Expression scope = call.getScope().orElseThrow();
			if (call.getArguments().stream().anyMatch(scope::equals)
					&& Set.of("add", "addAll", "put", "putAll", "removeAll", "retainAll")
						.contains(call.getNameAsString())
					&& !assertionContext(call)) {
				findings.add(Finding.at(call, "Collection or map is added to itself"));
			}
		}
	}

	private static boolean assertionContext(Node node) {
		Node current = node;
		while (current != null) {
			if (current instanceof com.github.javaparser.ast.stmt.AssertStmt) {
				return true;
			}
			if (current instanceof MethodCallExpr call && call.getNameAsString().startsWith("assert")) {
				return true;
			}
			current = current.getParentNode().orElse(null);
		}
		return false;
	}

	private static void varargs(InspectionContext context, List<Finding> findings) {
		List<MethodDeclaration> methods = context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(method -> !method.getParameters().isEmpty()
					&& method.getParameter(method.getParameters().size() - 1).isVarArgs())
			.toList();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			for (MethodDeclaration method : methods) {
				if (!call.getNameAsString().equals(method.getNameAsString())
						|| call.getArguments().size() != method.getParameters().size()) {
					continue;
				}
				Expression argument = call.getArgument(call.getArguments().size() - 1);
				String type = visibleType(context, argument, call).orElse("");
				String varargElement = method.getParameter(method.getParameters().size() - 1).getType().asString();
				boolean forwardedVarargsArray = compatibleVarargsArray(type, varargElement);
				if (!forwardedVarargsArray
						&& (argument instanceof NullLiteralExpr || argument instanceof ArrayCreationExpr)) {
					findings.add(Finding.at(argument, "Confusing single argument to varargs method"));
				}
				if (!forwardedVarargsArray && primitiveArray(type)) {
					findings.add(Finding.at(argument, "Confusing primitive array argument to varargs method"));
				}
				if (COLLECTION_TYPES.contains(simple(type))) {
					findings.add(Finding.at(argument, "Iterable is used as one vararg element"));
				}
				if (argument instanceof ConditionalExpr conditional) {
					String left = visibleType(context, conditional.getThenExpr(), call).orElse("");
					String right = visibleType(context, conditional.getElseExpr(), call).orElse("");
					if (left.endsWith("[]") != right.endsWith("[]")) {
						findings.add(Finding.at(argument,
								"Suspicious ternary operator mixes array and element vararg branches"));
					}
				}
			}
		}
	}

	private static void overwrittenElements(InspectionContext context, List<Finding> findings) {
		for (BlockStmt block : context.compilationUnit().findAll(BlockStmt.class)) {
			String prior = null;
			for (Statement statement : block.getStatements()) {
				String key = writtenKey(context, statement).orElse(null);
				if (key != null && key.equals(prior)) {
					findings
						.add(Finding.at(statement, "Map, Set, or array element is overwritten by a consecutive write"));
				}
				prior = key;
			}
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"of".equals(call.getNameAsString()) || call.getScope().isEmpty()
					|| !Set.of("Set", "java.util.Set").contains(call.getScope().orElseThrow().toString())) {
				continue;
			}
			HashSet<String> seen = new HashSet<>();
			for (Expression argument : call.getArguments()) {
				if (!seen.add(argument.toString())) {
					findings.add(Finding.at(argument, "Set.of() contains a duplicate element"));
				}
			}
		}
	}

	private static Optional<String> writtenKey(InspectionContext context, Statement statement) {
		if (!(statement instanceof ExpressionStmt expressionStatement)) {
			return Optional.empty();
		}
		Expression expression = expressionStatement.getExpression();
		if (expression instanceof MethodCallExpr call && call.getScope().isPresent()
				&& !call.getArguments().isEmpty()) {
			Expression receiver = call.getScope().orElseThrow();
			String type = TypeLookup.visibleType(context.compilationUnit(), receiver, call).orElse("");
			boolean mapWrite = "put".equals(call.getNameAsString()) && knownMapType(context, type);
			boolean setWrite = "add".equals(call.getNameAsString()) && knownSetType(context, type);
			if (mapWrite || setWrite) {
				return Optional.of(receiver + ":" + call.getArgument(0));
			}
		}
		if (expression instanceof AssignExpr assignment && assignment.getOperator() == AssignExpr.Operator.ASSIGN
				&& assignment.getTarget() instanceof ArrayAccessExpr access && stableArrayIndex(access.getIndex())
				&& assignment.getValue().findAll(ArrayAccessExpr.class).stream().noneMatch(access::equals)) {
			return Optional.of(access.getName() + ":" + access.getIndex());
		}
		return Optional.empty();
	}

	private static boolean stableArrayIndex(Expression index) {
		return index.findAll(AssignExpr.class).isEmpty() && index.findAll(MethodCallExpr.class).isEmpty()
				&& index.findAll(com.github.javaparser.ast.expr.UnaryExpr.class)
					.stream()
					.noneMatch(unary -> Set
						.of(com.github.javaparser.ast.expr.UnaryExpr.Operator.PREFIX_INCREMENT,
								com.github.javaparser.ast.expr.UnaryExpr.Operator.POSTFIX_INCREMENT,
								com.github.javaparser.ast.expr.UnaryExpr.Operator.PREFIX_DECREMENT,
								com.github.javaparser.ast.expr.UnaryExpr.Operator.POSTFIX_DECREMENT)
						.contains(unary.getOperator()));
	}

	private static boolean knownMapType(InspectionContext context, String type) {
		return TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, MAP_TYPES) || TypeLookup
			.isKnownType(context.compilationUnit(), type, "java.util.concurrent", CONCURRENT_MAP_TYPES);
	}

	private static boolean knownSetType(InspectionContext context, String type) {
		return TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, SET_TYPES) || TypeLookup
			.isKnownType(context.compilationUnit(), type, "java.util.concurrent", CONCURRENT_SET_TYPES);
	}

	private static void emptyContainers(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isEmpty() || !(call.getScope().orElseThrow() instanceof MethodCallExpr factory)) {
				continue;
			}
			boolean emptyFactory = factory.getArguments().isEmpty()
					&& (factory.getNameAsString().startsWith("empty") || "of".equals(factory.getNameAsString()));
			if (emptyFactory && Set.of("isEmpty", "size", "contains", "containsKey", "get", "remove")
				.contains(call.getNameAsString())) {
				findings.add(Finding.at(call, "Redundant operation on an empty container"));
			}
		}
	}

	private static void sortedCollections(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			if (!Set.of("TreeSet", "TreeMap", "PriorityQueue").contains(creation.getType().getNameAsString())
					|| !creation.getArguments().isEmpty() || creation.getType().getTypeArguments().isEmpty()
					|| creation.getType().getTypeArguments().orElseThrow().isEmpty()) {
				continue;
			}
			String element = creation.getType().getTypeArguments().orElseThrow().get(0).asString();
			ClassOrInterfaceDeclaration declaration = context.compilationUnit()
				.findAll(ClassOrInterfaceDeclaration.class)
				.stream()
				.filter(type -> type.getNameAsString().equals(simple(element)))
				.findFirst()
				.orElse(null);
			if (declaration != null && declaration.getImplementedTypes()
				.stream()
				.noneMatch(parent -> "Comparable".equals(parent.getNameAsString()))) {
				findings.add(Finding.at(creation,
						"Sorted collection relies on natural ordering of non-Comparable elements"));
			}
		}
	}

	private static void suspiciousCollectionCalls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isEmpty()) {
				continue;
			}
			Expression scope = call.getScope().orElseThrow();
			if (ELEMENT_METHODS.contains(call.getNameAsString()) && call.getArguments().size() == 1) {
				String expected = genericElementType(context, scope, call).orElse("");
				String actual = visibleType(context, call.getArgument(0), call).orElse("");
				if (!expected.isEmpty() && obviousMismatch(expected, actual)
						&& !listIndexRemoval(context, call, scope)) {
					findings
						.add(Finding.at(call, "Suspicious collection method call uses an incompatible element type"));
				}
			}
			if ("toArray".equals(call.getNameAsString()) && call.getArguments().size() == 1) {
				String expected = genericElementType(context, scope, call).orElse("");
				String actual = visibleType(context, call.getArgument(0), call).orElse("");
				if (!expected.isEmpty() && actual.endsWith("[]") && !simple(expected).equals(simple(actual))) {
					findings.add(Finding.at(call, "Suspicious Collection.toArray() component type"));
				}
			}
		}
	}

	private static void properties(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isEmpty() || !PROPERTIES_HASHTABLE_METHODS.contains(call.getNameAsString())) {
				continue;
			}
			String type = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
				.orElse("");
			if (TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, Set.of("Properties"))) {
				findings.add(Finding.at(call, "Properties object is used through inherited Hashtable methods"));
			}
		}
	}

	private static Optional<String> genericElementType(InspectionContext context, Expression expression, Node use) {
		return TypeLookup.visibleDeclaredType(context.compilationUnit(), expression, use)
			.stream()
			.filter(type -> type.contains("<") && type.contains(">"))
			.map(type -> type.substring(type.indexOf('<') + 1, type.lastIndexOf('>')))
			.findFirst();
	}

	private static boolean listIndexRemoval(InspectionContext context, MethodCallExpr call, Expression receiver) {
		if (!"remove".equals(call.getNameAsString()) || !integralIndex(context, call.getArgument(0), call)) {
			return false;
		}
		String type = TypeLookup.visibleType(context.compilationUnit(), receiver, call).orElse("");
		return TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, LIST_TYPES) || TypeLookup
			.isKnownType(context.compilationUnit(), type, "java.util.concurrent", Set.of("CopyOnWriteArrayList"));
	}

	private static boolean integralIndex(InspectionContext context, Expression expression, Node use) {
		if (expression.isIntegerLiteralExpr() || expression.isCharLiteralExpr()) {
			return true;
		}
		if (expression.isEnclosedExpr()) {
			return integralIndex(context, expression.asEnclosedExpr().getInner(), use);
		}
		if (expression.isUnaryExpr()) {
			com.github.javaparser.ast.expr.UnaryExpr unary = expression.asUnaryExpr();
			if (Set
				.of(com.github.javaparser.ast.expr.UnaryExpr.Operator.PLUS,
						com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS)
				.contains(unary.getOperator())) {
				return integralIndex(context, unary.getExpression(), use);
			}
		}
		if (expression.isCastExpr()) {
			return Set.of("byte", "short", "char", "int").contains(expression.asCastExpr().getType().asString());
		}
		String type = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), expression, use).orElse("");
		return Set.of("byte", "short", "char", "int").contains(type);
	}

	private static boolean obviousMismatch(String expected, String actual) {
		if (actual.isEmpty()) {
			return false;
		}
		String left = simple(expected);
		String right = simple(actual);
		return Set.of("String", "Integer", "Long", "Double", "Boolean", "Character").contains(left)
				&& Set.of("String", "Integer", "Long", "Double", "Boolean", "Character").contains(right)
				&& !left.equals(right);
	}

	private static Optional<String> visibleType(InspectionContext context, Expression expression, Node use) {
		if (expression.isStringLiteralExpr()) {
			return Optional.of("String");
		}
		if (expression.isIntegerLiteralExpr()) {
			return Optional.of("Integer");
		}
		if (expression.isLongLiteralExpr()) {
			return Optional.of("Long");
		}
		if (expression.isDoubleLiteralExpr()) {
			return Optional.of("Double");
		}
		if (expression.isBooleanLiteralExpr()) {
			return Optional.of("Boolean");
		}
		if (expression.isCharLiteralExpr()) {
			return Optional.of("Character");
		}
		if (expression instanceof ArrayCreationExpr array) {
			return Optional.of(array.getElementType().asString() + "[]".repeat(array.getLevels().size()));
		}
		return TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), expression, use);
	}

	private static boolean primitiveArray(String type) {
		if (!type.endsWith("[]")) {
			return false;
		}
		String element = type.substring(0, type.indexOf('['));
		return Set.of("boolean", "byte", "short", "int", "long", "char", "float", "double").contains(element);
	}

	private static boolean compatibleVarargsArray(String argumentType, String elementType) {
		return argumentType.equals(elementType + "[]")
				|| simple(argumentType).equals(simple(elementType)) && argumentType.endsWith("[]");
	}

	private static String simple(String type) {
		String currentType = type;
		while (currentType.endsWith("[]")) {
			currentType = currentType.substring(0, currentType.length() - 2);
		}
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

}

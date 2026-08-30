package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

/** Reports local objects whose read/write usage is inconsistent and likely incomplete. */
public final class ReportStateUsageBugsTool implements InspectionTool {

	private static final Set<String> BUILDER_TYPES = Set.of("StringBuilder", "StringBuffer", "StringJoiner");

	private static final Set<String> BUILDER_WRITES = Set.of("append", "add", "insert", "delete", "deleteCharAt",
			"replace", "setCharAt", "setLength");

	private static final Set<String> BUILDER_READS = Set.of("toString", "length", "charAt", "chars", "codePoints",
			"substring", "subSequence", "indexOf");

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "Map", "Queue", "Deque",
			"ArrayList", "LinkedList", "HashSet", "TreeSet", "HashMap", "TreeMap");

	private static final Set<String> COLLECTION_WRITES = Set.of("add", "addAll", "put", "putAll", "remove", "removeAll",
			"clear", "replace", "set");

	private static final Set<String> COLLECTION_READS = Set.of("get", "contains", "containsKey", "containsValue",
			"size", "isEmpty", "iterator", "stream", "forEach", "toArray", "keySet", "values", "entrySet");

	private static final Set<Set<String>> CONFUSING_NAMES = Set.of(Set.of("width", "height"), Set.of("x", "y"),
			Set.of("row", "column"), Set.of("source", "target"), Set.of("from", "to"), Set.of("min", "max"),
			Set.of("left", "right"), Set.of("start", "end"));

	private static final Pattern ACCESSOR_PREFIX = Pattern.compile("^(get|set|new|old)");

	@Override
	public String id() {
		return "report-state-usage-bugs";
	}

	@Override
	public String description() {
		return "Report mismatched reads/writes and suspicious variable or parameter name combinations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (AstSupport.ancestor(variable, FieldDeclaration.class).isPresent()) {
				continue;
			}
			Node boundary = boundary(variable).orElse(null);
			if (boundary == null) {
				continue;
			}
			String type = simple(variable.getType().asString());
			if (BUILDER_TYPES.contains(type)) {
				localUsage(variable, boundary, BUILDER_WRITES, BUILDER_READS).filter(Usage::mismatched)
					.ifPresent(usage -> findings.add(Finding.at(variable, "Mismatched query and update of " + type)));
			}
			if (COLLECTION_TYPES.contains(type)) {
				localUsage(variable, boundary, COLLECTION_WRITES, COLLECTION_READS).filter(Usage::mismatched)
					.ifPresent(
							usage -> findings.add(Finding.at(variable, "Mismatched query and update of collection")));
			}
			if (variable.getType().isArrayType() && locallyAllocatedArray(variable)) {
				arrayUsage(variable, boundary)
					.filter(usage -> !externallyUsed(variable.getNameAsString(), boundary, Set.of(), Set.of()))
					.filter(Usage::mismatched)
					.ifPresent(usage -> findings.add(Finding.at(variable, "Mismatched read and write of array")));
			}
		}
		suspiciousNames(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static Optional<Usage> localUsage(VariableDeclarator variable, Node boundary, Set<String> writes,
			Set<String> reads) {
		String name = variable.getNameAsString();
		if (externallyUsed(name, boundary, writes, reads)) {
			return Optional.empty();
		}
		Optional<Usage> explicitUsage = usage(name, boundary, writes, reads);
		if (explicitUsage.isEmpty()) {
			return Optional.empty();
		}
		Usage usage = explicitUsage.orElseThrow();
		if (usage.write() && !usage.read() && !locallyAllocatedObject(variable)) {
			return Optional.empty();
		}
		return Optional.of(new Usage(usage.read(), usage.write() || initializerSuppliesState(variable)));
	}

	private static boolean locallyAllocatedObject(VariableDeclarator variable) {
		return variable.getInitializer().filter(initializer -> initializer.isObjectCreationExpr()).isPresent();
	}

	private static boolean initializerSuppliesState(VariableDeclarator variable) {
		if (variable.getParentNode().flatMap(Node::getParentNode).filter(ForEachStmt.class::isInstance).isPresent()) {
			return true;
		}
		return variable.getInitializer()
			.filter(initializer -> !(initializer.isObjectCreationExpr()
					&& initializer.asObjectCreationExpr().getArguments().isEmpty())
					&& !(initializer.isArrayCreationExpr()
							&& initializer.asArrayCreationExpr().getInitializer().isEmpty()))
			.isPresent();
	}

	private static boolean locallyAllocatedArray(VariableDeclarator variable) {
		return variable.getInitializer().filter(expression -> expression.isArrayCreationExpr()).isPresent();
	}

	private static boolean externallyUsed(String name, Node boundary, Set<String> writes, Set<String> reads) {
		for (NameExpr use : boundary.findAll(NameExpr.class)) {
			if (!name.equals(use.getNameAsString())) {
				continue;
			}
			Node parent = use.getParentNode().orElse(null);
			if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == use).isPresent()
					&& (writes.contains(call.getNameAsString()) || reads.contains(call.getNameAsString()))) {
				continue;
			}
			if (parent instanceof ArrayAccessExpr access && access.getName() == use) {
				continue;
			}
			return true;
		}
		return false;
	}

	private static Optional<Usage> usage(String name, Node boundary, Set<String> writes, Set<String> reads) {
		List<MethodCallExpr> calls = boundary.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> call.getScope()
				.filter(scope -> scope instanceof NameExpr used && used.getNameAsString().equals(name))
				.isPresent())
			.toList();
		List<MethodReferenceExpr> references = boundary.findAll(MethodReferenceExpr.class)
			.stream()
			.filter(reference -> reference.getScope().toString().equals(name))
			.toList();
		boolean write = calls.stream().anyMatch(call -> writes.contains(call.getNameAsString()))
				|| references.stream().anyMatch(reference -> writes.contains(reference.getIdentifier()));
		boolean read = calls.stream()
			.anyMatch(call -> reads.contains(call.getNameAsString())
					|| writes.contains(call.getNameAsString()) && resultUsed(call))
				|| references.stream().anyMatch(reference -> reads.contains(reference.getIdentifier()));
		return write || read ? Optional.of(new Usage(read, write)) : Optional.empty();
	}

	private static boolean resultUsed(MethodCallExpr call) {
		if (!(call.getParentNode().orElse(null) instanceof ExpressionStmt statement)) {
			return true;
		}
		if (!(statement.getParentNode().orElse(null) instanceof LambdaExpr lambda)) {
			return false;
		}
		return AstSupport.ancestor(lambda, MethodCallExpr.class)
			.map(owner -> Set.of("filter", "anyMatch", "allMatch", "noneMatch", "takeWhile", "dropWhile", "removeIf")
				.contains(owner.getNameAsString()))
			.orElse(false);
	}

	private static Optional<Usage> arrayUsage(VariableDeclarator variable, Node boundary) {
		String name = variable.getNameAsString();
		List<ArrayAccessExpr> accesses = boundary.findAll(ArrayAccessExpr.class)
			.stream()
			.filter(access -> access.getName() instanceof NameExpr used && used.getNameAsString().equals(name))
			.toList();
		boolean write = initializerSuppliesState(variable)
				|| accesses.stream().anyMatch(ReportStateUsageBugsTool::arrayElementWritten);
		boolean read = accesses.stream().anyMatch(ReportStateUsageBugsTool::arrayElementRead);
		return write || read ? Optional.of(new Usage(read, write)) : Optional.empty();
	}

	private static boolean arrayElementWritten(ArrayAccessExpr access) {
		Node parent = access.getParentNode().orElse(null);
		return parent instanceof AssignExpr assignment && assignment.getTarget() == access
				|| parent instanceof UnaryExpr unary && unary.getExpression() == access
						&& Set
							.of(UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
									UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT)
							.contains(unary.getOperator());
	}

	private static boolean arrayElementRead(ArrayAccessExpr access) {
		Node parent = access.getParentNode().orElse(null);
		if (parent instanceof AssignExpr assignment && assignment.getTarget() == access) {
			return assignment.getOperator() != AssignExpr.Operator.ASSIGN;
		}
		return true;
	}

	private static void suspiciousNames(InspectionContext context, List<Finding> findings) {
		List<MethodDeclaration> methods = context.compilationUnit().findAll(MethodDeclaration.class);
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			methods.stream()
				.filter(method -> method.getNameAsString().equals(call.getNameAsString())
						&& method.getParameters().size() == call.getArguments().size())
				.findFirst()
				.ifPresent(method -> {
					for (int index = 0; index < call.getArguments().size(); index++) {
						if (call.getArgument(index) instanceof NameExpr argument && confusing(
								method.getParameter(index).getNameAsString(), argument.getNameAsString())) {
							findings.add(Finding.at(call.getArgument(index),
									"Suspicious variable/parameter name combination"));
						}
					}
				});
		}
	}

	private static boolean confusing(String expected, String actual) {
		String currentExpected = expected;
		String currentActual = actual;
		currentExpected = normalized(currentExpected);
		currentActual = normalized(currentActual);
		if (currentExpected.equals(currentActual)) {
			return false;
		}
		for (Set<String> pair : CONFUSING_NAMES) {
			if (pair.contains(currentExpected) && pair.contains(currentActual)) {
				return true;
			}
		}
		return false;
	}

	private static String normalized(String name) {
		String currentName = name;
		currentName = ACCESSOR_PREFIX.matcher(currentName).replaceFirst("").toLowerCase(java.util.Locale.ROOT);
		return currentName;
	}

	private static Optional<Node> boundary(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			if (parent.orElseThrow() instanceof CallableDeclaration<?> callable) {
				return Optional.of(callable);
			}
			parent = parent.orElseThrow().getParentNode();
		}
		return Optional.empty();
	}

	private static String simple(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

	private record Usage(boolean read, boolean write) {
		private boolean mismatched() {
			return read != write;
		}
	}

}

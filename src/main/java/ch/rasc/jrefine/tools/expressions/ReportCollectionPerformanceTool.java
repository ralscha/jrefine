package ch.rasc.jrefine.tools.expressions;

import java.util.Optional;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.github.javaparser.ast.expr.Expression;

/**
 * Reports collection API and implementation choices with avoidable asymptotic or
 * allocation cost.
 */
public final class ReportCollectionPerformanceTool implements PolicyInspectionTool {

	private static final Set<String> LISTS = Set.of("List", "ArrayList", "LinkedList", "Vector", "Stack");

	private static final Set<String> SETS = Set.of("Set", "HashSet", "LinkedHashSet", "TreeSet");

	private static final Set<String> MAPS = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "EnumMap",
			"ConcurrentMap");

	@Override
	public String id() {
		return "report-collection-performance";
	}

	@Override
	public String description() {
		return "Report inefficient collection calls, implementations, traversals, and copying loops";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isPresent() && call.getArguments().size() == 1
					&& "containsAll".equals(call.getNameAsString())
					&& known(context, call.getScope().orElseThrow(), call, LISTS)) {
				findings.add(Finding.at(call, "List.containsAll() may have quadratic performance"));
			}
			if (call.getScope().isPresent() && call.getArguments().size() == 1
					&& "removeAll".equals(call.getNameAsString())
					&& known(context, call.getScope().orElseThrow(), call, SETS)
					&& known(context, call.getArgument(0), call, LISTS)) {
				findings.add(Finding.at(call, "Set.removeAll(List) may select a slow traversal strategy"));
			}
			if (call.getScope().isPresent() && "remove".equals(call.getNameAsString())
					&& call.getArguments().size() == 1 && loopAncestor(call)
					&& known(context, call.getScope().orElseThrow(), call, LISTS)) {
				findings.add(Finding.at(call, "List.remove() in a loop may repeatedly shift elements"));
			}
		}
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			enumCollection(context, variable, findings);
		}
		for (ForEachStmt loop : context.compilationUnit().findAll(ForEachStmt.class)) {
			keySetIteration(context, loop, findings);
			arrayCopyToCollection(context, loop, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void enumCollection(InspectionContext context, VariableDeclarator variable, List<Finding> findings) {
		if (!(variable.getInitializer().orElse(null) instanceof ObjectCreationExpr creation)) {
			return;
		}
		String declared = variable.getType().asString().replace(" ", "");
		boolean map = declared.startsWith("Map<") || declared.startsWith("java.util.Map<");
		boolean set = declared.startsWith("Set<") || declared.startsWith("java.util.Set<");
		if (!map && !set) {
			return;
		}
		int start = declared.indexOf('<') + 1;
		int end = declared.indexOf(map ? ',' : '>', start);
		if (start <= 0 || end < start) {
			return;
		}
		String argument = declared.substring(start, end);
		int dot = argument.lastIndexOf('.');
		if (dot >= 0) {
			argument = argument.substring(dot + 1);
		}
		String enumName = argument;
		if (context.compilationUnit()
			.findAll(EnumDeclaration.class)
			.stream()
			.noneMatch(value -> value.getNameAsString().equals(enumName))) {
			return;
		}
		String implementation = creation.getType().getNameAsString();
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), creation.getType().asString(), "java.util",
				Set.of(implementation))) {
			return;
		}
		if (map && Set.of("HashMap", "LinkedHashMap", "TreeMap").contains(implementation)) {
			findings.add(Finding.at(creation, "Map with enum keys can use EnumMap"));
		}
		if (set && Set.of("HashSet", "LinkedHashSet", "TreeSet").contains(implementation)) {
			findings.add(Finding.at(creation, "Set of enum values can use EnumSet"));
		}
	}

	private static void keySetIteration(InspectionContext context, ForEachStmt loop, List<Finding> findings) {
		if (!(loop.getIterable() instanceof MethodCallExpr keys) || !"keySet".equals(keys.getNameAsString())
				|| keys.getScope().isEmpty() || loop.getVariable().getVariables().size() != 1
				|| !known(context, keys.getScope().orElseThrow(), keys, MAPS)) {
			return;
		}
		String mapText = keys.getScope().orElseThrow().toString();
		String key = loop.getVariable().getVariable(0).getNameAsString();
		boolean retrieves = loop.getBody()
			.findAll(MethodCallExpr.class)
			.stream()
			.anyMatch(call -> "get".equals(call.getNameAsString()) && call.getScope().isPresent()
					&& call.getScope().orElseThrow().toString().equals(mapText) && call.getArguments().size() == 1
					&& call.getArgument(0) instanceof NameExpr name && name.getNameAsString().equals(key));
		if (retrieves) {
			findings.add(Finding.at(loop, "Iterate over Map.entrySet() when both keys and values are used"));
		}
	}

	private static void arrayCopyToCollection(InspectionContext context, ForEachStmt loop, List<Finding> findings) {
		if (!(loop.getIterable() instanceof NameExpr array)
				|| TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), array, loop)
					.filter(type -> type.endsWith("[]"))
					.isEmpty()) {
			return;
		}
		String item = loop.getVariable().getVariable(0).getNameAsString();
		long adds = loop.getBody()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> "add".equals(call.getNameAsString()) && call.getArguments().size() == 1
					&& call.getArgument(0) instanceof NameExpr name && name.getNameAsString().equals(item))
			.count();
		if (adds == 1) {
			findings.add(Finding.at(loop, "Manual array-to-collection copy can use a bulk operation"));
		}
	}

	private static boolean known(InspectionContext context, Expression expression, Node use, Set<String> allowed) {
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, allowed))
			.isPresent();
	}

	private static boolean loopAncestor(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof ForStmt || value instanceof ForEachStmt || value instanceof WhileStmt
					|| value instanceof DoStmt) {
				return true;
			}
			parent = value.getParentNode();
		}
		return false;
	}

}

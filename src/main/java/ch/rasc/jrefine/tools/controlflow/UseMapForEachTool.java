package ch.rasc.jrefine.tools.controlflow;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.SemanticEvidence;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Replaces entrySet traversal with Map.forEach(). */
public final class UseMapForEachTool implements InspectionTool {

	private static final Set<String> MAP_TYPES = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap",
			"NavigableMap", "ConcurrentMap", "ConcurrentHashMap", "WeakHashMap", "IdentityHashMap", "Hashtable");

	@Override
	public String id() {
		return "use-map-for-each";
	}

	@Override
	public String description() {
		return "Replace Map.entrySet() traversal with Map.forEach()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(ForEachStmt.class)
			.stream()
			.map(loop -> loopCandidate(context, loop))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> callCandidate(context, call))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.node(), "Replace entrySet traversal with Map.forEach()"));
			if (applyFixes) {
				context.editor().replace(candidate.node().getRange().orElseThrow(), replacement(context, candidate));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> loopCandidate(InspectionContext context, ForEachStmt loop) {
		if (!(loop.getIterable() instanceof MethodCallExpr entries) || !"entrySet".equals(entries.getNameAsString())
				|| !entries.getArguments().isEmpty() || entries.getScope().isEmpty()
				|| loop.getVariable().getVariables().size() != 1 || AstSupport.hasComment(context, loop)
				|| introducesCheckedExceptionRisk(loop) || hasEscapingControlFlow(loop.getBody())) {
			return Optional.empty();
		}
		Expression map = entries.getScope().orElseThrow();
		String entryName = loop.getVariable().getVariable(0).getNameAsString();
		return mapReceiver(context, map, loop) && validEntryUses(loop.getBody(), entryName)
				&& !capturesReassignedLocal(context, loop.getBody(), loop, entryName)
						? Optional.of(new Candidate(loop, map, loop.getBody(), entryName)) : Optional.empty();
	}

	private static Optional<Candidate> callCandidate(InspectionContext context, MethodCallExpr call) {
		if (!"forEach".equals(call.getNameAsString()) || call.getArguments().size() != 1
				|| !(call.getArgument(0) instanceof LambdaExpr lambda) || lambda.getParameters().size() != 1
				|| !(call.getScope().orElse(null) instanceof MethodCallExpr entries)
				|| !"entrySet".equals(entries.getNameAsString()) || !entries.getArguments().isEmpty()
				|| entries.getScope().isEmpty() || AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Expression map = entries.getScope().orElseThrow();
		String entryName = lambda.getParameter(0).getNameAsString();
		return mapReceiver(context, map, call) && validEntryUses(lambda.getBody(), entryName)
				? Optional.of(new Candidate(call, map, lambda.getBody(), entryName)) : Optional.empty();
	}

	private static boolean mapReceiver(InspectionContext context, Expression map, Node use) {
		return TypeLookup.visibleType(context.compilationUnit(), map, use)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, MAP_TYPES))
			.isPresent();
	}

	private static boolean validEntryUses(Node body, String entryName) {
		List<NameExpr> references = body.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(entryName))
			.toList();
		if (references.isEmpty()) {
			return false;
		}
		return references.stream()
			.allMatch(name -> name.getParentNode()
				.filter(MethodCallExpr.class::isInstance)
				.map(MethodCallExpr.class::cast)
				.filter(call -> call.getScope().filter(name::equals).isPresent() && call.getArguments().isEmpty()
						&& Set.of("getKey", "getValue").contains(call.getNameAsString()))
				.isPresent());
	}

	private static boolean capturesReassignedLocal(InspectionContext context, Node body, Node use, String entryName) {
		Set<String> declaredInside = body.findAll(VariableDeclarator.class)
			.stream()
			.map(VariableDeclarator::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		return body.findAll(NameExpr.class)
			.stream()
			.map(NameExpr::getNameAsString)
			.filter(name -> !name.equals(entryName) && !declaredInside.contains(name))
			.filter(name -> TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name, use))
			.distinct()
			.anyMatch(name -> !SemanticEvidence.isEffectivelyFinalLocalOrParameter(context, name, use));
	}

	private static boolean introducesCheckedExceptionRisk(ForEachStmt loop) {
		if (loop.getBody().findAll(MethodCallExpr.class).isEmpty()) {
			return false;
		}
		Optional<Node> parent = loop.getParentNode();
		while (parent.isPresent()) {
			Node ancestor = parent.orElseThrow();
			if (ancestor instanceof TryStmt tryStatement && !tryStatement.getCatchClauses().isEmpty()) {
				return true;
			}
			if (ancestor instanceof CallableDeclaration<?> callable) {
				return !callable.getThrownExceptions().isEmpty();
			}
			parent = ancestor.getParentNode();
		}
		return false;
	}

	private static boolean hasEscapingControlFlow(Node body) {
		return !body.findAll(BreakStmt.class).isEmpty() || !body.findAll(ContinueStmt.class).isEmpty()
				|| !body.findAll(ReturnStmt.class).isEmpty();
	}

	private static String replacement(InspectionContext context, Candidate candidate) {
		HashSet<String> names = new HashSet<>(
				candidate.body().findAll(SimpleName.class).stream().map(SimpleName::asString).toList());
		String key = availableName(names, "key", "mapKey");
		names.add(key);
		String value = availableName(names, "value", "mapValue");
		Node body = candidate.body().clone();
		body.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> call.getScope().orElse(null) instanceof NameExpr name
					&& name.getNameAsString().equals(candidate.entryName()) && call.getArguments().isEmpty()
					&& Set.of("getKey", "getValue").contains(call.getNameAsString()))
			.toList()
			.forEach(call -> call.replace(new NameExpr("getKey".equals(call.getNameAsString()) ? key : value)));
		String bodyText;
		if (body instanceof ExpressionStmt expression) {
			bodyText = expression.getExpression().toString();
		}
		else if (body instanceof BlockStmt) {
			bodyText = body.toString();
		}
		else if (body instanceof Statement) {
			bodyText = "{ " + body + " }";
		}
		else {
			bodyText = body.toString();
		}
		return context.editor().text(candidate.map()) + ".forEach((" + key + ", " + value + ") -> " + bodyText + ")"
				+ (candidate.node() instanceof ForEachStmt ? ";" : "");
	}

	private static String availableName(Set<String> names, String preferred, String fallback) {
		if (!names.contains(preferred)) {
			return preferred;
		}
		if (!names.contains(fallback)) {
			return fallback;
		}
		int index = 2;
		while (names.contains(fallback + index)) {
			index++;
		}
		return fallback + index;
	}

	private record Candidate(Node node, Expression map, Node body, String entryName) {
	}

}

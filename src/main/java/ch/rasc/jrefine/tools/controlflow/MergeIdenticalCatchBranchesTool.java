package ch.rasc.jrefine.tools.controlflow;

import java.util.Set;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.UnionType;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.github.javaparser.ast.expr.MethodCallExpr;

/** Combines adjacent identical catch bodies into a multi-catch clause. */
public final class MergeIdenticalCatchBranchesTool implements InspectionTool {

	private static final Map<String, Optional<Class<?>>> JDK_TYPES = new ConcurrentHashMap<>();

	private static final Set<String> COMMON_THROWABLE_METHODS = Set.of("getMessage", "getLocalizedMessage", "getCause",
			"getStackTrace", "getSuppressed", "toString", "printStackTrace");

	@Override
	public String id() {
		return "merge-identical-catch-branches";
	}

	@Override
	public String description() {
		return "Merge identical catch branches into multi-catch clauses";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(TryStmt.class)
			.stream()
			.map(statement -> candidate(context, statement))
			.flatMap(Optional::stream)
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && other.statement().isAncestorOf(candidate.statement())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.second(), "Merge identical catch branches"));
			if (applyFixes) {
				TryStmt replacement = candidate.statement().clone();
				CatchClause first = replacement.getCatchClauses().get(candidate.firstIndex());
				CatchClause second = replacement.getCatchClauses().get(candidate.firstIndex() + 1);
				first.getParameter().setType(union(first, second));
				replacement.getCatchClauses().remove(candidate.firstIndex() + 1);
				context.editor()
					.replace(candidate.statement().getRange().orElseThrow(),
							indentLikeOriginal(context, candidate.statement(), replacement.toString()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, TryStmt statement) {
		if (AstSupport.hasComment(context, statement)) {
			return Optional.empty();
		}
		for (int index = 0; index + 1 < statement.getCatchClauses().size(); index++) {
			CatchClause first = statement.getCatchClauses().get(index);
			CatchClause second = statement.getCatchClauses().get(index + 1);
			if (normalizedBody(first).equals(normalizedBody(second)) && unrelatedEnough(context, first, second)
					&& hasTypeIndependentParameterUses(first) && hasTypeIndependentParameterUses(second)) {
				return Optional.of(new Candidate(statement, index, second));
			}
		}
		return Optional.empty();
	}

	private static BlockStmt normalizedBody(CatchClause clause) {
		BlockStmt body = clause.getBody().clone();
		String parameter = clause.getParameter().getNameAsString();
		body.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(parameter))
			.forEach(name -> name.setName("caughtException"));
		return body;
	}

	private static boolean unrelatedEnough(InspectionContext context, CatchClause first, CatchClause second) {
		List<String> left = simpleTypes(first);
		List<String> right = simpleTypes(second);
		for (String leftName : left) {
			Optional<Class<?>> leftType = knownException(context, leftName);
			if (leftType.isEmpty()) {
				return false;
			}
			for (String rightName : right) {
				Optional<Class<?>> rightType = knownException(context, rightName);
				if (rightType.isEmpty() || leftType.orElseThrow().isAssignableFrom(rightType.orElseThrow())
						|| rightType.orElseThrow().isAssignableFrom(leftType.orElseThrow())) {
					return false;
				}
			}
		}
		return true;
	}

	private static Optional<Class<?>> knownException(InspectionContext context, String spelling) {
		String simple = TypeLookup.simpleName(spelling);
		ArrayList<String> names = new ArrayList<>();
		if (spelling.contains(".")) {
			names.add(spelling);
		}
		else {
			names.add("java.lang." + simple);
			context.compilationUnit()
				.getImports()
				.stream()
				.filter(imported -> !imported.isStatic())
				.forEach(imported -> {
					if (imported.isAsterisk()) {
						names.add(imported.getNameAsString() + "." + simple);
					}
					else if (imported.getName().getIdentifier().equals(simple)) {
						names.add(imported.getNameAsString());
					}
				});
		}
		Class<?> resolved = null;
		for (String name : names) {
			String packageName = name.substring(0, name.lastIndexOf('.'));
			if (!TypeLookup.isKnownType(context.compilationUnit(), spelling, packageName, Set.of(simple))) {
				continue;
			}
			Optional<Class<?>> type = JDK_TYPES.computeIfAbsent(name, MergeIdenticalCatchBranchesTool::loadJdkType);
			if (type.isPresent()) {
				if (resolved != null && resolved != type.orElseThrow()) {
					return Optional.empty();
				}
				resolved = type.orElseThrow();
			}
		}
		return Optional.ofNullable(resolved);
	}

	private static Optional<Class<?>> loadJdkType(String name) {
		if (!name.startsWith("java.") && !name.startsWith("javax.")) {
			return Optional.empty();
		}
		try {
			Class<?> type = Class.forName(name, false, ClassLoader.getPlatformClassLoader());
			return Throwable.class.isAssignableFrom(type) ? Optional.of(type) : Optional.empty();
		}
		catch (ClassNotFoundException | LinkageError exception) {
			return Optional.empty();
		}
	}

	private static boolean hasTypeIndependentParameterUses(CatchClause clause) {
		String parameter = clause.getParameter().getNameAsString();
		return clause.getBody()
			.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(parameter))
			.allMatch(name -> name.getParentNode().orElse(null) instanceof MethodCallExpr call
					&& call.getScope().orElse(null) == name && call.getArguments().isEmpty()
					&& COMMON_THROWABLE_METHODS.contains(call.getNameAsString()));
	}

	private static List<String> simpleTypes(CatchClause clause) {
		if (clause.getParameter().getType() instanceof UnionType union) {
			return union.getElements().stream().map(Object::toString).toList();
		}
		return java.util.List.of(clause.getParameter().getType().asString());
	}

	private static UnionType union(CatchClause first, CatchClause second) {
		NodeList<ReferenceType> types = new NodeList<>();
		addTypes(types, first);
		addTypes(types, second);
		return new UnionType(types);
	}

	private static void addTypes(NodeList<ReferenceType> target, CatchClause clause) {
		if (clause.getParameter().getType() instanceof UnionType union) {
			union.getElements().forEach(type -> target.add(type.clone()));
		}
		else {
			target.add(StaticJavaParser.parseClassOrInterfaceType(clause.getParameter().getType().asString()));
		}
	}

	private static String indentLikeOriginal(InspectionContext context, TryStmt node, String replacement) {
		String indent = " ".repeat(Math.max(0, node.getBegin().orElseThrow().column - 1));
		return LineEndingSupport.indentLikeSource(replacement, context.editor().source(), indent);
	}

	private record Candidate(TryStmt statement, int firstIndex, CatchClause second) {
	}

}

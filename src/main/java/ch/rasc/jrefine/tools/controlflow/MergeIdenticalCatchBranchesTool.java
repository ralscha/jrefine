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
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;

/** Combines adjacent identical catch bodies into a multi-catch clause. */
public final class MergeIdenticalCatchBranchesTool implements InspectionTool {

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
			if (normalizedBody(first).equals(normalizedBody(second)) && unrelatedEnough(first, second)) {
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

	private static boolean unrelatedEnough(CatchClause first, CatchClause second) {
		List<String> left = simpleTypes(first);
		List<String> right = simpleTypes(second);
		if (left.stream().anyMatch(right::contains)) {
			return false;
		}
		Set<String> broad = java.util.Set.of("Throwable", "Exception", "RuntimeException", "Error");
		return left.stream().noneMatch(broad::contains) && right.stream().noneMatch(broad::contains);
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

package ch.rasc.jrefine.tools.controlflow;

import java.util.List;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Replaces direct element-copy loops between known JDK collections with addAll(). */
public final class UseBulkOperationTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "SortedSet", "NavigableSet",
			"Queue", "Deque", "ArrayList", "LinkedList", "Vector", "Stack", "HashSet", "LinkedHashSet", "TreeSet",
			"ArrayDeque", "PriorityQueue", "CopyOnWriteArrayList", "CopyOnWriteArraySet");

	@Override
	public String id() {
		return "use-bulk-operation";
	}

	@Override
	public String description() {
		return "Replace direct collection-copy loops with addAll()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(ForEachStmt.class)
			.stream()
			.map(loop -> candidate(context, loop))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.loop(), "Replace iteration with Collection.addAll()"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.loop().getRange().orElseThrow(), context.editor().text(candidate.target())
							+ ".addAll(" + context.editor().text(candidate.source()) + ");");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ForEachStmt loop) {
		if (AstSupport.hasComment(context, loop) || loop.getVariable().getVariables().size() != 1
				|| !(loop.getIterable() instanceof NameExpr source)) {
			return Optional.empty();
		}
		Statement statement = singleStatement(loop.getBody());
		if (!(statement instanceof ExpressionStmt expressionStatement)
				|| !(expressionStatement.getExpression() instanceof MethodCallExpr add)
				|| !"add".equals(add.getNameAsString()) || add.getArguments().size() != 1
				|| !(add.getScope().orElse(null) instanceof NameExpr target)
				|| !(add.getArgument(0) instanceof NameExpr item)
				|| !item.getNameAsString().equals(loop.getVariable().getVariable(0).getNameAsString())
				|| target.getNameAsString().equals(source.getNameAsString())) {
			return Optional.empty();
		}
		if (!knownCollection(context, source, loop) || !knownCollection(context, target, loop)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(loop, target, source));
	}

	private static Statement singleStatement(Statement body) {
		if (!(body instanceof BlockStmt block)) {
			return body;
		}
		return block.getStatements().size() == 1 ? block.getStatement(0) : null;
	}

	private static boolean knownCollection(InspectionContext context, Expression expression, ForEachStmt use) {
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, COLLECTION_TYPES))
			.isPresent();
	}

	private record Candidate(ForEachStmt loop, Expression target, Expression source) {
	}

}

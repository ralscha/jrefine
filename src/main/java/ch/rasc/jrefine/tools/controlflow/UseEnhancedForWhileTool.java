package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.List;

/**
 * Converts the canonical iterator-declaration plus while loop to an enhanced for loop.
 */
public final class UseEnhancedForWhileTool implements InspectionTool {

	private static final Set<String> ITERABLE_TYPES = Set.of("Collection", "Iterable", "List", "Set", "SortedSet",
			"NavigableSet", "Queue", "Deque", "ArrayList", "LinkedList", "Vector", "Stack", "HashSet", "LinkedHashSet",
			"TreeSet", "ArrayDeque", "PriorityQueue", "CopyOnWriteArrayList", "CopyOnWriteArraySet");

	private static final Set<String> MUTATORS = Set.of("add", "addAll", "clear", "remove", "removeAll", "removeIf",
			"replaceAll", "retainAll", "set", "sort");

	@Override
	public String id() {
		return "use-enhanced-for-while";
	}

	@Override
	public String description() {
		return "Replace iterator while loops with enhanced for loops";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(BlockStmt.class)
			.stream()
			.flatMap(block -> candidates(context, block).stream())
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && other.loop().isAncestorOf(candidate.loop())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.loop(), "Replace iterator while loop with enhanced for loop"));
			if (applyFixes) {
				VariableDeclarationExpr variable = candidate.itemDeclaration().clone();
				variable.getVariable(0).removeInitializer();
				BlockStmt body = candidate.body().clone();
				body.getStatements().remove(0);
				String replacement = indentLikeOriginal(context, candidate.loop(),
						new ForEachStmt(variable, candidate.iterable().clone(), body).toString());
				context.editor().removeLine(candidate.iteratorStatement());
				context.editor().replace(candidate.loop().getRange().orElseThrow(), replacement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static List<Candidate> candidates(InspectionContext context, BlockStmt block) {
		ArrayList<Candidate> result = new ArrayList<>();
		for (int index = 1; index < block.getStatements().size(); index++) {
			candidate(context, block.getStatement(index - 1), block.getStatement(index)).ifPresent(result::add);
		}
		return result;
	}

	private static Optional<Candidate> candidate(InspectionContext context, Statement previous, Statement statement) {
		if (!(previous instanceof ExpressionStmt iteratorStatement)
				|| !(iteratorStatement.getExpression() instanceof VariableDeclarationExpr iteratorDeclaration)
				|| iteratorDeclaration.getVariables().size() != 1 || !(statement instanceof WhileStmt loop)
				|| !(loop.getCondition() instanceof MethodCallExpr hasNext)
				|| !"hasNext".equals(hasNext.getNameAsString()) || !hasNext.getArguments().isEmpty()
				|| !(hasNext.getScope().orElse(null) instanceof NameExpr conditionIterator)
				|| !(loop.getBody() instanceof BlockStmt body) || body.getStatements().isEmpty()
				|| !(body.getStatement(0) instanceof ExpressionStmt itemStatement)
				|| !(itemStatement.getExpression() instanceof VariableDeclarationExpr itemDeclaration)
				|| itemDeclaration.getVariables().size() != 1 || AstSupport.hasComment(context, iteratorStatement)
				|| AstSupport.hasComment(context, loop)) {
			return Optional.empty();
		}
		VariableDeclarator iterator = iteratorDeclaration.getVariable(0);
		if (!conditionIterator.getNameAsString().equals(iterator.getNameAsString())
				|| !(iterator.getInitializer().orElse(null) instanceof MethodCallExpr iteratorCall)
				|| !"iterator".equals(iteratorCall.getNameAsString()) || !iteratorCall.getArguments().isEmpty()
				|| iteratorCall.getScope().isEmpty()) {
			return Optional.empty();
		}
		VariableDeclarator item = itemDeclaration.getVariable(0);
		if (!(item.getInitializer().orElse(null) instanceof MethodCallExpr next)
				|| !"next".equals(next.getNameAsString()) || !next.getArguments().isEmpty()
				|| !(next.getScope().orElse(null) instanceof NameExpr nextIterator)
				|| !nextIterator.getNameAsString().equals(iterator.getNameAsString())) {
			return Optional.empty();
		}
		Expression iterable = iteratorCall.getScope().orElseThrow();
		if (TypeLookup.visibleType(context.compilationUnit(), iterable, loop)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, ITERABLE_TYPES)
					|| TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("Iterable")))
			.isEmpty()) {
			return Optional.empty();
		}
		List<Statement> remaining = body.getStatements().stream().skip(1).toList();
		if (remaining.stream()
			.flatMap(node -> node.findAll(NameExpr.class).stream())
			.anyMatch(name -> name.getNameAsString().equals(iterator.getNameAsString()))) {
			return Optional.empty();
		}
		if (remaining.stream()
			.flatMap(node -> node.findAll(MethodCallExpr.class).stream())
			.anyMatch(call -> call.getScope().filter(iterable::equals).isPresent()
					&& MUTATORS.contains(call.getNameAsString()))) {
			return Optional.empty();
		}
		if (remaining.stream()
			.flatMap(node -> node.findAll(AssignExpr.class).stream())
			.anyMatch(assign -> assign.getTarget().equals(iterable))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(iteratorStatement, loop, itemDeclaration, iterable, body));
	}

	private static String indentLikeOriginal(InspectionContext context, Node node, String replacement) {
		String indent = " ".repeat(Math.max(0, node.getBegin().orElseThrow().column - 1));
		return LineEndingSupport.indentLikeSource(replacement, context.editor().source(), indent);
	}

	private record Candidate(ExpressionStmt iteratorStatement, WhileStmt loop, VariableDeclarationExpr itemDeclaration,
			Expression iterable, BlockStmt body) {
	}

}

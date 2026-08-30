package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Set;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.Statement;
import java.util.Optional;
import java.util.List;
import java.util.stream.Stream;

/** Converts simple indexed array/list loops to enhanced for loops. */
public final class UseEnhancedForTool implements InspectionTool {

	private static final Set<String> MUTATORS = Set.of("add", "addAll", "addFirst", "addLast", "clear", "offer",
			"offerFirst", "offerLast", "poll", "pollFirst", "pollLast", "pop", "push", "remove", "removeAll",
			"removeFirst", "removeLast", "removeIf", "replaceAll", "retainAll", "set", "sort");

	private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList", "Vector", "Stack",
			"CopyOnWriteArrayList");

	private static final Set<String> ITERABLE_TYPES = Set.of("Collection", "Iterable", "List", "Set", "SortedSet",
			"NavigableSet", "Queue", "Deque", "ArrayList", "LinkedList", "Vector", "Stack", "HashSet", "LinkedHashSet",
			"TreeSet", "ArrayDeque", "PriorityQueue", "CopyOnWriteArrayList", "CopyOnWriteArraySet");

	@Override
	public String id() {
		return "use-enhanced-for";
	}

	@Override
	public String description() {
		return "Replace eligible indexed array and list loops with enhanced for loops";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(ForStmt.class)
			.stream()
			.map(loop -> candidate(context, loop))
			.flatMap(Optional::stream)
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> ancestors(candidate.loop()).filter(ForStmt.class::isInstance)
				.map(ForStmt.class::cast)
				.noneMatch(ancestor -> all.stream().anyMatch(other -> other.loop() == ancestor)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.loop(), "Replace indexed loop with enhanced for loop"));
			if (applyFixes) {
				VariableDeclarationExpr variable = candidate.itemDeclaration().clone();
				variable.getVariable(0).removeInitializer();
				BlockStmt body = candidate.body().clone();
				body.getStatements().remove(0);
				String replacement = indentLikeOriginal(context, candidate.loop(),
						new ForEachStmt(variable, candidate.iterable().clone(), body).toString());
				context.editor().replace(candidate.loop().getRange().orElseThrow(), replacement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Stream<Node> ancestors(Node node) {
		return java.util.stream.Stream
			.iterate(node.getParentNode(), Optional::isPresent, parent -> parent.orElseThrow().getParentNode())
			.map(Optional::orElseThrow);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ForStmt loop) {
		if (hasComment(context.editor().text(loop))) {
			return java.util.Optional.empty();
		}
		Optional<Candidate> iterator = iteratorCandidate(context, loop);
		return iterator.isPresent() ? iterator : indexedCandidate(context, loop);
	}

	private static Optional<Candidate> indexedCandidate(InspectionContext context, ForStmt loop) {
		if (loop.getInitialization().size() != 1
				|| !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr indexDeclaration)
				|| indexDeclaration.getVariables().size() != 1 || loop.getCompare().isEmpty()
				|| !(loop.getCompare().orElseThrow() instanceof BinaryExpr compare)
				|| compare.getOperator() != BinaryExpr.Operator.LESS || loop.getUpdate().size() != 1
				|| !(loop.getUpdate().get(0) instanceof UnaryExpr update) || !(loop.getBody() instanceof BlockStmt body)
				|| body.getStatements().isEmpty() || !(body.getStatement(0) instanceof ExpressionStmt itemStatement)
				|| !(itemStatement.getExpression() instanceof VariableDeclarationExpr itemDeclaration)
				|| itemDeclaration.getVariables().size() != 1) {
			return java.util.Optional.empty();
		}
		VariableDeclarator index = indexDeclaration.getVariable(0);
		if (!index.getType().isPrimitiveType() || !"INT".equals(index.getType().asPrimitiveType().getType().name())
				|| index.getInitializer().filter(UseEnhancedForTool::isZero).isEmpty()
				|| !(compare.getLeft() instanceof NameExpr comparedIndex)
				|| !comparedIndex.getNameAsString().equals(index.getNameAsString())
				|| !incrementOf(update, index.getNameAsString())) {
			return java.util.Optional.empty();
		}
		Expression access = itemDeclaration.getVariable(0).getInitializer().orElse(null);
		Optional<Expression> iterable = iterable(compare.getRight(), access, index.getNameAsString());
		if (iterable.isEmpty() || !safeIterable(iterable.orElseThrow())) {
			return java.util.Optional.empty();
		}
		if (compare.getRight() instanceof MethodCallExpr
				&& TypeLookup.visibleType(context.compilationUnit(), iterable.orElseThrow(), loop)
					.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, LIST_TYPES))
					.isEmpty()) {
			return java.util.Optional.empty();
		}

		List<Statement> remainingStatements = body.getStatements().stream().skip(1).toList();
		if (remainingStatements.stream()
			.flatMap(statement -> statement.findAll(NameExpr.class).stream())
			.anyMatch(name -> name.getNameAsString().equals(index.getNameAsString()))) {
			return java.util.Optional.empty();
		}
		if (remainingStatements.stream()
			.flatMap(statement -> statement.findAll(MethodCallExpr.class).stream())
			.anyMatch(call -> call.getScope().filter(scope -> scope.equals(iterable.orElseThrow())).isPresent()
					&& MUTATORS.contains(call.getNameAsString()))) {
			return java.util.Optional.empty();
		}
		if (remainingStatements.stream()
			.flatMap(statement -> statement.findAll(AssignExpr.class).stream())
			.anyMatch(assignment -> assignment.getTarget().equals(iterable.orElseThrow()))) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Candidate(loop, itemDeclaration, iterable.orElseThrow(), body));
	}

	private static Optional<Candidate> iteratorCandidate(InspectionContext context, ForStmt loop) {
		if (loop.getInitialization().size() != 1
				|| !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr iteratorDeclaration)
				|| iteratorDeclaration.getVariables().size() != 1 || !loop.getUpdate().isEmpty()
				|| loop.getCompare().isEmpty() || !(loop.getCompare().orElseThrow() instanceof MethodCallExpr hasNext)
				|| !"hasNext".equals(hasNext.getNameAsString()) || !hasNext.getArguments().isEmpty()
				|| !(hasNext.getScope().orElse(null) instanceof NameExpr comparedIterator)
				|| !(loop.getBody() instanceof BlockStmt body) || body.getStatements().isEmpty()
				|| !(body.getStatement(0) instanceof ExpressionStmt itemStatement)
				|| !(itemStatement.getExpression() instanceof VariableDeclarationExpr itemDeclaration)
				|| itemDeclaration.getVariables().size() != 1) {
			return java.util.Optional.empty();
		}
		VariableDeclarator iterator = iteratorDeclaration.getVariable(0);
		if (!comparedIterator.getNameAsString().equals(iterator.getNameAsString())
				|| iterator.getInitializer().isEmpty()
				|| !(iterator.getInitializer().orElseThrow() instanceof MethodCallExpr iteratorCall)
				|| !"iterator".equals(iteratorCall.getNameAsString()) || !iteratorCall.getArguments().isEmpty()
				|| iteratorCall.getScope().isEmpty() || !safeIterable(iteratorCall.getScope().orElseThrow())) {
			return java.util.Optional.empty();
		}
		Expression itemInitializer = itemDeclaration.getVariable(0).getInitializer().orElse(null);
		if (!(itemInitializer instanceof MethodCallExpr next) || !"next".equals(next.getNameAsString())
				|| !next.getArguments().isEmpty() || !(next.getScope().orElse(null) instanceof NameExpr nextIterator)
				|| !nextIterator.getNameAsString().equals(iterator.getNameAsString())) {
			return java.util.Optional.empty();
		}
		Expression iterable = iteratorCall.getScope().orElseThrow();
		Optional<String> type = TypeLookup.visibleType(context.compilationUnit(), iterable, loop);
		if (type
			.filter(value -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), value, ITERABLE_TYPES)
					|| TypeLookup.isKnownJavaLangType(context.compilationUnit(), value, Set.of("Iterable")))
			.isEmpty()) {
			return java.util.Optional.empty();
		}
		List<Statement> remainingStatements = body.getStatements().stream().skip(1).toList();
		if (remainingStatements.stream()
			.flatMap(statement -> statement.findAll(NameExpr.class).stream())
			.anyMatch(name -> name.getNameAsString().equals(iterator.getNameAsString()))) {
			return java.util.Optional.empty();
		}
		if (mutatesIterable(remainingStatements, iterable)) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Candidate(loop, itemDeclaration, iterable, body));
	}

	private static boolean mutatesIterable(List<Statement> statements, Expression iterable) {
		return statements.stream()
			.flatMap(statement -> statement.findAll(MethodCallExpr.class).stream())
			.anyMatch(call -> call.getScope().filter(scope -> scope.equals(iterable)).isPresent()
					&& MUTATORS.contains(call.getNameAsString()))
				|| statements.stream()
					.flatMap(statement -> statement.findAll(AssignExpr.class).stream())
					.anyMatch(assignment -> assignment.getTarget().equals(iterable));
	}

	private static Optional<Expression> iterable(Expression bound, Expression itemAccess, String indexName) {
		if (bound instanceof FieldAccessExpr length && "length".equals(length.getNameAsString())
				&& itemAccess instanceof ArrayAccessExpr arrayAccess && isIndex(arrayAccess.getIndex(), indexName)
				&& arrayAccess.getName().equals(length.getScope())) {
			return java.util.Optional.of(length.getScope());
		}
		if (bound instanceof MethodCallExpr size && "size".equals(size.getNameAsString())
				&& size.getArguments().isEmpty() && size.getScope().isPresent()
				&& itemAccess instanceof MethodCallExpr get && "get".equals(get.getNameAsString())
				&& get.getArguments().size() == 1 && isIndex(get.getArgument(0), indexName)
				&& get.getScope().isPresent() && get.getScope().orElseThrow().equals(size.getScope().orElseThrow())) {
			return size.getScope();
		}
		return java.util.Optional.empty();
	}

	private static boolean isZero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private static boolean isIndex(Expression expression, String name) {
		return expression instanceof NameExpr index && index.getNameAsString().equals(name);
	}

	private static boolean incrementOf(UnaryExpr update, String name) {
		return (update.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
				|| update.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT)
				&& isIndex(update.getExpression(), name);
	}

	private static boolean safeIterable(Expression expression) {
		if (expression instanceof NameExpr) {
			return true;
		}
		return expression instanceof FieldAccessExpr access && safeIterable(access.getScope());
	}

	private static String indentLikeOriginal(InspectionContext context, ForStmt loop, String replacement) {
		String indent = " ".repeat(Math.max(0, loop.getBegin().orElseThrow().column - 1));
		return LineEndingSupport.indentLikeSource(replacement, context.editor().source(), indent);
	}

	private static boolean hasComment(String source) {
		return source.contains("//") || source.contains("/*");
	}

	private record Candidate(ForStmt loop, VariableDeclarationExpr itemDeclaration, Expression iterable,
			BlockStmt body) {
	}

}

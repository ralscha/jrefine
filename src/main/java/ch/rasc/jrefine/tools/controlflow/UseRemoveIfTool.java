package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;

/** Replaces canonical Iterator.remove() filtering loops with Collection.removeIf(). */
public final class UseRemoveIfTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "Queue", "Deque",
			"ArrayList", "LinkedList", "Vector", "HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque",
			"CopyOnWriteArrayList");

	@Override
	public String id() {
		return "use-remove-if";
	}

	@Override
	public String description() {
		return "Replace iterator filtering loops with Collection.removeIf()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(ForStmt.class)
			.stream()
			.map(loop -> candidate(context, loop))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.loop(), "Replace filtering loop with Collection.removeIf()"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.loop().getRange().orElseThrow(),
							context.editor().text(candidate.collection()) + ".removeIf(" + candidate.itemName() + " -> "
									+ context.editor().text(candidate.condition()) + ");");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ForStmt loop) {
		if (loop.getInitialization().size() != 1
				|| !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || loop.getCompare().isEmpty()
				|| !(loop.getCompare().orElseThrow() instanceof MethodCallExpr hasNext)
				|| !"hasNext".equals(hasNext.getNameAsString()) || !hasNext.getArguments().isEmpty()
				|| !(hasNext.getScope().orElse(null) instanceof NameExpr compared) || !loop.getUpdate().isEmpty()
				|| !(loop.getBody() instanceof BlockStmt body) || body.getStatements().size() != 2
				|| !(body.getStatement(0) instanceof ExpressionStmt itemStatement)
				|| !(itemStatement.getExpression() instanceof VariableDeclarationExpr itemDeclaration)
				|| itemDeclaration.getVariables().size() != 1 || !(body.getStatement(1) instanceof IfStmt conditional)
				|| conditional.getElseStmt().isPresent() || AstSupport.hasComment(context, loop)) {
			return Optional.empty();
		}
		VariableDeclarator iterator = declaration.getVariable(0);
		if (!compared.getNameAsString().equals(iterator.getNameAsString())
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
		Expression removal = singleExpression(conditional.getThenStmt());
		if (!(removal instanceof MethodCallExpr remove) || !"remove".equals(remove.getNameAsString())
				|| !remove.getArguments().isEmpty()
				|| !(remove.getScope().orElse(null) instanceof NameExpr removeIterator)
				|| !removeIterator.getNameAsString().equals(iterator.getNameAsString())) {
			return Optional.empty();
		}
		if (conditional.getCondition()
			.findAll(NameExpr.class)
			.stream()
			.anyMatch(name -> name.getNameAsString().equals(iterator.getNameAsString()))) {
			return Optional.empty();
		}
		Expression collection = iteratorCall.getScope().orElseThrow();
		if (TypeLookup.visibleType(context.compilationUnit(), collection, loop)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, COLLECTION_TYPES))
			.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(loop, collection, item.getNameAsString(), conditional.getCondition()));
	}

	private static Expression singleExpression(Statement statement) {
		if (statement instanceof ExpressionStmt expression) {
			return expression.getExpression();
		}
		if (statement instanceof BlockStmt block && block.getStatements().size() == 1
				&& block.getStatement(0) instanceof ExpressionStmt expression) {
			return expression.getExpression();
		}
		return null;
	}

	private record Candidate(ForStmt loop, Expression collection, String itemName, Expression condition) {
	}

}

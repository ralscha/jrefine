package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.List;

/** Replaces canonical indexed List.set() transformation loops with List.replaceAll(). */
public final class UseListReplaceAllTool implements InspectionTool {

	private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList", "Vector", "Stack",
			"CopyOnWriteArrayList");

	@Override
	public String id() {
		return "use-list-replace-all";
	}

	@Override
	public String description() {
		return "Replace indexed transformation loops with List.replaceAll()";
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
			findings.add(Finding.at(candidate.loop(), "Replace transformation loop with List.replaceAll()"));
			if (applyFixes) {
				String valueName = candidate.replacement()
					.findAll(NameExpr.class)
					.stream()
					.anyMatch(name -> "value".equals(name.getNameAsString())) ? "element" : "value";
				String transformed = context.editor().text(candidate.replacement());
				for (MethodCallExpr get : candidate.getCalls()) {
					transformed = transformed.replace(context.editor().text(get), valueName);
				}
				context.editor()
					.replace(candidate.loop().getRange().orElseThrow(), context.editor().text(candidate.list())
							+ ".replaceAll(" + valueName + " -> " + transformed + ");");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ForStmt loop) {
		if (loop.getInitialization().size() != 1
				|| !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || loop.getCompare().isEmpty()
				|| !(loop.getCompare().orElseThrow() instanceof BinaryExpr compare)
				|| compare.getOperator() != BinaryExpr.Operator.LESS || loop.getUpdate().size() != 1
				|| !(loop.getUpdate().get(0) instanceof UnaryExpr update) || !(loop.getBody() instanceof BlockStmt body)
				|| body.getStatements().size() != 1 || !(body.getStatement(0) instanceof ExpressionStmt statement)
				|| !(statement.getExpression() instanceof MethodCallExpr set) || !"set".equals(set.getNameAsString())
				|| set.getScope().isEmpty() || set.getArguments().size() != 2 || AstSupport.hasComment(context, loop)) {
			return Optional.empty();
		}
		VariableDeclarator index = declaration.getVariable(0);
		if (!index.getType().isPrimitiveType() || !"int".equals(index.getType().asString())
				|| index.getInitializer().filter(UseListReplaceAllTool::zero).isEmpty()
				|| !(compare.getLeft() instanceof NameExpr compared)
				|| !compared.getNameAsString().equals(index.getNameAsString())
				|| !(compare.getRight() instanceof MethodCallExpr size) || !"size".equals(size.getNameAsString())
				|| !size.getArguments().isEmpty() || size.getScope().isEmpty()
				|| !size.getScope().orElseThrow().equals(set.getScope().orElseThrow())
				|| !increment(update, index.getNameAsString())
				|| !isIndex(set.getArgument(0), index.getNameAsString())) {
			return Optional.empty();
		}
		Expression list = set.getScope().orElseThrow();
		if (TypeLookup.visibleType(context.compilationUnit(), list, loop)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, LIST_TYPES))
			.isEmpty()) {
			return Optional.empty();
		}
		Expression replacement = set.getArgument(1);
		List<MethodCallExpr> gets = replacement.findAll(MethodCallExpr.class)
			.stream()
			.filter(get -> "get".equals(get.getNameAsString()) && get.getArguments().size() == 1
					&& get.getScope().filter(list::equals).isPresent()
					&& isIndex(get.getArgument(0), index.getNameAsString()))
			.toList();
		if (gets.isEmpty()) {
			return Optional.empty();
		}
		if (replacement.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(index.getNameAsString()))
			.anyMatch(name -> gets.stream().noneMatch(get -> get.isAncestorOf(name)))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(loop, list, replacement, gets));
	}

	private static boolean zero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private static boolean isIndex(Expression expression, String name) {
		return expression instanceof NameExpr index && index.getNameAsString().equals(name);
	}

	private static boolean increment(UnaryExpr expression, String name) {
		return (expression.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
				|| expression.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT)
				&& isIndex(expression.getExpression(), name);
	}

	private record Candidate(ForStmt loop, Expression list, Expression replacement, List<MethodCallExpr> getCalls) {
	}

}

package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Replaces canonical constant-value array initialization loops with Arrays.fill(). */
public final class UseArrayFillTool implements InspectionTool {

	@Override
	public String id() {
		return "use-array-fill";
	}

	@Override
	public String description() {
		return "Replace explicit array filling with Arrays.fill()";
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
		String arrays = candidates.isEmpty() ? "Arrays"
				: ImportSupport.useType(context, "java.util.Arrays", applyFixes);
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.loop(), "Replace explicit array filling with Arrays.fill()"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.loop().getRange().orElseThrow(),
							arrays + ".fill(" + context.editor().text(candidate.array()) + ", "
									+ context.editor().text(candidate.value()) + ");");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ForStmt loop) {
		if (AstSupport.hasComment(context, loop) || loop.getInitialization().size() != 1
				|| !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || loop.getCompare().isEmpty()
				|| !(loop.getCompare().orElseThrow() instanceof BinaryExpr compare)
				|| compare.getOperator() != BinaryExpr.Operator.LESS || loop.getUpdate().size() != 1
				|| !(loop.getUpdate().get(0) instanceof UnaryExpr update)) {
			return Optional.empty();
		}
		VariableDeclarator index = declaration.getVariable(0);
		if (!index.getType().isPrimitiveType() || !"int".equals(index.getType().asString())
				|| index.getInitializer().filter(UseArrayFillTool::zero).isEmpty()
				|| !(compare.getLeft() instanceof NameExpr compared)
				|| !compared.getNameAsString().equals(index.getNameAsString())
				|| !increment(update, index.getNameAsString())
				|| !(compare.getRight() instanceof FieldAccessExpr length) || !"length".equals(length.getNameAsString())
				|| !stable(length.getScope())) {
			return Optional.empty();
		}
		Statement statement = singleStatement(loop.getBody());
		if (!(statement instanceof ExpressionStmt expressionStatement)
				|| !(expressionStatement.getExpression() instanceof AssignExpr assignment)
				|| assignment.getOperator() != AssignExpr.Operator.ASSIGN
				|| !(assignment.getTarget() instanceof ArrayAccessExpr access)
				|| !access.getName().equals(length.getScope()) || !(access.getIndex() instanceof NameExpr usedIndex)
				|| !usedIndex.getNameAsString().equals(index.getNameAsString())) {
			return Optional.empty();
		}
		Expression value = assignment.getValue();
		if (!stableValue(value) || value.findAll(NameExpr.class)
			.stream()
			.anyMatch(name -> name.getNameAsString().equals(index.getNameAsString()))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(loop, length.getScope(), value));
	}

	private static Statement singleStatement(Statement statement) {
		if (statement instanceof BlockStmt block) {
			return block.getStatements().size() == 1 ? block.getStatement(0) : null;
		}
		return statement;
	}

	private static boolean zero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private static boolean increment(UnaryExpr expression, String name) {
		return (expression.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
				|| expression.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT)
				&& expression.getExpression() instanceof NameExpr index && index.getNameAsString().equals(name);
	}

	private static boolean stableValue(Expression expression) {
		if (expression.isLiteralExpr() || expression.isNameExpr() || expression.isThisExpr()) {
			return true;
		}
		return expression instanceof FieldAccessExpr access && stable(access);
	}

	private static boolean stable(Expression expression) {
		if (expression.isNameExpr() || expression.isThisExpr() || expression.isSuperExpr()) {
			return true;
		}
		return expression instanceof FieldAccessExpr access && stable(access.getScope());
	}

	private record Candidate(ForStmt loop, Expression array, Expression value) {
	}

}

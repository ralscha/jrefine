package ch.rasc.jrefine.tools.controlflow;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Unwraps an array loop guarded by a non-empty length test. */
public final class RemoveRedundantArrayLengthCheckTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-array-length-check";
	}

	@Override
	public String description() {
		return "Remove non-empty array checks that only guard iteration";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(IfStmt.class)
			.stream()
			.map(statement -> candidate(context, statement))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.statement(), "Remove redundant array length check"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.statement().getRange().orElseThrow(), context.editor().text(candidate.loop()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, IfStmt statement) {
		if (statement.getElseStmt().isPresent() || AstSupport.hasComment(context, statement)) {
			return Optional.empty();
		}
		Statement loop = onlyStatement(statement.getThenStmt());
		if (!(loop instanceof ForEachStmt forEach)) {
			return Optional.empty();
		}
		Expression checked = nonEmptyArray(statement.getCondition()).orElse(null);
		if (checked == null || !checked.equals(forEach.getIterable())) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(statement, forEach));
	}

	private static Statement onlyStatement(Statement statement) {
		if (statement instanceof BlockStmt block && block.getStatements().size() == 1) {
			return block.getStatement(0);
		}
		return statement;
	}

	private static Optional<Expression> nonEmptyArray(Expression condition) {
		if (!(condition instanceof BinaryExpr binary)) {
			return Optional.empty();
		}
		if (length(binary.getLeft()).isPresent() && zero(binary.getRight())
				&& Set.of(BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.GREATER).contains(binary.getOperator())) {
			return length(binary.getLeft());
		}
		if (zero(binary.getLeft()) && length(binary.getRight()).isPresent()
				&& Set.of(BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.LESS).contains(binary.getOperator())) {
			return length(binary.getRight());
		}
		return Optional.empty();
	}

	private static Optional<Expression> length(Expression expression) {
		return expression instanceof FieldAccessExpr access && "length".equals(access.getNameAsString())
				? Optional.of(access.getScope()) : Optional.empty();
	}

	private static boolean zero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private record Candidate(IfStmt statement, ForEachStmt loop) {
	}

}

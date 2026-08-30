package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Replaces canonical StringBuilder append loops with String.repeat(). */
public final class UseStringRepeatTool implements InspectionTool {

	@Override
	public String id() {
		return "use-string-repeat";
	}

	@Override
	public int minimumJavaVersion() {
		return 11;
	}

	@Override
	public String description() {
		return "Replace repeated string append loops with repeat()";
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
			findings.add(Finding.at(candidate.loop(), "Replace append loop with String.repeat()"));
			if (applyFixes) {
				String value = context.editor().text(candidate.value());
				String repeated = candidate.value() instanceof StringLiteralExpr ? value
						: "String.valueOf(" + value + ")";
				String count = context.editor().text(candidate.count());
				if (!(candidate.count() instanceof IntegerLiteralExpr literal) || literal.asNumber().intValue() < 0) {
					count = "Math.max(0, " + count + ")";
				}
				context.editor()
					.replace(candidate.loop().getRange().orElseThrow(), context.editor().text(candidate.builder())
							+ ".append(" + repeated + ".repeat(" + count + "));");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ForStmt loop) {
		if (loop.getInitialization().size() != 1
				|| !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || loop.getCompare().isEmpty()
				|| !(loop.getCompare().orElseThrow() instanceof BinaryExpr compare) || loop.getUpdate().size() != 1
				|| !(loop.getUpdate().get(0) instanceof UnaryExpr update) || AstSupport.hasComment(context, loop)) {
			return Optional.empty();
		}
		VariableDeclarator index = declaration.getVariable(0);
		if (!index.getType().isPrimitiveType() || !"int".equals(index.getType().asString())
				|| index.getInitializer().filter(UseStringRepeatTool::zero).isEmpty()
				|| !increment(update, index.getNameAsString())) {
			return Optional.empty();
		}
		Expression count = count(compare, index.getNameAsString()).orElse(null);
		Statement statement = singleStatement(loop.getBody()).orElse(null);
		if (count == null || !(statement instanceof ExpressionStmt expressionStatement)
				|| !(expressionStatement.getExpression() instanceof MethodCallExpr append)
				|| !"append".equals(append.getNameAsString()) || append.getArguments().size() != 1
				|| !(append.getScope().orElse(null) instanceof NameExpr builder)) {
			return Optional.empty();
		}
		if (NumericSupport.typeOf(context, count, loop).filter("int"::equals).isEmpty() || !stable(context, count, loop)
				|| !stable(context, append.getArgument(0), loop)
				|| append.findAll(NameExpr.class)
					.stream()
					.anyMatch(name -> name.getNameAsString().equals(index.getNameAsString()))) {
			return Optional.empty();
		}
		String builderType = TypeLookup.visibleType(context.compilationUnit(), builder, loop).orElse("");
		if (!TypeLookup.isKnownJavaLangType(context.compilationUnit(), builderType,
				Set.of("StringBuilder", "StringBuffer"))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(loop, builder, append.getArgument(0), count));
	}

	private static Optional<Statement> singleStatement(Statement body) {
		if (body instanceof BlockStmt block) {
			return block.getStatements().size() == 1 ? Optional.of(block.getStatement(0)) : Optional.empty();
		}
		return Optional.of(body);
	}

	private static Optional<Expression> count(BinaryExpr compare, String index) {
		if (compare.getOperator() == BinaryExpr.Operator.LESS && compare.getLeft() instanceof NameExpr name
				&& name.getNameAsString().equals(index)) {
			return Optional.of(compare.getRight());
		}
		if (compare.getOperator() == BinaryExpr.Operator.GREATER && compare.getRight() instanceof NameExpr name
				&& name.getNameAsString().equals(index)) {
			return Optional.of(compare.getLeft());
		}
		return Optional.empty();
	}

	private static boolean stable(InspectionContext context, Expression expression, ForStmt use) {
		if (expression instanceof IntegerLiteralExpr || expression instanceof StringLiteralExpr
				|| expression instanceof CharLiteralExpr) {
			return true;
		}
		if (!(expression instanceof NameExpr name)
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), name.getNameAsString(), use)) {
			return false;
		}
		String type = TypeLookup.visibleType(context.compilationUnit(), expression, use).orElse("");
		return !type.isBlank();
	}

	private static boolean zero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private static boolean increment(UnaryExpr expression, String name) {
		return (expression.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
				|| expression.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT)
				&& expression.getExpression() instanceof NameExpr value && value.getNameAsString().equals(name);
	}

	private record Candidate(ForStmt loop, Expression builder, Expression value, Expression count) {
	}

}

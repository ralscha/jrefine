package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Promotes integer multiplication and shifts before their result is widened to long. */
public final class PromoteIntegerOperationToLongTool implements InspectionTool {

	@Override
	public String id() {
		return "promote-integer-operation-to-long";
	}

	@Override
	public String description() {
		return "Perform integer multiplication and shifts as long operations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(operation -> candidate(context, operation))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.operation(), "Promote the integer operation to long"));
			if (applyFixes) {
				Expression operand = candidate.operand();
				String replacement = operand instanceof IntegerLiteralExpr ? context.editor().text(operand) + "L"
						: "(long) " + context.editor().text(operand);
				context.editor().replace(operand.getRange().orElseThrow(), replacement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr operation) {
		if (operation.getOperator() != BinaryExpr.Operator.MULTIPLY
				&& operation.getOperator() != BinaryExpr.Operator.LEFT_SHIFT
				|| AstSupport.hasComment(context, operation)
				|| NumericSupport.typeOf(context, operation, operation).filter("int"::equals).isEmpty()
				|| !longContext(context, operation)) {
			return Optional.empty();
		}
		Expression operand = operation.getOperator() == BinaryExpr.Operator.LEFT_SHIFT ? operation.getLeft()
				: leftmostMultiplicand(operation);
		return Optional.of(new Candidate(operation, operand));
	}

	private static Expression leftmostMultiplicand(BinaryExpr expression) {
		Expression current = expression.getLeft();
		while (current instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.MULTIPLY) {
			current = binary.getLeft();
		}
		return current;
	}

	private static boolean longContext(InspectionContext context, Expression expression) {
		Node current = expression;
		while (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed
				&& enclosed.getInner() == current) {
			current = enclosed;
		}
		Node parent = current.getParentNode().orElse(null);
		if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == current) {
			return variable.getType().isPrimitiveType() && "long".equals(variable.getType().asString());
		}
		if (parent instanceof AssignExpr assignment && assignment.getValue() == current) {
			return NumericSupport.typeOf(context, assignment.getTarget(), assignment)
				.filter("long"::equals)
				.isPresent();
		}
		if (parent instanceof ReturnStmt statement && statement.getExpression().orElse(null) == current) {
			return AstSupport.ancestor(statement, MethodDeclaration.class)
				.filter(method -> method.getType().isPrimitiveType() && "long".equals(method.getType().asString()))
				.isPresent();
		}
		return false;
	}

	private record Candidate(BinaryExpr operation, Expression operand) {
	}

}

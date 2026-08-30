package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** Reports implicit numeric conversions whose intent or result may be surprising. */
public final class ReportNumericConversionIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-numeric-conversion-issues";
	}

	@Override
	public String description() {
		return "Report suspicious char arithmetic and implicit numeric conversions";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			String left = type(context, binary.getLeft(), binary);
			String right = type(context, binary.getRight(), binary);
			if ((binary.getOperator() == BinaryExpr.Operator.PLUS || binary.getOperator() == BinaryExpr.Operator.MINUS)
					&& numeric(left) && numeric(right) && ("char".equals(left) || "char".equals(right))) {
				findings.add(Finding.at(binary, "'char' expression is used in arithmetic"));
			}
			if ((binary.getOperator() == BinaryExpr.Operator.EQUALS
					|| binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
					&& ("short".equals(left) && "char".equals(right) || "char".equals(left) && "short".equals(right))) {
				findings.add(Finding.at(binary, "Comparison mixes short and char values"));
			}
			if (binary.getOperator() == BinaryExpr.Operator.DIVIDE && NumericSupport.isIntegral(left)
					&& NumericSupport.isIntegral(right) && floatingContext(context, binary)) {
				findings.add(Finding.at(binary, "Integer division result is used in a floating-point context"));
			}
		}

		context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.forEach(variable -> variable.getInitializer()
				.ifPresent(value -> implicitConversion(context, value,
						variable.getType().isPrimitiveType() ? variable.getType().asString() : "", findings)));
		context.compilationUnit()
			.findAll(AssignExpr.class)
			.stream()
			.filter(assignment -> assignment.getOperator() == AssignExpr.Operator.ASSIGN)
			.forEach(assignment -> implicitConversion(context, assignment.getValue(),
					type(context, assignment.getTarget(), assignment), findings));
		context.compilationUnit()
			.findAll(ReturnStmt.class)
			.forEach(statement -> statement.getExpression()
				.ifPresent(value -> AstSupport.ancestor(statement, MethodDeclaration.class)
					.filter(method -> method.getType().isPrimitiveType())
					.ifPresent(method -> implicitConversion(context, value, method.getType().asString(), findings))));

		for (IntegerLiteralExpr literal : context.compilationUnit().findAll(IntegerLiteralExpr.class)) {
			String spelling = context.editor().text(literal).replace("_", "");
			if (spelling.matches("0[xX][89a-fA-F][0-9a-fA-F]*") && longContext(context, literal)) {
				findings.add(Finding.at(literal, "Negative int hexadecimal constant is widened in a long context"));
			}
		}
		for (AssignExpr assignment : context.compilationUnit().findAll(AssignExpr.class)) {
			if (assignment.getOperator() == AssignExpr.Operator.ASSIGN) {
				continue;
			}
			String target = type(context, assignment.getTarget(), assignment);
			String value = type(context, assignment.getValue(), assignment);
			if (numeric(target) && numeric(value) && !NumericSupport.canWiden(value, target)) {
				findings.add(Finding.at(assignment, "Compound assignment performs a possibly lossy implicit cast"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void implicitConversion(InspectionContext context, Expression expression, String target,
			List<Finding> findings) {
		if (expression instanceof CastExpr || !numeric(target)) {
			return;
		}
		String source = type(context, expression, expression);
		if (numeric(source) && !source.equals(target) && NumericSupport.canWiden(source, target)) {
			findings.add(Finding.at(expression, "Implicit numeric conversion from " + source + " to " + target));
		}
	}

	private static boolean floatingContext(InspectionContext context, Expression expression) {
		Node current = expression;
		while (true) {
			if (current instanceof Expression currentExpression
					&& NumericSupport.expectedType(context, currentExpression)
						.filter(NumericSupport::isFloatingPoint)
						.isPresent()) {
				return true;
			}
			if (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed) {
				current = enclosed;
			}
			else if (current.getParentNode().orElse(null) instanceof BinaryExpr parent) {
				if (NumericSupport.typeOf(context, parent, parent)
					.filter(NumericSupport::isFloatingPoint)
					.isPresent()) {
					return true;
				}
				current = parent;
			}
			else {
				return false;
			}
		}
	}

	private static boolean longContext(InspectionContext context, Expression expression) {
		Node current = expression;
		while (true) {
			if (current instanceof Expression currentExpression
					&& NumericSupport.expectedType(context, currentExpression).filter("long"::equals).isPresent()) {
				return true;
			}
			if (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed) {
				current = enclosed;
			}
			else if (current.getParentNode().orElse(null) instanceof BinaryExpr binary) {
				if (NumericSupport.typeOf(context, binary, binary).filter("long"::equals).isPresent()) {
					return true;
				}
				current = binary;
			}
			else {
				return false;
			}
		}
	}

	private static String type(InspectionContext context, Expression expression, Node use) {
		return NumericSupport.typeOf(context, expression, use).map(NumericSupport::simpleName).orElse("");
	}

	private static boolean numeric(String type) {
		return NumericSupport.isNumeric(type);
	}

}

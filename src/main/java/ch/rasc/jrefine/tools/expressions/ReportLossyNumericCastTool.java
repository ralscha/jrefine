package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.List;

/** Reports explicit primitive conversions that can discard magnitude or precision. */
public final class ReportLossyNumericCastTool implements InspectionTool {

	@Override
	public String id() {
		return "report-lossy-numeric-cast";
	}

	@Override
	public String description() {
		return "Report numeric casts that may lose precision";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Finding> findings = context.compilationUnit()
			.findAll(CastExpr.class)
			.stream()
			.filter(cast -> lossy(context, cast))
			.map(cast -> Finding.at(cast, "Numeric cast may lose precision"))
			.toList();
		// A lossy cast has no generally safe automatic replacement.
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean lossy(InspectionContext context, CastExpr cast) {
		if (!cast.getType().isPrimitiveType() || cast.getExpression() instanceof LiteralExpr
				|| intentionalFloatRoundTrip(cast) || maskedIntegralConversion(cast) || widenedCharLoopValue(cast)) {
			return false;
		}
		String source = NumericSupport.typeOf(context, cast.getExpression(), cast)
			.map(NumericSupport::simpleName)
			.orElse(null);
		String target = NumericSupport.simpleName(cast.getType().asString());
		if (source == null || source.equals(target) || !NumericSupport.isNumeric(source)
				|| !NumericSupport.isNumeric(target)) {
			return false;
		}
		return switch (source) {
			case "double" -> !"double".equals(target);
			case "float" -> NumericSupport.isIntegral(target);
			case "long" -> !"long".equals(target);
			case "int" -> java.util.Set.of("byte", "short", "char", "float").contains(target);
			case "short" -> java.util.Set.of("byte", "char").contains(target);
			case "char" -> java.util.Set.of("byte", "short").contains(target);
			case "byte" -> "char".equals(target);
			default -> false;
		};
	}

	private static boolean intentionalFloatRoundTrip(CastExpr cast) {
		if (!"float".equals(cast.getType().asString())) {
			return false;
		}
		Node parent = cast.getParentNode().orElse(null);
		while (parent instanceof EnclosedExpr) {
			parent = parent.getParentNode().orElse(null);
		}
		return parent instanceof CastExpr outer && "double".equals(outer.getType().asString());
	}

	private static boolean maskedIntegralConversion(CastExpr cast) {
		Expression value = unwrap(cast.getExpression());
		if (!(value instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.BINARY_AND) {
			return false;
		}
		long mask = integer(binary.getLeft());
		if (mask < 0) {
			mask = integer(binary.getRight());
		}
		long maximum = switch (cast.getType().asString()) {
			case "byte" -> 0xffL;
			case "short", "char" -> 0xffffL;
			case "int" -> 0xffff_ffffL;
			default -> -1L;
		};
		return mask >= 0 && mask <= maximum;
	}

	private static boolean widenedCharLoopValue(CastExpr cast) {
		if (!"char".equals(cast.getType().asString()) || !(cast.getExpression() instanceof NameExpr name)) {
			return false;
		}
		return AstSupport.ancestor(cast, ForEachStmt.class)
			.filter(loop -> loop.getVariable().getVariables().size() == 1
					&& loop.getVariable().getVariable(0).getNameAsString().equals(name.getNameAsString())
					&& loop.getIterable() instanceof MethodCallExpr call && "toCharArray".equals(call.getNameAsString())
					&& call.getArguments().isEmpty())
			.isPresent();
	}

	private static Expression unwrap(Expression expression) {
		Expression value = expression;
		while (value instanceof EnclosedExpr enclosed) {
			value = enclosed.getInner();
		}
		return value;
	}

	private static long integer(Expression expression) {
		if (expression instanceof IntegerLiteralExpr literal) {
			return literal.asNumber().longValue();
		}
		if (expression instanceof LongLiteralExpr literal) {
			return literal.asNumber().longValue();
		}
		return -1L;
	}

}

package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Modernizes long literal suffixes and replaces casts of integer literals. */
public final class UseLongLiteralTool implements InspectionTool {

	@Override
	public String id() {
		return "use-long-literal";
	}

	@Override
	public String description() {
		return "Use clear long literal suffixes";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<CastExpr> candidates = context.compilationUnit()
			.findAll(CastExpr.class)
			.stream()
			.filter(cast -> cast.getType().isPrimitiveType() && "long".equals(cast.getType().asString())
					&& cast.getExpression() instanceof IntegerLiteralExpr && !AstSupport.hasComment(context, cast))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (CastExpr cast : candidates) {
			findings.add(Finding.at(cast, "Use a long literal instead of a cast"));
			if (applyFixes) {
				context.editor()
					.replace(cast.getRange().orElseThrow(), context.editor().text(cast.getExpression()) + "L");
			}
		}
		for (LongLiteralExpr literal : context.compilationUnit().findAll(LongLiteralExpr.class)) {
			String source = context.editor().text(literal);
			if (!source.endsWith("l")) {
				continue;
			}
			findings.add(Finding.at(literal, "Use uppercase 'L' for a long literal suffix"));
			if (applyFixes) {
				context.editor()
					.replace(literal.getRange().orElseThrow(), source.substring(0, source.length() - 1) + "L");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

}

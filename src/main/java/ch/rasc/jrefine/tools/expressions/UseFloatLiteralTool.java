package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Replaces casts of double literals with float literal suffixes. */
public final class UseFloatLiteralTool implements InspectionTool {

	@Override
	public String id() {
		return "use-float-literal";
	}

	@Override
	public String description() {
		return "Replace casts of double literals with float suffixes";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<CastExpr> candidates = context.compilationUnit()
			.findAll(CastExpr.class)
			.stream()
			.filter(cast -> cast.getType().isPrimitiveType() && "float".equals(cast.getType().asString())
					&& cast.getExpression() instanceof DoubleLiteralExpr && !AstSupport.hasComment(context, cast))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (CastExpr cast : candidates) {
			findings.add(Finding.at(cast, "Use a float literal instead of a cast"));
			if (applyFixes) {
				String spelling = context.editor().text(cast.getExpression());
				if (spelling.endsWith("d") || spelling.endsWith("D")) {
					spelling = spelling.substring(0, spelling.length() - 1);
				}
				context.editor().replace(cast.getRange().orElseThrow(), spelling + "F");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

}

package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Removes numeric casts already supplied by variable initialization conversion. */
public final class RemoveUnnecessaryNumericCastTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-numeric-cast";
	}

	@Override
	public String description() {
		return "Remove explicit numeric casts inserted implicitly by Java";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<CastExpr> all = context.compilationUnit()
			.findAll(CastExpr.class)
			.stream()
			.filter(cast -> unnecessary(context, cast) && !AstSupport.hasComment(context, cast))
			.toList();
		List<CastExpr> candidates = all.stream()
			.filter(cast -> all.stream().noneMatch(other -> other != cast && other.isAncestorOf(cast)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (CastExpr cast : candidates) {
			findings.add(Finding.at(cast, "Remove unnecessary numeric cast"));
			if (applyFixes) {
				context.editor().replace(cast.getRange().orElseThrow(), context.editor().text(cast.getExpression()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean unnecessary(InspectionContext context, CastExpr cast) {
		if (!cast.getType().isPrimitiveType() || !NumericSupport.isNumeric(cast.getType().asString())) {
			return false;
		}
		String source = NumericSupport.typeOf(context, cast.getExpression(), cast).orElse(null);
		if (source == null || !NumericSupport.isNumeric(source)) {
			return false;
		}
		String target = cast.getType().asString();
		if (NumericSupport.simpleName(source).equals(target)) {
			return true;
		}
		if ("long".equals(target) && cast.getExpression() instanceof IntegerLiteralExpr) {
			return false;
		}
		return target.equals(expectedType(context, cast)) && NumericSupport.canWiden(source, target);
	}

	private static String expectedType(InspectionContext context, CastExpr cast) {
		Node current = cast;
		while (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed
				&& enclosed.getInner() == current) {
			current = enclosed;
		}
		Node parent = current.getParentNode().orElse(null);
		if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == current
				&& variable.getType().isPrimitiveType()) {
			return variable.getType().asString();
		}
		if (parent instanceof AssignExpr assignment && assignment.getValue() == current) {
			return NumericSupport.typeOf(context, assignment.getTarget(), assignment).orElse("");
		}
		if (parent instanceof ReturnStmt statement && statement.getExpression().orElse(null) == current) {
			return AstSupport.ancestor(statement, MethodDeclaration.class)
				.filter(method -> method.getType().isPrimitiveType())
				.map(method -> method.getType().asString())
				.orElse("");
		}
		return "";
	}

}

package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Removes reference casts already supplied by an assignment or return conversion. */
public final class RemoveRedundantTypeCastTool implements InspectionTool {

	private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "short", "char", "int", "long", "float",
			"double");

	@Override
	public String id() {
		return "remove-redundant-type-cast";
	}

	@Override
	public String description() {
		return "Remove unnecessary reference type casts";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<CastExpr> candidates = context.compilationUnit()
			.findAll(CastExpr.class)
			.stream()
			.filter(cast -> candidate(context, cast))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (CastExpr cast : candidates) {
			findings.add(Finding.at(cast, "Remove redundant reference type cast"));
			if (applyFixes) {
				context.editor().replace(cast.getRange().orElseThrow(), context.editor().text(cast.getExpression()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean candidate(InspectionContext context, CastExpr cast) {
		if (cast.getType().isPrimitiveType() || AstSupport.hasComment(context, cast)) {
			return false;
		}
		String expected = expectedType(context, cast).map(ExpressionToolSupport::simpleName).orElse(null);
		String target = ExpressionToolSupport.simpleName(cast.getType().asString());
		if (expected == null || !expected.equals(target)) {
			return false;
		}
		if (cast.getExpression() instanceof NullLiteralExpr) {
			return true;
		}
		String source = ExpressionToolSupport.visibleSimpleType(context, cast.getExpression(), cast).orElse(null);
		if (source == null || PRIMITIVES.contains(source)) {
			return false;
		}
		return source.equals(target) || "Object".equals(target) && ExpressionToolSupport
			.knownType(context.compilationUnit(), cast.getType().asString(), "java.lang", Set.of("Object"));
	}

	private static Optional<String> expectedType(InspectionContext context, CastExpr cast) {
		Node current = cast;
		while (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed
				&& enclosed.getInner() == current) {
			current = enclosed;
		}
		Node parent = current.getParentNode().orElse(null);
		if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == current) {
			return Optional.of(variable.getType().asString());
		}
		if (parent instanceof AssignExpr assignment && assignment.getValue() == current) {
			return ch.rasc.jrefine.analysis.TypeLookup.visibleType(context.compilationUnit(), assignment.getTarget(),
					assignment);
		}
		if (parent instanceof ReturnStmt statement && statement.getExpression().orElse(null) == current) {
			return AstSupport.ancestor(statement, MethodDeclaration.class).map(method -> method.getType().asString());
		}
		return Optional.empty();
	}

}

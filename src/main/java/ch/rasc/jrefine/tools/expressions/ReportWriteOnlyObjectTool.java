package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.List;
import java.util.Set;

/**
 * Reports locally allocated atomic holders that are written but never queried or exposed.
 */
public final class ReportWriteOnlyObjectTool implements PolicyInspectionTool {

	private static final Set<String> ATOMIC_TYPES = Set.of("AtomicReference", "AtomicInteger", "AtomicLong");

	private static final Set<String> WRITE_METHODS = Set.of("set", "lazySet", "setPlain", "setOpaque", "setRelease");

	@Override
	public String id() {
		return "report-write-only-object";
	}

	@Override
	public String description() {
		return "Report local objects that are modified but never queried";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Finding> findings = context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> writeOnly(context, variable))
			.map(variable -> Finding.at(variable,
					"Object '" + variable.getNameAsString() + "' is modified but never queried"))
			.toList();
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean writeOnly(InspectionContext context, VariableDeclarator variable) {
		if (!(variable.getInitializer().orElse(null) instanceof ObjectCreationExpr creation)
				|| creation.getAnonymousClassBody().isPresent()
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), creation.getType().asString(),
						"java.util.concurrent.atomic", ATOMIC_TYPES)) {
			return false;
		}
		if (!variable.getType().isVarType() && !ExpressionToolSupport.simpleName(variable.getType().asString())
			.equals(ExpressionToolSupport.simpleName(creation.getType().asString()))) {
			return false;
		}
		BlockStmt block = AstSupport.ancestor(variable, BlockStmt.class).orElse(null);
		if (block == null || block.findAll(VariableDeclarator.class)
			.stream()
			.anyMatch(other -> other != variable && other.getNameAsString().equals(variable.getNameAsString())
					&& other.getBegin().orElseThrow().isAfter(variable.getBegin().orElseThrow()))) {
			return false;
		}
		List<NameExpr> uses = block.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(variable.getNameAsString()))
			.filter(name -> name.getBegin().orElseThrow().isAfter(variable.getBegin().orElseThrow()))
			.toList();
		return !uses.isEmpty() && uses.stream().allMatch(ReportWriteOnlyObjectTool::writeUse);
	}

	private static boolean writeUse(NameExpr name) {
		if (AstSupport.ancestor(name, LambdaExpr.class).isPresent()
				|| name.getParentNode().orElse(null) instanceof AssignExpr) {
			return false;
		}
		if (!(name.getParentNode().orElse(null) instanceof MethodCallExpr call) || call.getScope().orElse(null) != name
				|| !WRITE_METHODS.contains(call.getNameAsString())) {
			return false;
		}
		return call.getParentNode().orElse(null) instanceof ExpressionStmt;
	}

}

package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import java.util.List;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/**
 * Removes a bare return that is already implied at the end of a void method or
 * constructor.
 */
public final class RemoveUnnecessaryReturnTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-return";
	}

	@Override
	public String description() {
		return "Remove redundant final return statements from void methods and constructors";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ReturnStmt> candidates = context.compilationUnit()
			.findAll(ReturnStmt.class)
			.stream()
			.filter(statement -> statement.getExpression().isEmpty())
			.filter(RemoveUnnecessaryReturnTool::isFinalStatement)
			.filter(statement -> !hasComment(context.editor().text(statement)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (ReturnStmt statement : candidates) {
			findings.add(Finding.at(statement, "Remove unnecessary final return statement"));
			if (applyFixes) {
				context.editor().removeLine(statement);
				statement.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean isFinalStatement(ReturnStmt statement) {
		if (!(statement.getParentNode().orElse(null) instanceof BlockStmt block) || block.getStatements().isEmpty()
				|| block.getStatements().getLast().orElseThrow() != statement) {
			return false;
		}
		Node parent = block.getParentNode().orElse(null);
		return parent instanceof ConstructorDeclaration
				|| parent instanceof MethodDeclaration method && method.getType().isVoidType();
	}

	private static boolean hasComment(String source) {
		return source.contains("//") || source.contains("/*");
	}

}

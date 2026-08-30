package ch.rasc.jrefine.tools.controlflow;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.Optional;

/** Removes continue statements that are already implied at the end of a loop body. */
public final class RemoveUnnecessaryContinueTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-continue";
	}

	@Override
	public String description() {
		return "Remove final continue statements from loop bodies";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ContinueStmt> candidates = context.compilationUnit()
			.findAll(ContinueStmt.class)
			.stream()
			.filter(RemoveUnnecessaryContinueTool::finalInOwnLoop)
			.filter(statement -> !AstSupport.hasComment(context, statement))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (ContinueStmt statement : candidates) {
			findings.add(Finding.at(statement, "Remove unnecessary final continue statement"));
			if (applyFixes) {
				context.editor().removeLine(statement);
				statement.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean finalInOwnLoop(ContinueStmt statement) {
		if (!(statement.getParentNode().orElse(null) instanceof BlockStmt body) || body.getStatements().isEmpty()
				|| body.getStatements().getLast().orElseThrow() != statement) {
			return false;
		}
		Optional<Node> loop = SimplifyLabelsTool.nearestLoop(statement);
		if (loop.isEmpty() || loopBody(loop.orElseThrow()) != body) {
			return false;
		}
		if (statement.getLabel().isEmpty()) {
			return true;
		}
		return targetLabel(statement, statement.getLabel().orElseThrow().getIdentifier())
			.map(label -> label.getStatement() == loop.orElseThrow())
			.orElse(false);
	}

	private static Node loopBody(Node loop) {
		if (loop instanceof ForStmt statement) {
			return statement.getBody();
		}
		if (loop instanceof ForEachStmt statement) {
			return statement.getBody();
		}
		if (loop instanceof WhileStmt statement) {
			return statement.getBody();
		}
		return ((DoStmt) loop).getBody();
	}

	private static Optional<LabeledStmt> targetLabel(Node statement, String name) {
		Optional<Node> parent = statement.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof LabeledStmt label && label.getLabel().getIdentifier().equals(name)) {
				return java.util.Optional.of(label);
			}
			parent = value.getParentNode();
		}
		return java.util.Optional.empty();
	}

}

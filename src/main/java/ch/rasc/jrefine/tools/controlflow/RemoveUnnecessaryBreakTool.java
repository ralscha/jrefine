package ch.rasc.jrefine.tools.controlflow;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Removes breaks implied by the end of a switch rule or the final colon-style branch. */
public final class RemoveUnnecessaryBreakTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-break";
	}

	@Override
	public String description() {
		return "Remove break statements that cannot skip any code";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<BreakStmt> candidates = context.compilationUnit()
			.findAll(BreakStmt.class)
			.stream()
			.filter(RemoveUnnecessaryBreakTool::unnecessary)
			.filter(statement -> !AstSupport.hasComment(context, statement))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (BreakStmt statement : candidates) {
			findings.add(Finding.at(statement, "Remove unnecessary break statement"));
			if (applyFixes) {
				context.editor().removeLine(statement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean unnecessary(BreakStmt statement) {
		if (statement.getLabel().isPresent()
				|| !(nearestBreakTarget(statement).orElse(null) instanceof SwitchStmt target)) {
			return false;
		}
		if (statement.getParentNode().orElse(null) instanceof SwitchEntry entry) {
			return isLast(statement, entry) && isLastEntry(entry, target);
		}
		if (!(statement.getParentNode().orElse(null) instanceof BlockStmt block) || !isLast(statement, block)
				|| !(block.getParentNode().orElse(null) instanceof SwitchEntry entry)
				|| entry.getStatements().size() != 1 || entry.getStatement(0) != block) {
			return false;
		}
		return entry.getType() == SwitchEntry.Type.BLOCK || isLastEntry(entry, target);
	}

	private static boolean isLast(BreakStmt statement, SwitchEntry entry) {
		return !entry.getStatements().isEmpty() && entry.getStatements().getLast().orElseThrow() == statement;
	}

	private static boolean isLast(BreakStmt statement, BlockStmt block) {
		return !block.getStatements().isEmpty() && block.getStatements().getLast().orElseThrow() == statement;
	}

	private static boolean isLastEntry(SwitchEntry entry, SwitchStmt statement) {
		return !statement.getEntries().isEmpty() && statement.getEntries().getLast().orElseThrow() == entry;
	}

	private static Optional<Node> nearestBreakTarget(BreakStmt statement) {
		Optional<Node> parent = statement.getParentNode();
		while (parent.isPresent()) {
			Node node = parent.orElseThrow();
			if (node instanceof SwitchStmt || node instanceof ForStmt || node instanceof ForEachStmt
					|| node instanceof WhileStmt || node instanceof DoStmt) {
				return Optional.of(node);
			}
			parent = node.getParentNode();
		}
		return Optional.empty();
	}

}

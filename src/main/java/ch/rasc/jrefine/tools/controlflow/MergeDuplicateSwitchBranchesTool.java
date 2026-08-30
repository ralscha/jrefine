package ch.rasc.jrefine.tools.controlflow;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Merges switch labels whose branches are structurally identical. */
public final class MergeDuplicateSwitchBranchesTool implements InspectionTool {

	@Override
	public String id() {
		return "merge-duplicate-switch-branches";
	}

	@Override
	public String description() {
		return "Merge duplicate switch branches";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(SwitchStmt.class)
			.stream()
			.map(node -> candidate(context, node, node.getEntries()))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(SwitchExpr.class)
			.stream()
			.map(node -> candidate(context, node, node.getEntries()))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		List<Candidate> nonNested = candidates.stream()
			.filter(candidate -> candidates.stream()
				.noneMatch(other -> other != candidate && other.node().isAncestorOf(candidate.node())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : nonNested) {
			findings.add(Finding.at(candidate.entries().get(candidate.secondIndex()), "Merge duplicate switch branch"));
			if (applyFixes) {
				Node replacement = candidate.node().clone();
				NodeList<SwitchEntry> entries = entries(replacement);
				SwitchEntry first = entries.get(candidate.firstIndex());
				SwitchEntry second = entries.get(candidate.secondIndex());
				if (first.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
					first.getStatements().clear();
				}
				else {
					second.getLabels().forEach(label -> first.getLabels().add(label.clone()));
					entries.remove(candidate.secondIndex());
				}
				context.editor()
					.replace(candidate.node().getRange().orElseThrow(),
							indentLikeOriginal(context, candidate.node(), replacement.toString()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, Node node, NodeList<SwitchEntry> entries) {
		if (AstSupport.hasComment(context, node)) {
			return Optional.empty();
		}
		for (int left = 0; left < entries.size(); left++) {
			SwitchEntry first = entries.get(left);
			if (first.isDefault() || first.getLabels().isEmpty()) {
				continue;
			}
			for (int right = left + 1; right < entries.size(); right++) {
				SwitchEntry second = entries.get(right);
				if (second.isDefault() || second.getLabels().isEmpty() || first.getType() != second.getType()
						|| !first.getStatements().equals(second.getStatements())
						|| !first.getGuard().equals(second.getGuard())) {
					continue;
				}
				if (first.getType() == SwitchEntry.Type.STATEMENT_GROUP && right != left + 1) {
					continue;
				}
				if (first.getType() == SwitchEntry.Type.STATEMENT_GROUP && first.getStatements().isEmpty()) {
					continue;
				}
				return Optional.of(new Candidate(node, entries, left, right));
			}
		}
		return Optional.empty();
	}

	private static NodeList<SwitchEntry> entries(Node node) {
		if (node instanceof SwitchStmt statement) {
			return statement.getEntries();
		}
		return ((SwitchExpr) node).getEntries();
	}

	private static String indentLikeOriginal(InspectionContext context, Node node, String replacement) {
		String indent = " ".repeat(Math.max(0, node.getBegin().orElseThrow().column - 1));
		return LineEndingSupport.indentLikeSource(replacement, context.editor().source(), indent);
	}

	private record Candidate(Node node, NodeList<SwitchEntry> entries, int firstIndex, int secondIndex) {
	}

}

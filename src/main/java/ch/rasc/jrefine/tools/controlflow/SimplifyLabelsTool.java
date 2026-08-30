package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/** Removes unused labels and labels that do not change break/continue targets. */
public final class SimplifyLabelsTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-labels";
	}

	@Override
	public String description() {
		return "Remove unused labels and unnecessary break or continue labels";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<LabeledStmt, List<Node>> references = new IdentityHashMap<>();
		context.compilationUnit().findAll(LabeledStmt.class).forEach(label -> references.put(label, new ArrayList<>()));
		ArrayList<SimpleName> removableReferences = new ArrayList<>();

		for (BreakStmt statement : context.compilationUnit().findAll(BreakStmt.class)) {
			statement.getLabel().flatMap(label -> targetLabel(statement, label.getIdentifier())).ifPresent(target -> {
				references.get(target).add(statement);
				if (unnecessaryBreakLabel(statement, target)) {
					removableReferences.add(statement.getLabel().orElseThrow());
				}
			});
		}
		for (ContinueStmt statement : context.compilationUnit().findAll(ContinueStmt.class)) {
			statement.getLabel().flatMap(label -> targetLabel(statement, label.getIdentifier())).ifPresent(target -> {
				references.get(target).add(statement);
				if (unnecessaryContinueLabel(statement, target)) {
					removableReferences.add(statement.getLabel().orElseThrow());
				}
			});
		}

		List<LabeledStmt> removableLabels = references.entrySet()
			.stream()
			.filter(entry -> entry.getValue().isEmpty() || entry.getValue()
				.stream()
				.allMatch(reference -> referenceLabel(reference).filter(removableReferences::contains).isPresent()))
			.map(Map.Entry::getKey)
			.filter(label -> !hasCommentInPrefix(context, label))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (LabeledStmt label : removableLabels) {
			findings.add(Finding.at(label.getLabel(), "Remove unused label '" + label.getLabel() + "'"));
			if (applyFixes) {
				removeLabelPrefix(context, label);
			}
		}
		for (SimpleName label : removableReferences) {
			findings.add(Finding.at(label, "Remove unnecessary control-flow label '" + label + "'"));
			if (applyFixes) {
				context.editor().removeWithLeadingWhitespace(label.getRange().orElseThrow());
				label.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
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

	private static boolean unnecessaryBreakLabel(BreakStmt statement, LabeledStmt target) {
		return nearestBreakTarget(statement).map(node -> node == target.getStatement()).orElse(false);
	}

	private static boolean unnecessaryContinueLabel(ContinueStmt statement, LabeledStmt target) {
		return nearestLoop(statement).map(node -> node == target.getStatement()).orElse(false);
	}

	private static Optional<Node> nearestBreakTarget(Node statement) {
		Optional<Node> parent = statement.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (isLoop(value) || value instanceof SwitchStmt) {
				return java.util.Optional.of(value);
			}
			parent = value.getParentNode();
		}
		return java.util.Optional.empty();
	}

	static Optional<Node> nearestLoop(Node statement) {
		Optional<Node> parent = statement.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (isLoop(value)) {
				return java.util.Optional.of(value);
			}
			parent = value.getParentNode();
		}
		return java.util.Optional.empty();
	}

	private static boolean isLoop(Node node) {
		return node instanceof ForStmt || node instanceof ForEachStmt || node instanceof WhileStmt
				|| node instanceof DoStmt;
	}

	private static Optional<SimpleName> referenceLabel(Node reference) {
		if (reference instanceof BreakStmt statement) {
			return statement.getLabel();
		}
		return reference instanceof ContinueStmt statement ? statement.getLabel() : java.util.Optional.empty();
	}

	private static void removeLabelPrefix(InspectionContext context, LabeledStmt statement) {
		context.editor().removeWithTrailingWhitespace(labelPrefixRange(statement));
	}

	private static boolean hasCommentInPrefix(InspectionContext context, LabeledStmt statement) {
		String source = context.editor().text(labelPrefixRange(statement));
		return source.contains("//") || source.contains("/*");
	}

	private static Range labelPrefixRange(LabeledStmt statement) {
		JavaToken colon = statement.getLabel().getTokenRange().orElseThrow().getEnd();
		do {
			colon = colon.getNextToken().orElseThrow();
			String text = colon.getText();
			if (!text.isBlank() && !text.startsWith("//") && !text.startsWith("/*") && !":".equals(text)) {
				throw new IllegalStateException("Could not locate label colon");
			}
		}
		while (!":".equals(colon.getText()));
		return new Range(statement.getLabel().getRange().orElseThrow().begin, colon.getRange().orElseThrow().end);
	}

}

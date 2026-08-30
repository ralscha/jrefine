package ch.rasc.jrefine.tools.syntax;

import com.github.javaparser.ast.Modifier.Keyword;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

/** Sorts declaration modifiers into the customary Java Language Specification order. */
public final class SortModifiersTool implements InspectionTool {

	private static final EnumMap<Modifier.Keyword, Integer> ORDER = order();

	@Override
	public String id() {
		return "sort-modifiers";
	}

	@Override
	public String description() {
		return "Sort declaration modifiers into canonical Java order";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(Node.class)
			.stream()
			.filter(NodeWithModifiers.class::isInstance)
			.map(node -> candidate(context, node, (NodeWithModifiers<?>) node))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.node(), "Sort declaration modifiers"));
			if (applyFixes) {
				context.editor().replace(candidate.range(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, Node node,
			NodeWithModifiers<?> withModifiers) {
		NodeList<Modifier> modifiers = withModifiers.getModifiers();
		if (modifiers.size() < 2) {
			return java.util.Optional.empty();
		}
		List<Modifier> sorted = modifiers.stream()
			.sorted(Comparator.comparingInt(modifier -> ORDER.getOrDefault(modifier.getKeyword(), Integer.MAX_VALUE)))
			.toList();
		if (java.util.stream.IntStream.range(0, modifiers.size())
			.allMatch(index -> modifiers.get(index).getKeyword() == sorted.get(index).getKeyword())) {
			return java.util.Optional.empty();
		}
		Range range = new Range(modifiers.get(0).getRange().orElseThrow().begin,
				modifiers.get(modifiers.size() - 1).getRange().orElseThrow().end);
		String between = context.editor().text(range);
		String residue = between;
		for (Modifier modifier : modifiers) {
			residue = residue.replace(modifier.getKeyword().asString(), "");
		}
		if (!residue.isBlank()) {
			return java.util.Optional.empty();
		}
		String replacement = String.join(" ",
				sorted.stream().map(modifier -> modifier.getKeyword().asString()).toList());
		return java.util.Optional.of(new Candidate(node, range, replacement));
	}

	private static EnumMap<Modifier.Keyword, Integer> order() {
		EnumMap<Keyword, Integer> result = new EnumMap<>(Modifier.Keyword.class);
		List<Keyword> keywords = List.of(Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.PRIVATE,
				Modifier.Keyword.ABSTRACT, Modifier.Keyword.DEFAULT, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL,
				Modifier.Keyword.SEALED, Modifier.Keyword.NON_SEALED, Modifier.Keyword.TRANSIENT,
				Modifier.Keyword.VOLATILE, Modifier.Keyword.SYNCHRONIZED, Modifier.Keyword.NATIVE,
				Modifier.Keyword.STRICTFP, Modifier.Keyword.TRANSITIVE);
		for (int index = 0; index < keywords.size(); index++) {
			result.put(keywords.get(index), index);
		}
		return result;
	}

	private record Candidate(Node node, Range range, String replacement) {
	}

}

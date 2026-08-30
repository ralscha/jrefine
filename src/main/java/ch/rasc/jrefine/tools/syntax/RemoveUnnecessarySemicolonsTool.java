package ch.rasc.jrefine.tools.syntax;

import java.util.List;
import java.util.Optional;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import com.github.javaparser.Position;

/**
 * Removes empty statements inside blocks while preserving intentional empty loop bodies.
 */
public final class RemoveUnnecessarySemicolonsTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-semicolons";
	}

	@Override
	public String description() {
		return "Remove semicolons that form redundant empty statements";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<EmptyStmt> candidates = context.compilationUnit()
			.findAll(EmptyStmt.class)
			.stream()
			.filter(statement -> statement.getParentNode().orElse(null) instanceof BlockStmt)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		HashSet<Range> occupied = new HashSet<>();
		for (EmptyStmt statement : candidates) {
			occupied.add(statement.getRange().orElseThrow());
			findings.add(Finding.at(statement, "Remove unnecessary semicolon"));
			if (applyFixes) {
				context.editor().removeLine(statement);
				statement.remove();
			}
		}
		for (JavaToken token : context.compilationUnit().getTokenRange().orElseThrow()) {
			if (!";".equals(token.getText()) || token.getRange().isEmpty()
					|| occupied.contains(token.getRange().orElseThrow())
					|| !isStrayDeclarationSemicolon(context, token)) {
				continue;
			}
			Position position = token.getRange().orElseThrow().begin;
			findings.add(new Finding(position.line, position.column, "Remove unnecessary semicolon"));
			if (applyFixes) {
				context.editor().removeLine(token.getRange().orElseThrow());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean isStrayDeclarationSemicolon(InspectionContext context, JavaToken token) {
		Position position = token.getRange().orElseThrow().begin;
		List<TypeDeclaration> containingTypes = context.compilationUnit()
			.findAll(TypeDeclaration.class)
			.stream()
			.filter(type -> type.getRange().filter(range -> range.contains(position)).isPresent())
			.toList();
		if (containingTypes.isEmpty()) {
			if (context.compilationUnit()
				.getPackageDeclaration()
				.flatMap(Node::getRange)
				.filter(range -> range.contains(position))
				.isPresent()) {
				return false;
			}
			if (context.compilationUnit()
				.getImports()
				.stream()
				.flatMap(imported -> imported.getRange().stream())
				.anyMatch(range -> range.contains(position))) {
				return false;
			}
			return context.compilationUnit()
				.getModule()
				.flatMap(Node::getRange)
				.filter(range -> range.contains(position))
				.isEmpty();
		}
		TypeDeclaration type = containingTypes.stream()
			.max(Comparator.comparingInt(RemoveUnnecessarySemicolonsTool::depth))
			.orElseThrow();
		for (Object memberValue : type.getMembers()) {
			Node member = (Node) memberValue;
			if (member.getRange().filter(range -> range.contains(position)).isPresent()) {
				return false;
			}
		}
		return !(type instanceof EnumDeclaration enumeration) || !enumSeparator(enumeration, position);
	}

	private static boolean enumSeparator(EnumDeclaration enumeration, Position position) {
		if (enumeration.getEntries().isEmpty()) {
			return false;
		}
		Position afterEntries = enumeration.getEntries().getLast().orElseThrow().getEnd().orElseThrow();
		Position beforeMembers = enumeration.getMembers().isEmpty() ? enumeration.getEnd().orElseThrow()
				: enumeration.getMembers().get(0).getBegin().orElseThrow();
		return afterEntries.isBefore(position) && position.isBefore(beforeMembers);
	}

	private static int depth(Node node) {
		int depth = 0;
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			depth++;
			parent = parent.orElseThrow().getParentNode();
		}
		return depth;
	}

}

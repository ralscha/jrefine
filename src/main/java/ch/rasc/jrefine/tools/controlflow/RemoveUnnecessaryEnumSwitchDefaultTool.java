package ch.rasc.jrefine.tools.controlflow;

import java.util.Set;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Removes default branches from switches that cover every constant of a local enum. */
public final class RemoveUnnecessaryEnumSwitchDefaultTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-enum-switch-default";
	}

	@Override
	public String description() {
		return "Remove default branches from exhaustive enum switches";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<String, EnumDeclaration> enums = uniqueEnums(context);
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(SwitchStmt.class)
			.stream()
			.map(statement -> candidate(context, statement, statement.getSelector(), statement.getEntries(), enums))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(SwitchExpr.class)
			.stream()
			.map(expression -> candidate(context, expression, expression.getSelector(), expression.getEntries(), enums))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.defaultEntry(),
					"Remove unnecessary default branch from exhaustive enum switch"));
			if (applyFixes) {
				context.editor().removeLine(candidate.defaultEntry());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, Node node, Expression selector,
			List<SwitchEntry> entries, Map<String, EnumDeclaration> enums) {
		String type = TypeLookup.visibleType(context.compilationUnit(), selector, node)
			.map(RemoveUnnecessaryEnumSwitchDefaultTool::simpleType)
			.orElse(null);
		EnumDeclaration declaration = type == null ? null : enums.get(type);
		if (declaration == null || entries.stream().anyMatch(entry -> entry.getGuard().isPresent())) {
			return Optional.empty();
		}
		Optional<SwitchEntry> defaultEntry = entries.stream().filter(SwitchEntry::isDefault).findFirst();
		if (defaultEntry.isEmpty() || AstSupport.hasComment(context, defaultEntry.orElseThrow())) {
			return Optional.empty();
		}
		HashSet<String> covered = new HashSet<>();
		entries.stream()
			.filter(entry -> !entry.isDefault())
			.flatMap(entry -> entry.getLabels().stream())
			.map(RemoveUnnecessaryEnumSwitchDefaultTool::constantName)
			.flatMap(Optional::stream)
			.forEach(covered::add);
		Set<String> constants = declaration.getEntries()
			.stream()
			.map(constant -> constant.getNameAsString())
			.collect(java.util.stream.Collectors.toSet());
		return covered.containsAll(constants) ? Optional.of(new Candidate(defaultEntry.orElseThrow()))
				: Optional.empty();
	}

	private static Optional<String> constantName(Expression expression) {
		if (expression instanceof NameExpr name) {
			return Optional.of(name.getNameAsString());
		}
		if (expression instanceof FieldAccessExpr field) {
			return Optional.of(field.getNameAsString());
		}
		return Optional.empty();
	}

	private static Map<String, EnumDeclaration> uniqueEnums(InspectionContext context) {
		HashMap<String, List<EnumDeclaration>> grouped = new HashMap<>();
		for (EnumDeclaration declaration : context.compilationUnit().findAll(EnumDeclaration.class)) {
			grouped.computeIfAbsent(declaration.getNameAsString(), ignored -> new ArrayList<>()).add(declaration);
		}
		HashMap<String, EnumDeclaration> result = new HashMap<>();
		grouped.forEach((name, declarations) -> {
			if (declarations.size() == 1) {
				result.put(name, declarations.get(0));
			}
		});
		return result;
	}

	private static String simpleType(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

	private record Candidate(SwitchEntry defaultEntry) {
	}

}

package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.AstSupport;
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
import java.util.Set;

/** Removes interfaces already inherited through another locally declared supertype. */
public final class RemoveRedundantInterfacesTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-interfaces";
	}

	@Override
	public String description() {
		return "Remove redundant interfaces from extends and implements lists";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<String, ClassOrInterfaceDeclaration> types = uniqueTypes(context);
		List<Candidate> candidates = context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.map(type -> candidate(context, type, types))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.type(), "Remove redundant interface declaration"));
			if (applyFixes) {
				Range range = removalRange(candidate.types(), candidate.index());
				if (candidate.index() == 0 && candidate.types().size() > 1) {
					context.editor().removeWithTrailingWhitespace(range);
				}
				else {
					context.editor().removeWithLeadingWhitespace(range);
				}
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ClassOrInterfaceDeclaration declaration,
			Map<String, ClassOrInterfaceDeclaration> types) {
		NodeList<ClassOrInterfaceType> direct = declaration.isInterface() ? declaration.getExtendedTypes()
				: declaration.getImplementedTypes();
		for (int index = 0; index < direct.size(); index++) {
			String name = simpleType(direct.get(index).asString());
			HashSet<String> inherited = new HashSet<>();
			if (!declaration.isInterface()) {
				declaration.getExtendedTypes()
					.forEach(parent -> inherited
						.addAll(interfaceClosure(simpleType(parent.asString()), types, new HashSet<>())));
			}
			for (int other = 0; other < direct.size(); other++) {
				if (other != index) {
					inherited
						.addAll(interfaceClosure(simpleType(direct.get(other).asString()), types, new HashSet<>()));
				}
			}
			if (inherited.contains(name)) {
				Range range = removalRange(direct, index);
				if (!context.editor().text(range).contains("//") && !context.editor().text(range).contains("/*")) {
					return Optional.of(new Candidate(direct, index, direct.get(index)));
				}
			}
		}
		return Optional.empty();
	}

	private static Set<String> interfaceClosure(String name, Map<String, ClassOrInterfaceDeclaration> types,
			Set<String> visited) {
		if (!visited.add(name)) {
			return Set.of();
		}
		ClassOrInterfaceDeclaration declaration = types.get(name);
		if (declaration == null) {
			return Set.of();
		}
		HashSet<String> result = new HashSet<>();
		if (declaration.isInterface()) {
			result.add(name);
		}
		declaration.getImplementedTypes()
			.forEach(parent -> result.addAll(interfaceClosure(simpleType(parent.asString()), types, visited)));
		declaration.getExtendedTypes()
			.forEach(parent -> result.addAll(interfaceClosure(simpleType(parent.asString()), types, visited)));
		return result;
	}

	private static Range removalRange(NodeList<ClassOrInterfaceType> types, int index) {
		ClassOrInterfaceType current = types.get(index);
		if (types.size() == 1) {
			JavaToken keyword = AstSupport.previousSignificant(current.getTokenRange().orElseThrow().getBegin());
			return new Range(keyword.getRange().orElseThrow().begin, current.getRange().orElseThrow().end);
		}
		if (index == 0) {
			JavaToken comma = AstSupport.previousSignificant(types.get(1).getTokenRange().orElseThrow().getBegin());
			return new Range(current.getRange().orElseThrow().begin, comma.getRange().orElseThrow().end);
		}
		JavaToken comma = AstSupport.previousSignificant(current.getTokenRange().orElseThrow().getBegin());
		return new Range(comma.getRange().orElseThrow().begin, current.getRange().orElseThrow().end);
	}

	private static Map<String, ClassOrInterfaceDeclaration> uniqueTypes(InspectionContext context) {
		HashMap<String, List<ClassOrInterfaceDeclaration>> grouped = new HashMap<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			grouped.computeIfAbsent(type.getNameAsString(), ignored -> new ArrayList<>()).add(type);
		}
		HashMap<String, ClassOrInterfaceDeclaration> result = new HashMap<>();
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
		return dot >= 0 ? currentType.substring(dot + 1) : currentType;
	}

	private record Candidate(NodeList<ClassOrInterfaceType> types, int index, ClassOrInterfaceType type) {
	}

}

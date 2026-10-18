package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Qualifies source-local static members with their declaring type instead of an instance.
 */
public final class QualifyStaticMemberAccessTool implements InspectionTool {

	@Override
	public String id() {
		return "qualify-static-member-access";
	}

	@Override
	public String description() {
		return "Qualify source-local static field and method access with the declaring type";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<String, TypeDeclaration<?>> types = uniqueTypes(context);
		Set<String> staticFields = new HashSet<>();
		Set<String> staticMethods = new HashSet<>();
		for (TypeDeclaration<?> type : types.values()) {
			for (var member : type.getMembers()) {
				if (member instanceof FieldDeclaration field && field.isStatic()) {
					field.getVariables().forEach(variable -> staticFields.add(variable.getNameAsString()));
				}
				else if (member instanceof MethodDeclaration method && method.isStatic()) {
					staticMethods.add(method.getNameAsString());
				}
			}
		}
		ArrayList<Candidate> candidates = new ArrayList<>();
		for (FieldAccessExpr access : context.compilationUnit().findAll(FieldAccessExpr.class)) {
			if (!staticFields.contains(access.getNameAsString())) {
				continue;
			}
			sourceType(context, access.getScope(), access, types).filter(type -> canQualify(context, type, access))
				.filter(type -> staticField(type, access.getNameAsString()))
				.ifPresent(type -> candidates.add(new Candidate(access, access.getScope(), type.getNameAsString())));
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isEmpty() || !staticMethods.contains(call.getNameAsString())) {
				continue;
			}
			Expression scope = call.getScope().orElseThrow();
			sourceType(context, scope, call, types).filter(type -> canQualify(context, type, call))
				.filter(type -> staticMethod(type, call))
				.ifPresent(type -> candidates.add(new Candidate(call, scope, type.getNameAsString())));
		}
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.access(), "Access static member through declaring type '"
					+ candidate.replacement() + "' instead of an instance"));
			if (applyFixes) {
				context.editor().replace(candidate.scope().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static java.util.Optional<TypeDeclaration<?>> sourceType(InspectionContext context, Expression scope,
			com.github.javaparser.ast.Node use, Map<String, TypeDeclaration<?>> types) {
		if (!(scope instanceof NameExpr)) {
			return java.util.Optional.empty();
		}
		return TypeLookup.visibleType(context.compilationUnit(), scope, use)
			.map(TypeLookup::simpleName)
			.map(types::get);
	}

	private static boolean staticField(TypeDeclaration<?> type, String name) {
		return type.getMembers()
			.stream()
			.filter(FieldDeclaration.class::isInstance)
			.map(FieldDeclaration.class::cast)
			.filter(FieldDeclaration::isStatic)
			.flatMap(field -> field.getVariables().stream())
			.anyMatch(variable -> variable.getNameAsString().equals(name));
	}

	private static boolean canQualify(InspectionContext context, TypeDeclaration<?> type,
			com.github.javaparser.ast.Node use) {
		String typeName = type.getNameAsString();
		if (TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), typeName, use)) {
			return false;
		}
		return context.compilationUnit()
			.findAll(FieldDeclaration.class)
			.stream()
			.flatMap(field -> field.getVariables().stream())
			.noneMatch(variable -> variable.getNameAsString().equals(typeName));
	}

	private static boolean staticMethod(TypeDeclaration<?> type, MethodCallExpr call) {
		List<MethodDeclaration> matches = type.getMembers()
			.stream()
			.filter(MethodDeclaration.class::isInstance)
			.map(MethodDeclaration.class::cast)
			.filter(method -> method.getNameAsString().equals(call.getNameAsString()))
			.filter(method -> method.getParameters().size() == call.getArguments().size())
			.toList();
		return !matches.isEmpty() && matches.stream().allMatch(MethodDeclaration::isStatic);
	}

	private static Map<String, TypeDeclaration<?>> uniqueTypes(InspectionContext context) {
		HashMap<String, List<TypeDeclaration<?>>> grouped = new HashMap<>();
		context.compilationUnit()
			.findAll(TypeDeclaration.class)
			.forEach(type -> grouped.computeIfAbsent(type.getNameAsString(), ignored -> new ArrayList<>()).add(type));
		HashMap<String, TypeDeclaration<?>> result = new HashMap<>();
		grouped.forEach((name, declarations) -> {
			if (declarations.size() == 1) {
				result.put(name, declarations.getFirst());
			}
		});
		return result;
	}

	private record Candidate(com.github.javaparser.ast.Node access, Expression scope, String replacement) {
	}

}

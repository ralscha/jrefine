package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.modules.ModuleRequiresDirective;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.WildcardType;
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

/** Removes declaration elements that are provably redundant from source syntax alone. */
public final class RemoveRedundantDeclarationElementsTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-declaration-elements";
	}

	@Override
	public String description() {
		return "Remove redundant @SafeVarargs, throws types, annotation values, and java.base requirements";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		redundantSafeVarargs(context, candidates);
		duplicateThrows(context, candidates);
		defaultAnnotationValues(context, candidates);
		redundantJavaBase(context, candidates);

		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.node(), candidate.message()));
			if (applyFixes) {
				candidate.edit().apply(context);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static void redundantSafeVarargs(InspectionContext context, List<Candidate> candidates) {
		List<CallableDeclaration<?>> callables = new ArrayList<>(
				context.compilationUnit().findAll(MethodDeclaration.class));
		callables.addAll(context.compilationUnit().findAll(ConstructorDeclaration.class));
		for (CallableDeclaration<?> callable : callables) {
			Optional<AnnotationExpr> annotation = callable.getAnnotations()
				.stream()
				.filter(value -> knownSafeVarargs(context, value))
				.findFirst();
			if (annotation.isEmpty()) {
				continue;
			}
			Optional<Type> component = callable.getParameters()
				.stream()
				.filter(parameter -> parameter.isVarArgs())
				.map(parameter -> parameter.getType())
				.findFirst();
			if (component.filter(type -> reifiable(type, callable)).isEmpty()) {
				continue;
			}
			AnnotationExpr redundant = annotation.orElseThrow();
			candidates.add(new Candidate(redundant, "Remove @SafeVarargs from a reifiable varargs parameter",
					inspection -> inspection.editor().removeLine(redundant)));
		}
	}

	private static boolean knownSafeVarargs(InspectionContext context, AnnotationExpr annotation) {
		String spelling = annotation.getNameAsString();
		if ("java.lang.SafeVarargs".equals(spelling)) {
			return true;
		}
		if (!"SafeVarargs".equals(spelling) || context.compilationUnit()
			.findAll(AnnotationDeclaration.class)
			.stream()
			.anyMatch(declaration -> "SafeVarargs".equals(declaration.getNameAsString()))) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.filter(imported -> !imported.isStatic() && !imported.isAsterisk())
			.filter(imported -> "SafeVarargs".equals(imported.getName().getIdentifier()))
			.allMatch(imported -> "java.lang.SafeVarargs".equals(imported.getNameAsString()));
	}

	private static boolean reifiable(Type type, CallableDeclaration<?> callable) {
		if (type.isPrimitiveType()) {
			return true;
		}
		if (type instanceof ArrayType array) {
			return reifiable(array.getComponentType(), callable);
		}
		if (!(type instanceof ClassOrInterfaceType classType)
				|| visibleTypeParameters(callable).contains(classType.getNameAsString())) {
			return false;
		}
		return classType.getTypeArguments().isEmpty() || classType.getTypeArguments()
			.orElseThrow()
			.stream()
			.allMatch(RemoveRedundantDeclarationElementsTool::unboundedWildcard);
	}

	private static boolean unboundedWildcard(Type type) {
		return type instanceof WildcardType wildcard && wildcard.getExtendedType().isEmpty()
				&& wildcard.getSuperType().isEmpty();
	}

	private static Set<String> visibleTypeParameters(CallableDeclaration<?> callable) {
		HashSet<String> names = new HashSet<>();
		callable.getTypeParameters().stream().map(TypeParameter::getNameAsString).forEach(names::add);
		Optional<com.github.javaparser.ast.Node> current = callable.getParentNode();
		while (current.isPresent()) {
			com.github.javaparser.ast.Node parent = current.orElseThrow();
			if (parent instanceof NodeWithTypeParameters<?> declaration) {
				declaration.getTypeParameters().stream().map(TypeParameter::getNameAsString).forEach(names::add);
			}
			current = parent.getParentNode();
		}
		return names;
	}

	private static void duplicateThrows(InspectionContext context, List<Candidate> candidates) {
		List<CallableDeclaration<?>> callables = new ArrayList<>(
				context.compilationUnit().findAll(MethodDeclaration.class));
		callables.addAll(context.compilationUnit().findAll(ConstructorDeclaration.class));
		for (CallableDeclaration<?> callable : callables) {
			NodeList<ReferenceType> thrown = callable.getThrownExceptions();
			HashSet<String> seen = new HashSet<>();
			for (int index = 0; index < thrown.size(); index++) {
				ReferenceType type = thrown.get(index);
				if (seen.add(normalizeType(type.asString()))) {
					continue;
				}
				Range range = listElementRange(thrown, index);
				if (AstSupport.hasComment(context, type) || context.editor().text(range).contains("//")
						|| context.editor().text(range).contains("/*")) {
					continue;
				}
				boolean first = index == 0;
				candidates.add(new Candidate(type, "Remove duplicate exception from throws clause", inspection -> {
					if (first) {
						inspection.editor().removeWithTrailingWhitespace(range);
					}
					else {
						inspection.editor().removeWithLeadingWhitespace(range);
					}
				}));
			}
		}
	}

	private static void defaultAnnotationValues(InspectionContext context, List<Candidate> candidates) {
		Map<String, AnnotationDeclaration> annotations = uniqueAnnotations(context);
		for (NormalAnnotationExpr annotation : context.compilationUnit().findAll(NormalAnnotationExpr.class)) {
			AnnotationDeclaration declaration = annotations.get(annotation.getName().getIdentifier());
			if (declaration == null || !annotation.getNameAsString().equals(annotation.getName().getIdentifier())
					|| AstSupport.hasComment(context, annotation)) {
				continue;
			}
			Map<String, String> defaults = new HashMap<>();
			for (AnnotationMemberDeclaration member : declaration.getMembers()
				.stream()
				.filter(AnnotationMemberDeclaration.class::isInstance)
				.map(AnnotationMemberDeclaration.class::cast)
				.toList()) {
				member.getDefaultValue().ifPresent(value -> defaults.put(member.getNameAsString(), value.toString()));
			}
			List<MemberValuePair> redundant = annotation.getPairs()
				.stream()
				.filter(pair -> pair.getValue().toString().equals(defaults.get(pair.getNameAsString())))
				.toList();
			if (redundant.isEmpty()) {
				continue;
			}
			List<MemberValuePair> retained = annotation.getPairs()
				.stream()
				.filter(pair -> !redundant.contains(pair))
				.toList();
			String replacement = annotationReplacement(context, annotation, retained);
			candidates.add(new Candidate(annotation, "Remove annotation argument equal to its declared default",
					inspection -> inspection.editor().replace(annotation.getRange().orElseThrow(), replacement)));
		}
	}

	private static Map<String, AnnotationDeclaration> uniqueAnnotations(InspectionContext context) {
		HashMap<String, List<AnnotationDeclaration>> grouped = new HashMap<>();
		context.compilationUnit()
			.findAll(AnnotationDeclaration.class)
			.stream()
			.filter(annotation -> annotation.getParentNode().filter(CompilationUnit.class::isInstance).isPresent())
			.forEach(annotation -> grouped.computeIfAbsent(annotation.getNameAsString(), ignored -> new ArrayList<>())
				.add(annotation));
		HashMap<String, AnnotationDeclaration> result = new HashMap<>();
		grouped.forEach((name, declarations) -> {
			if (declarations.size() == 1) {
				result.put(name, declarations.getFirst());
			}
		});
		return result;
	}

	private static String annotationReplacement(InspectionContext context, NormalAnnotationExpr annotation,
			List<MemberValuePair> retained) {
		String name = context.editor().text(annotation.getName());
		if (retained.isEmpty()) {
			return "@" + name;
		}
		return "@" + name + "("
				+ retained.stream().map(context.editor()::text).collect(java.util.stream.Collectors.joining(", "))
				+ ")";
	}

	private static void redundantJavaBase(InspectionContext context, List<Candidate> candidates) {
		context.compilationUnit()
			.findAll(ModuleRequiresDirective.class)
			.stream()
			.filter(directive -> "java.base".equals(directive.getNameAsString()))
			.filter(directive -> !AstSupport.hasComment(context, directive))
			.forEach(directive -> candidates
				.add(new Candidate(directive, "Remove redundant requires java.base directive",
						inspection -> inspection.editor().removeLine(directive))));
	}

	private static Range listElementRange(NodeList<? extends ReferenceType> values, int index) {
		ReferenceType current = values.get(index);
		if (index == 0 && values.size() > 1) {
			JavaToken comma = AstSupport.previousSignificant(values.get(1).getTokenRange().orElseThrow().getBegin());
			return new Range(current.getRange().orElseThrow().begin, comma.getRange().orElseThrow().end);
		}
		JavaToken comma = AstSupport.previousSignificant(current.getTokenRange().orElseThrow().getBegin());
		return new Range(comma.getRange().orElseThrow().begin, current.getRange().orElseThrow().end);
	}

	private static String normalizeType(String value) {
		return value.replace(" ", "");
	}

	@FunctionalInterface
	private interface Edit {

		void apply(InspectionContext context);

	}

	private record Candidate(com.github.javaparser.ast.Node node, String message, Edit edit) {
	}

}

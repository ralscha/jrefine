package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ReturnStmt;

/** Replaces locally provable Guava functional primitives with Java APIs. */
public final class ReplaceGuavaFunctionalPrimitivesTool implements InspectionTool {

	private static final Map<String, String> FUNCTION_TYPES = Map.of("Function", "java.util.function.Function",
			"Predicate", "java.util.function.Predicate", "Supplier", "java.util.function.Supplier");

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "ArrayList", "LinkedList",
			"HashSet", "TreeSet");

	@Override
	public String id() {
		return "replace-guava-functional-primitives";
	}

	@Override
	public String description() {
		return "Replace Guava functional primitives with Java APIs";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<TypeCandidate> typeCandidates = context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.map(variable -> typeCandidate(context, variable))
			.flatMap(Optional::stream)
			.toList();
		List<ChainCandidate> chainCandidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(expression -> chainCandidate(context, expression))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		HashMap<String, String> names = new HashMap<>();
		for (TypeCandidate candidate : typeCandidates) {
			names.computeIfAbsent(candidate.qualifiedType(), type -> ImportSupport.useType(context, type, applyFixes));
		}
		String listName = chainCandidates.isEmpty() ? "List"
				: ImportSupport.useType(context, "java.util.List", applyFixes);
		String collectors = chainCandidates.isEmpty() ? "Collectors"
				: ImportSupport.useType(context, "java.util.stream.Collectors", applyFixes);
		for (TypeCandidate candidate : typeCandidates) {
			findings.add(Finding.at(candidate.type(), "Replace Guava functional interface with Java interface"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.type().getRange().orElseThrow(),
							names.get(candidate.qualifiedType()) + typeArguments(candidate.type()));
			}
		}
		for (ChainCandidate candidate : chainCandidates) {
			findings.add(Finding.at(candidate.expression(), "Replace FluentIterable chain with Stream API"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.type().getRange().orElseThrow(), listName + typeArguments(candidate.type()));
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(),
							context.editor().text(candidate.source()) + ".stream().map("
									+ context.editor().text(candidate.function()) + ").collect(" + collectors
									+ ".toList())");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<TypeCandidate> typeCandidate(InspectionContext context, VariableDeclarator variable) {
		if (!(variable.getType() instanceof ClassOrInterfaceType type) || variable.getInitializer()
			.filter(initializer -> initializer instanceof LambdaExpr || initializer instanceof MethodReferenceExpr)
			.isEmpty()) {
			return Optional.empty();
		}
		String simple = simpleType(type.asString());
		String qualified = FUNCTION_TYPES.get(simple);
		return qualified != null && knownGuavaType(context, type.asString(), "com.google.common.base", simple)
				? Optional.of(new TypeCandidate(type, qualified)) : Optional.empty();
	}

	private static Optional<ChainCandidate> chainCandidate(InspectionContext context, MethodCallExpr toList) {
		if (!"toList".equals(toList.getNameAsString()) || !toList.getArguments().isEmpty()
				|| !(toList.getScope().orElse(null) instanceof MethodCallExpr transform)
				|| !"transform".equals(transform.getNameAsString()) || transform.getArguments().size() != 1
				|| !(transform.getScope().orElse(null) instanceof MethodCallExpr from)
				|| !"from".equals(from.getNameAsString()) || from.getArguments().size() != 1
				|| from.getScope().isEmpty()
				|| !knownGuavaOwner(context, from.getScope().orElseThrow().toString(), "FluentIterable")
				|| AstSupport.hasComment(context, toList)) {
			return Optional.empty();
		}
		ClassOrInterfaceType type = targetType(toList).orElse(null);
		if (type == null || !knownGuavaType(context, type.asString(), "com.google.common.collect", "ImmutableList")) {
			return Optional.empty();
		}
		Expression source = from.getArgument(0);
		if (TypeLookup.visibleType(context.compilationUnit(), source, toList)
			.filter(sourceType -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), sourceType,
					COLLECTION_TYPES))
			.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new ChainCandidate(type, toList, source, transform.getArgument(0)));
	}

	private static Optional<ClassOrInterfaceType> targetType(MethodCallExpr expression) {
		Node parent = expression.getParentNode().orElse(null);
		if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == expression
				&& variable.getType() instanceof ClassOrInterfaceType type) {
			return Optional.of(type);
		}
		if (parent instanceof ReturnStmt statement && statement.getExpression().orElse(null) == expression) {
			return AstSupport.ancestor(statement, MethodDeclaration.class)
				.map(MethodDeclaration::getType)
				.filter(ClassOrInterfaceType.class::isInstance)
				.map(ClassOrInterfaceType.class::cast);
		}
		return Optional.empty();
	}

	private static boolean knownGuavaOwner(InspectionContext context, String spelling, String simple) {
		return knownGuavaType(context, spelling, "com.google.common.collect", simple);
	}

	private static boolean knownGuavaType(InspectionContext context, String spelling, String packageName,
			String simple) {
		if (context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(simple))) {
			return false;
		}
		String raw = simpleType(spelling);
		if (!raw.equals(simple)) {
			return false;
		}
		if (spelling.startsWith(packageName + ".")) {
			return true;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && (imported.getNameAsString().equals(packageName + "." + simple)
					|| imported.isAsterisk() && imported.getNameAsString().equals(packageName)));
	}

	private static String typeArguments(ClassOrInterfaceType type) {
		return type.getTypeArguments()
			.map(arguments -> "<"
					+ arguments.stream().map(Node::toString).reduce((left, right) -> left + ", " + right).orElse("")
					+ ">")
			.orElse("");
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

	private record TypeCandidate(ClassOrInterfaceType type, String qualifiedType) {
	}

	private record ChainCandidate(ClassOrInterfaceType type, MethodCallExpr expression, Expression source,
			Expression function) {
	}

}

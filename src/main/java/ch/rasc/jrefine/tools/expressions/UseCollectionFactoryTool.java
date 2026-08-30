package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.List;

/** Replaces legacy unmodifiable collection wrappers with collection factories. */
public final class UseCollectionFactoryTool implements InspectionTool {

	@Override
	public String id() {
		return "use-collection-factory";
	}

	@Override
	public int minimumJavaVersion() {
		return 9;
	}

	@Override
	public String description() {
		return "Replace unmodifiable wrappers with collection factory calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(FieldAccessExpr.class)
			.stream()
			.map(access -> candidate(context, access))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		HashMap<String, String> names = new HashMap<>();
		for (Candidate candidate : candidates) {
			names.computeIfAbsent(candidate.factory(),
					factory -> ImportSupport.useType(context, "java.util." + factory, applyFixes));
		}
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), candidate.message()));
			if (applyFixes) {
				String arguments = candidate.arguments()
					.stream()
					.map(context.editor()::text)
					.reduce((left, right) -> left + ", " + right)
					.orElse("");
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(),
							names.get(candidate.factory()) + "." + candidate.method() + "(" + arguments + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (call.getArguments().size() != 1 || call.getScope().isEmpty()
				|| !knownUtilType(context, call.getScope().orElseThrow().toString(), "Collections")
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		String factory = switch (call.getNameAsString()) {
			case "unmodifiableList" -> "List";
			case "unmodifiableSet" -> "Set";
			case "unmodifiableMap" -> "Map";
			default -> null;
		};
		if (factory == null) {
			return Optional.empty();
		}
		Expression argument = call.getArgument(0);
		if ("List".equals(factory) && argument instanceof MethodCallExpr inner
				&& knownStaticCall(context, inner, "Arrays", "asList")
				&& inner.getArguments().stream().allMatch(UseCollectionFactoryTool::nonNullConstant)) {
			return Optional.of(new Candidate(call, factory, "of", java.util.List.copyOf(inner.getArguments()),
					"Use immutable collection factory"));
		}
		if ("List".equals(factory) && argument instanceof MethodCallExpr inner
				&& knownStaticCall(context, inner, "Collections", "emptyList")) {
			return Optional
				.of(new Candidate(call, factory, "of", java.util.List.of(), "Use immutable collection factory"));
		}
		if ("List".equals(factory) && argument instanceof MethodCallExpr inner
				&& knownStaticCall(context, inner, "Collections", "singletonList") && inner.getArguments().size() == 1
				&& nonNullConstant(inner.getArgument(0))) {
			return Optional.of(new Candidate(call, factory, "of", java.util.List.of(inner.getArgument(0)),
					"Use immutable collection factory"));
		}
		if (argument instanceof ObjectCreationExpr creation && creation.getArguments().size() == 1
				&& creation.getAnonymousClassBody().isEmpty()) {
			String expected = switch (factory) {
				case "List" -> "ArrayList";
				case "Set" -> "HashSet";
				case "Map" -> "HashMap";
				default -> "";
			};
			if (knownUtilType(context, creation.getType().asString(), expected)) {
				return Optional.of(new Candidate(call, factory, "copyOf", java.util.List.of(creation.getArgument(0)),
						"Use immutable collection factory"));
			}
		}
		return Optional.empty();
	}

	private static Optional<Candidate> candidate(InspectionContext context, FieldAccessExpr access) {
		if (!knownUtilType(context, access.getScope().toString(), "Collections")
				|| AstSupport.hasComment(context, access)) {
			return Optional.empty();
		}
		String method = switch (access.getNameAsString()) {
			case "EMPTY_LIST" -> "emptyList";
			case "EMPTY_MAP" -> "emptyMap";
			case "EMPTY_SET" -> "emptySet";
			default -> null;
		};
		return method == null ? Optional.empty() : Optional.of(new Candidate(access, "Collections", method, List.of(),
				"Replace raw empty-collection field with generic factory method"));
	}

	private static boolean knownStaticCall(InspectionContext context, MethodCallExpr call, String owner,
			String method) {
		return call.getNameAsString().equals(method) && call.getScope().isPresent()
				&& knownUtilType(context, call.getScope().orElseThrow().toString(), owner);
	}

	private static boolean knownUtilType(InspectionContext context, String spelling, String expected) {
		if (context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(expected))) {
			return false;
		}
		return TypeLookup.isKnownJavaUtilType(context.compilationUnit(), spelling, Set.of(expected));
	}

	private static boolean nonNullConstant(Expression expression) {
		return expression instanceof LiteralExpr && !(expression instanceof NullLiteralExpr)
				|| expression instanceof ClassExpr;
	}

	private record Candidate(Expression expression, String factory, String method, List<Expression> arguments,
			String message) {
	}

}

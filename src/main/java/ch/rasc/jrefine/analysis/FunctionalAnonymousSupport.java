package ch.rasc.jrefine.analysis;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import java.util.List;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import ch.rasc.jrefine.api.InspectionContext;

import java.util.Map;
import java.util.Optional;

/**
 * Local proof that an anonymous class is a single functional-interface implementation.
 */
public final class FunctionalAnonymousSupport {

	private static final Map<String, Signature> JDK_INTERFACES = Map.ofEntries(
			Map.entry("Runnable", new Signature("run", 0)), Map.entry("Callable", new Signature("call", 0)),
			Map.entry("Comparator", new Signature("compare", 2)), Map.entry("Supplier", new Signature("get", 0)),
			Map.entry("Consumer", new Signature("accept", 1)), Map.entry("BiConsumer", new Signature("accept", 2)),
			Map.entry("Function", new Signature("apply", 1)), Map.entry("BiFunction", new Signature("apply", 2)),
			Map.entry("Predicate", new Signature("test", 1)), Map.entry("BiPredicate", new Signature("test", 2)),
			Map.entry("UnaryOperator", new Signature("apply", 1)),
			Map.entry("BinaryOperator", new Signature("apply", 2)));

	private FunctionalAnonymousSupport() {
	}

	public static Optional<AnonymousFunction> function(InspectionContext context, ObjectCreationExpr creation) {
		if (creation.getAnonymousClassBody().isEmpty() || !creation.getArguments().isEmpty()
				|| AstSupport.hasComment(context, creation)) {
			return Optional.empty();
		}
		NodeList<BodyDeclaration<?>> body = creation.getAnonymousClassBody().orElseThrow();
		if (body.size() != 1 || !(body.get(0) instanceof MethodDeclaration method) || method.getBody().isEmpty()
				|| method.isStatic() || method.isPrivate() || !method.findAll(ThisExpr.class).isEmpty()
				|| !method.findAll(SuperExpr.class).isEmpty()) {
			return Optional.empty();
		}
		String typeName = simpleType(creation.getType().asString());
		Signature jdk = JDK_INTERFACES.get(typeName);
		if (jdk != null && knownJdkInterface(context, creation.getType().asString(), typeName) && jdk.matches(method)) {
			return Optional.of(new AnonymousFunction(creation, method));
		}
		List<ClassOrInterfaceDeclaration> local = context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> type.isInterface() && type.getNameAsString().equals(typeName))
			.toList();
		if (local.size() != 1) {
			return Optional.empty();
		}
		List<MethodDeclaration> abstractMethods = local.getFirst()
			.getMethods()
			.stream()
			.filter(candidate -> !candidate.isStatic() && !candidate.isPrivate() && !candidate.isDefault())
			.toList();
		return abstractMethods.size() == 1
				&& abstractMethods.getFirst().getNameAsString().equals(method.getNameAsString())
				&& abstractMethods.getFirst().getParameters().size() == method.getParameters().size()
						? Optional.of(new AnonymousFunction(creation, method)) : Optional.empty();
	}

	public static String lambdaParameters(MethodDeclaration method) {
		List<String> names = method.getParameters().stream().map(parameter -> parameter.getNameAsString()).toList();
		if (names.isEmpty()) {
			return "()";
		}
		if (names.size() == 1) {
			return names.getFirst();
		}
		return "(" + String.join(", ", names) + ")";
	}

	private static boolean knownJdkInterface(InspectionContext context, String spelling, String simpleName) {
		if (context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(simpleName))) {
			return false;
		}
		if (spelling.contains(".")) {
			return "java.lang.Runnable".equals(spelling) || "java.util.Comparator".equals(spelling)
					|| "java.util.concurrent.Callable".equals(spelling)
					|| spelling.equals("java.util.function." + simpleName);
		}
		if ("Runnable".equals(simpleName)) {
			return true;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && (imported.getNameAsString().endsWith("." + simpleName)
					&& imported.getNameAsString().startsWith("java.util.")
					|| imported.isAsterisk() && ("java.util".equals(imported.getNameAsString())
							|| "java.util.concurrent".equals(imported.getNameAsString())
							|| "java.util.function".equals(imported.getNameAsString()))));
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

	private record Signature(String name, int parameters) {
		boolean matches(MethodDeclaration method) {
			return method.getNameAsString().equals(name) && method.getParameters().size() == parameters;
		}
	}

	public record AnonymousFunction(ObjectCreationExpr creation, MethodDeclaration method) {
	}

}

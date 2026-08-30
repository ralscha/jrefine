package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports source-local class organization, utility, inheritance, and member-structure
 * concerns.
 */
public final class ReportClassStructureIssuesTool implements PolicyInspectionTool {

	private static final Map<String, String> LISTENER_ADAPTERS = Map.ofEntries(
			Map.entry("ComponentListener", "ComponentAdapter"), Map.entry("ContainerListener", "ContainerAdapter"),
			Map.entry("FocusListener", "FocusAdapter"), Map.entry("HierarchyBoundsListener", "HierarchyBoundsAdapter"),
			Map.entry("KeyListener", "KeyAdapter"), Map.entry("MouseListener", "MouseAdapter"),
			Map.entry("MouseMotionListener", "MouseMotionAdapter"), Map.entry("WindowListener", "WindowAdapter"));

	@Override
	public String id() {
		return "report-class-structure-issues";
	}

	@Override
	public String description() {
		return "Report class, interface, utility, singleton, field, constructor, and parameter structure issues";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		methodAndFieldModifiers(context, findings);
		types(context, findings);
		topLevelTypes(context, findings);
		localAndAnonymousClasses(context, findings);
		unreadParameters(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void methodAndFieldModifiers(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			if (method.isPrivate() && method.isFinal()) {
				findings.add(Finding.at(method, "Private method is declared final"));
			}
			if (method.isStatic() && method.isFinal()) {
				findings.add(Finding.at(method, "Static method is declared final"));
			}
			if (method.isFinal()) {
				findings.add(Finding.at(method, "Method is final and cannot be overridden"));
			}
		}
		context.compilationUnit()
			.findAll(FieldDeclaration.class)
			.stream()
			.filter(field -> field.isStatic() && !field.isFinal())
			.forEach(field -> findings.add(Finding.at(field, "Static field is not final")));
	}

	private static void types(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (type.isInterface()) {
				inspectInterface(type, findings);
			}
			else {
				inspectClass(type, findings);
			}
		}
		for (EnumDeclaration type : context.compilationUnit().findAll(EnumDeclaration.class)) {
			type.getFields()
				.stream()
				.filter(field -> !field.isFinal())
				.forEach(field -> findings.add(Finding.at(field, "Non-final field in enum")));
		}
	}

	private static void inspectInterface(ClassOrInterfaceDeclaration type, List<Finding> findings) {
		if (type.getMethods().stream().filter(ReportClassStructureIssuesTool::functionalMethod).count() == 1
				&& type.getAnnotationByName("FunctionalInterface").isEmpty()) {
			findings.add(Finding.at(type, "Interface may be annotated as @FunctionalInterface"));
		}
		if (type.getMethods().isEmpty() && type.getFields().isEmpty()) {
			findings.add(Finding.at(type, "Marker interface has no methods or fields"));
		}
		type.getFields().forEach(field -> findings.add(Finding.at(field, "Constant is declared in an interface")));
		type.getMembers()
			.stream()
			.filter(ClassOrInterfaceDeclaration.class::isInstance)
			.map(ClassOrInterfaceDeclaration.class::cast)
			.forEach(nested -> findings.add(Finding.at(nested, "Inner class is declared in an interface")));
	}

	private static boolean functionalMethod(MethodDeclaration method) {
		if (method.isStatic() || method.isPrivate() || method.getBody().isPresent()) {
			return false;
		}
		return !Set.of("equals", "hashCode", "toString").contains(method.getNameAsString());
	}

	private static void inspectClass(ClassOrInterfaceDeclaration type, List<Finding> findings) {
		if (type.isAbstract() && interfaceCandidate(type)) {
			findings.add(Finding.at(type, "Abstract class may be an interface"));
		}
		for (ClassOrInterfaceType implemented : type.getImplementedTypes()) {
			String adapter = LISTENER_ADAPTERS.get(implemented.getNameAsString());
			if (adapter != null) {
				findings.add(Finding.at(implemented,
						"Class may extend " + adapter + " instead of implementing " + implemented.getNameAsString()));
			}
		}
		List<ConstructorDeclaration> constructors = type.getConstructors();
		if (!constructors.isEmpty() && constructors.stream().allMatch(ConstructorDeclaration::isPrivate)
				&& !type.isFinal()) {
			findings.add(Finding.at(type, "Class with only private constructors should be final"));
		}
		if (type.isAbstract()) {
			type.getFields()
				.stream()
				.filter(ReportClassStructureIssuesTool::constant)
				.forEach(field -> findings.add(Finding.at(field, "Constant is declared in an abstract class")));
		}
		if (type.getMembers().isEmpty()) {
			findings.add(Finding.at(type, "Empty class"));
		}
		for (MethodDeclaration method : type.getMethods()) {
			if (type.isAbstract() && method.getBody().filter(body -> body.getStatements().isEmpty()).isPresent()) {
				findings.add(Finding.at(method, "No-op method in abstract class"));
			}
		}
		type.getMembers()
			.stream()
			.filter(InitializerDeclaration.class::isInstance)
			.map(InitializerDeclaration.class::cast)
			.filter(initializer -> !initializer.isStatic())
			.forEach(initializer -> findings.add(Finding.at(initializer, "Non-static initializer")));
		if (singleton(type)) {
			findings.add(Finding.at(type, "Singleton class"));
		}
		if (utility(type)) {
			if (!type.isFinal() && !type.isAbstract()) {
				findings.add(Finding.at(type, "Utility class is not final"));
			}
			constructors.stream()
				.filter(ConstructorDeclaration::isPublic)
				.forEach(
						constructor -> findings.add(Finding.at(constructor, "Utility class has a public constructor")));
			if (constructors.isEmpty() || constructors.stream().noneMatch(ConstructorDeclaration::isPrivate)) {
				findings.add(Finding.at(type, "Utility class has no private constructor"));
			}
		}
	}

	private static boolean interfaceCandidate(ClassOrInterfaceDeclaration type) {
		if (!type.getConstructors().isEmpty()) {
			return false;
		}
		if (type.getFields().stream().anyMatch(field -> !constant(field))) {
			return false;
		}
		return type.getMethods().stream().noneMatch(method -> !method.isAbstract() && !method.isStatic());
	}

	private static boolean constant(FieldDeclaration field) {
		return field.isPublic() && field.isStatic() && field.isFinal();
	}

	private static boolean singleton(ClassOrInterfaceDeclaration type) {
		if (type.getConstructors().isEmpty()
				|| type.getConstructors().stream().anyMatch(constructor -> !constructor.isPrivate())) {
			return false;
		}
		return type.getFields()
			.stream()
			.filter(field -> field.isStatic())
			.flatMap(field -> field.getVariables().stream())
			.anyMatch(variable -> simple(variable.getType().asString()).equals(type.getNameAsString())
					&& variable.getInitializer()
						.filter(ObjectCreationExpr.class::isInstance)
						.map(ObjectCreationExpr.class::cast)
						.filter(creation -> creation.getType().getNameAsString().equals(type.getNameAsString()))
						.isPresent());
	}

	private static boolean utility(ClassOrInterfaceDeclaration type) {
		List<BodyDeclaration<?>> substantive = type.getMembers()
			.stream()
			.filter(member -> !(member instanceof ConstructorDeclaration))
			.toList();
		if (substantive.isEmpty()) {
			return false;
		}
		return substantive.stream()
			.allMatch(member -> member instanceof MethodDeclaration method && method.isStatic()
					|| member instanceof FieldDeclaration field && field.isStatic()
					|| member instanceof InitializerDeclaration initializer && initializer.isStatic()
					|| member instanceof ClassOrInterfaceDeclaration nested && nested.isStatic());
	}

	private static void topLevelTypes(InspectionContext context, List<Finding> findings) {
		NodeList<TypeDeclaration<?>> topLevel = context.compilationUnit().getTypes();
		if (topLevel.size() > 1) {
			topLevel
				.forEach(type -> findings.add(Finding.at(type, "Multiple top-level classes are declared in one file")));
		}
		String filename = context.path().getFileName().toString();
		if (filename.endsWith(".java")) {
			filename = filename.substring(0, filename.length() - 5);
		}
		String expected = filename;
		topLevel.stream()
			.filter(type -> !type.getNameAsString().equals(expected))
			.forEach(type -> findings.add(Finding.at(type, "Class name differs from file name '" + expected + "'")));
	}

	private static void localAndAnonymousClasses(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(LocalClassDeclarationStmt.class)
			.forEach(local -> findings.add(Finding.at(local, "Local class")));
	}

	private static void unreadParameters(InspectionContext context, List<Finding> findings) {
		List<CallableDeclaration<?>> callables = new ArrayList<>(
				context.compilationUnit().findAll(MethodDeclaration.class));
		callables.addAll(context.compilationUnit().findAll(ConstructorDeclaration.class));
		for (CallableDeclaration<?> callable : callables) {
			if (callable instanceof MethodDeclaration method && method.getBody().isEmpty()) {
				continue;
			}
			if (callable instanceof MethodDeclaration method && method.getAnnotationByName("Override").isPresent()) {
				continue;
			}
			for (Parameter parameter : callable.getParameters()) {
				boolean read = callable.findAll(NameExpr.class)
					.stream()
					.filter(name -> name.getNameAsString().equals(parameter.getNameAsString()))
					.anyMatch(ReportClassStructureIssuesTool::readAccess)
						|| callable.findAll(MethodReferenceExpr.class)
							.stream()
							.anyMatch(reference -> reference.getScope().toString().equals(parameter.getNameAsString()));
				if (!read) {
					findings.add(Finding.at(parameter, "Value passed as parameter is never read"));
				}
			}
		}
	}

	private static boolean readAccess(NameExpr name) {
		Node parent = name.getParentNode().orElse(null);
		return !(parent instanceof AssignExpr assignment && assignment.getTarget() == name
				&& assignment.getOperator() == AssignExpr.Operator.ASSIGN);
	}

	private static String simple(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

}

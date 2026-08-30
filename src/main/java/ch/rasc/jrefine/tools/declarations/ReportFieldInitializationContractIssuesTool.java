package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reports source-local non-null field initialization and mutable static initialization
 * hazards.
 */
public final class ReportFieldInitializationContractIssuesTool implements InspectionTool {

	private static final Set<String> NON_NULL_ANNOTATIONS = Set.of("android.annotation.NonNull",
			"androidx.annotation.NonNull", "edu.umd.cs.findbugs.annotations.NonNull", "jakarta.annotation.Nonnull",
			"javax.annotation.Nonnull", "lombok.NonNull", "org.checkerframework.checker.nullness.qual.NonNull",
			"org.eclipse.jdt.annotation.NonNull", "org.jetbrains.annotations.NotNull",
			"org.jspecify.annotations.NonNull");

	private static final Set<String> LOMBOK_CONSTRUCTOR_ANNOTATIONS = Set.of("AllArgsConstructor", "Builder", "Data",
			"NoArgsConstructor", "RequiredArgsConstructor", "Value");

	@Override
	public String id() {
		return "report-field-initialization-contract-issues";
	}

	@Override
	public String description() {
		return "Report non-null fields that may remain uninitialized and mutable static initialization reads";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (type.isInterface()) {
				continue;
			}
			nonNullFields(context, type, findings);
			nonFinalStaticInitializationReads(context, type, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void nonNullFields(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		if (lombokConstructors(type)) {
			return;
		}
		for (FieldDeclaration field : type.getFields()) {
			for (VariableDeclarator variable : field.getVariables()) {
				if (variable.getInitializer().isPresent() || !nonNullField(context, field, variable)) {
					continue;
				}
				if (field.isStatic()) {
					if (!assignedInInitializers(context, type, variable, true)) {
						findings.add(Finding.at(variable,
								"Static non-null field may not be initialized during class initialization"));
					}
				}
				else if (!assignedInInitializers(context, type, variable, false)
						&& constructorMayMissAssignment(context, type, variable)) {
					findings.add(Finding.at(variable,
							"Instance non-null field may not be initialized by every constructor"));
				}
			}
		}
	}

	private static boolean constructorMayMissAssignment(InspectionContext context, ClassOrInterfaceDeclaration type,
			VariableDeclarator field) {
		if (type.getConstructors().isEmpty()) {
			return true;
		}
		boolean someConstructorAssigns = type.getConstructors()
			.stream()
			.anyMatch(constructor -> assignsField(context, type, constructor.getBody(), field));
		for (ConstructorDeclaration constructor : type.getConstructors()) {
			if (assignsField(context, type, constructor.getBody(), field)) {
				continue;
			}
			boolean delegates = constructor.getBody()
				.getStatements()
				.stream()
				.filter(ExplicitConstructorInvocationStmt.class::isInstance)
				.map(ExplicitConstructorInvocationStmt.class::cast)
				.anyMatch(ExplicitConstructorInvocationStmt::isThis);
			if (delegates && someConstructorAssigns || callsInitializationHelper(type, constructor)) {
				continue;
			}
			return true;
		}
		return false;
	}

	private static boolean assignedInInitializers(InspectionContext context, ClassOrInterfaceDeclaration type,
			VariableDeclarator field, boolean staticInitializer) {
		return type.getMembers()
			.stream()
			.filter(InitializerDeclaration.class::isInstance)
			.map(InitializerDeclaration.class::cast)
			.filter(initializer -> initializer.isStatic() == staticInitializer)
			.anyMatch(initializer -> assignsField(context, type, initializer.getBody(), field)
					|| callsInitializationHelper(type, initializer));
	}

	private static boolean assignsField(InspectionContext context, ClassOrInterfaceDeclaration type, Node root,
			VariableDeclarator field) {
		return root.findAll(AssignExpr.class)
			.stream()
			.filter(assignment -> directlyWithin(assignment, root, type))
			.anyMatch(assignment -> fieldExpression(context, type, assignment.getTarget(), field.getNameAsString(),
					assignment));
	}

	private static boolean callsInitializationHelper(ClassOrInterfaceDeclaration type, Node root) {
		return root.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> directlyWithin(call, root, type))
			.filter(call -> call.getScope().isEmpty() || call.getScope().filter(ThisExpr.class::isInstance).isPresent())
			.anyMatch(call -> type.getMethodsByName(call.getNameAsString())
				.stream()
				.anyMatch(method -> method.getParameters().size() == call.getArguments().size()));
	}

	private static void nonFinalStaticInitializationReads(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		List<VariableDeclarator> mutableStaticFields = type.getFields()
			.stream()
			.filter(FieldDeclaration::isStatic)
			.filter(field -> !field.isFinal())
			.flatMap(field -> field.getVariables().stream())
			.toList();
		if (mutableStaticFields.isEmpty()) {
			return;
		}
		for (FieldDeclaration declaration : type.getFields()) {
			if (!declaration.isStatic()) {
				continue;
			}
			for (VariableDeclarator variable : declaration.getVariables()) {
				variable.getInitializer()
					.ifPresent(initializer -> initializationReads(context, type, initializer, mutableStaticFields,
							findings));
			}
		}
		type.getMembers()
			.stream()
			.filter(InitializerDeclaration.class::isInstance)
			.map(InitializerDeclaration.class::cast)
			.filter(InitializerDeclaration::isStatic)
			.forEach(initializer -> initializationReads(context, type, initializer.getBody(), mutableStaticFields,
					findings));
	}

	private static void initializationReads(InspectionContext context, ClassOrInterfaceDeclaration type, Node root,
			List<VariableDeclarator> fields, List<Finding> findings) {
		for (VariableDeclarator field : fields) {
			if (!before(field, root)) {
				continue;
			}
			String name = field.getNameAsString();
			root.findAll(NameExpr.class)
				.stream()
				.filter(reference -> directlyWithin(reference, root, type))
				.filter(reference -> reference.getNameAsString().equals(name))
				.filter(ReportFieldInitializationContractIssuesTool::readAccess)
				.filter(reference -> !TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(),
						name, reference))
				.forEach(reference -> findings
					.add(Finding.at(reference, "Non-final static field is read during class initialization")));
			root.findAll(FieldAccessExpr.class)
				.stream()
				.filter(reference -> directlyWithin(reference, root, type))
				.filter(reference -> reference.getNameAsString().equals(name))
				.filter(ReportFieldInitializationContractIssuesTool::readAccess)
				.filter(reference -> reference.getScope().toString().equals(type.getNameAsString()))
				.forEach(reference -> findings
					.add(Finding.at(reference, "Non-final static field is read during class initialization")));
		}
	}

	private static boolean readAccess(Node reference) {
		AssignExpr assignment = reference.findAncestor(AssignExpr.class).orElse(null);
		return assignment == null || assignment.getTarget() != reference
				|| assignment.getOperator() != AssignExpr.Operator.ASSIGN;
	}

	private static boolean nonNullField(InspectionContext context, FieldDeclaration field,
			VariableDeclarator variable) {
		ArrayList<AnnotationExpr> annotations = new ArrayList<>(field.getAnnotations());
		annotations.addAll(variable.getType().getAnnotations());
		return annotations.stream()
			.anyMatch(annotation -> knownNonNull(context, annotation.getNameAsString(),
					annotation.getName().getIdentifier()));
	}

	private static boolean knownNonNull(InspectionContext context, String spelling, String simpleName) {
		if (spelling.contains(".")) {
			return NON_NULL_ANNOTATIONS.contains(spelling);
		}
		if (context.compilationUnit()
			.findAll(AnnotationDeclaration.class)
			.stream()
			.anyMatch(annotation -> annotation.getNameAsString().equals(simpleName))) {
			return false;
		}
		List<String> explicit = context.compilationUnit()
			.getImports()
			.stream()
			.filter(imported -> !imported.isAsterisk() && imported.getName().getIdentifier().equals(simpleName))
			.map(imported -> imported.getNameAsString())
			.toList();
		if (!explicit.isEmpty()) {
			return explicit.stream().allMatch(NON_NULL_ANNOTATIONS::contains);
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.filter(imported -> imported.isAsterisk())
			.map(imported -> imported.getNameAsString() + "." + simpleName)
			.anyMatch(NON_NULL_ANNOTATIONS::contains);
	}

	private static boolean lombokConstructors(ClassOrInterfaceDeclaration type) {
		return type.getAnnotations()
			.stream()
			.map(annotation -> annotation.getName().getIdentifier())
			.anyMatch(LOMBOK_CONSTRUCTOR_ANNOTATIONS::contains);
	}

	private static boolean fieldExpression(InspectionContext context, ClassOrInterfaceDeclaration owner,
			Expression expression, String field, Node use) {
		if (expression instanceof NameExpr name && name.getNameAsString().equals(field)) {
			return !TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), field, use);
		}
		return expression instanceof FieldAccessExpr access && access.getNameAsString().equals(field)
				&& (access.getScope() instanceof ThisExpr
						|| access.getScope().toString().equals(owner.getNameAsString()));
	}

	private static boolean directlyWithin(Node node, Node root, ClassOrInterfaceDeclaration owner) {
		Node current = node;
		while (current != root) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof LambdaExpr || parent instanceof MethodDeclaration
					|| parent instanceof TypeDeclaration<?> && parent != owner) {
				return false;
			}
			current = parent;
		}
		return true;
	}

	private static boolean before(Node left, Node right) {
		Position leftPosition = left.getBegin().orElse(Position.HOME);
		Position rightPosition = right.getBegin().orElse(Position.HOME);
		return leftPosition.line < rightPosition.line
				|| leftPosition.line == rightPosition.line && leftPosition.column < rightPosition.column;
	}

}

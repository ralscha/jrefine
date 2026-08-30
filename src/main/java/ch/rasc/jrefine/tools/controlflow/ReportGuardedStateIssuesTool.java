package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reports source-local contracts expressed by common GuardedBy and Immutable annotations.
 */
public final class ReportGuardedStateIssuesTool implements PolicyInspectionTool {

	private static final Set<String> GUARDED_BY_ANNOTATIONS = Set.of("net.jcip.annotations.GuardedBy",
			"javax.annotation.concurrent.GuardedBy", "com.google.errorprone.annotations.concurrent.GuardedBy",
			"android.annotation.GuardedBy", "androidx.annotation.GuardedBy");

	private static final Set<String> IMMUTABLE_ANNOTATIONS = Set.of("net.jcip.annotations.Immutable",
			"javax.annotation.concurrent.Immutable", "com.google.errorprone.annotations.Immutable");

	@Override
	public String id() {
		return "report-guarded-state-issues";
	}

	@Override
	public String description() {
		return "Report inconsistent GuardedBy and Immutable source-local contracts";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			annotationContracts(context, type, findings);
			unguardedAccesses(context, type, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void annotationContracts(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		if (!type.isInterface() && knownAnnotation(context, type, "Immutable", IMMUTABLE_ANNOTATIONS).isPresent()) {
			type.getFields()
				.stream()
				.filter(field -> !field.isStatic() && !field.isFinal())
				.forEach(field -> findings
					.add(Finding.at(field, "Non-final instance field is declared in an @Immutable class")));
		}
		for (FieldDeclaration field : type.getFields()) {
			guard(context, field).ifPresent(value -> guardKindMismatch(type, field.isStatic(), field, value, findings));
		}
		for (MethodDeclaration method : directMethods(type)) {
			guard(context, method)
				.ifPresent(value -> guardKindMismatch(type, method.isStatic(), method, value, findings));
		}
	}

	private static void guardKindMismatch(ClassOrInterfaceDeclaration type, boolean memberStatic, Node member,
			String guard, List<Finding> findings) {
		if ("this".equals(guard) && memberStatic) {
			findings.add(Finding.at(member, "Static member is guarded by this instance"));
			return;
		}
		FieldDeclaration lock = guardField(type, guard).orElse(null);
		if (lock == null) {
			return;
		}
		if (memberStatic && !lock.isStatic()) {
			findings.add(Finding.at(member, "Static member is guarded by an instance field"));
		}
		else if (!memberStatic && lock.isStatic()) {
			findings.add(Finding.at(member, "Instance member is guarded by a static field"));
		}
	}

	private static void unguardedAccesses(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		for (FieldDeclaration field : type.getFields()) {
			String guard = guard(context, field).orElse(null);
			if (guard == null) {
				continue;
			}
			for (VariableDeclarator variable : field.getVariables()) {
				fieldAccesses(context, type, variable.getNameAsString()).stream()
					.filter(access -> !initializationAccess(access, type))
					.filter(access -> !guardHeld(context, type, access, guard))
					.forEach(access -> findings.add(Finding.at(access,
							"Field guarded by '" + guard + "' is accessed without holding that guard")));
			}
		}
		for (MethodDeclaration guarded : directMethods(type)) {
			String guard = guard(context, guarded).orElse(null);
			if (guard == null) {
				continue;
			}
			type.findAll(MethodCallExpr.class)
				.stream()
				.filter(call -> call.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) == type)
				.filter(call -> call.getNameAsString().equals(guarded.getNameAsString())
						&& call.getArguments().size() == guarded.getParameters().size())
				.filter(ReportGuardedStateIssuesTool::sourceLocalReceiver)
				.filter(call -> !initializationAccess(call, type))
				.filter(call -> !guardHeld(context, type, call, guard))
				.forEach(call -> findings
					.add(Finding.at(call, "Method guarded by '" + guard + "' is called without holding that guard")));
		}
	}

	private static List<Node> fieldAccesses(InspectionContext context, ClassOrInterfaceDeclaration type, String field) {
		ArrayList<Node> result = new ArrayList<>();
		type.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) == type)
			.filter(name -> name.getNameAsString().equals(field))
			.filter(name -> !TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), field,
					name))
			.forEach(result::add);
		type.findAll(FieldAccessExpr.class)
			.stream()
			.filter(access -> access.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) == type)
			.filter(access -> access.getNameAsString().equals(field))
			.filter(access -> access.getScope() instanceof ThisExpr
					|| access.getScope().toString().equals(type.getNameAsString()))
			.forEach(result::add);
		return result;
	}

	private static boolean sourceLocalReceiver(MethodCallExpr call) {
		return call.getScope().isEmpty() || call.getScope().filter(ThisExpr.class::isInstance).isPresent();
	}

	private static boolean initializationAccess(Node access, ClassOrInterfaceDeclaration type) {
		return access.findAncestor(ConstructorDeclaration.class)
			.filter(constructor -> constructor.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) == type)
			.isPresent()
				|| access.findAncestor(InitializerDeclaration.class)
					.filter(initializer -> initializer.findAncestor(ClassOrInterfaceDeclaration.class)
						.orElse(null) == type)
					.isPresent()
				|| access.findAncestor(FieldDeclaration.class).isPresent();
	}

	private static boolean guardHeld(InspectionContext context, ClassOrInterfaceDeclaration type, Node access,
			String guard) {
		Node current = access;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof SynchronizedStmt statement
					&& guard.equals(monitorName(type, statement.getExpression()))) {
				return true;
			}
			if (current instanceof MethodDeclaration method) {
				if (guard(context, method).filter(guard::equals).isPresent()) {
					return true;
				}
				if (method.isSynchronized() && (guard.equals("this") && !method.isStatic()
						|| guard.equals(type.getNameAsString() + ".class") && method.isStatic())) {
					return true;
				}
				return false;
			}
			if (current instanceof ClassOrInterfaceDeclaration && current != type) {
				return false;
			}
		}
		return false;
	}

	private static String monitorName(ClassOrInterfaceDeclaration type, Expression expression) {
		if (expression instanceof ThisExpr) {
			return "this";
		}
		if (expression instanceof NameExpr name) {
			return name.getNameAsString();
		}
		if (expression instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) {
			return access.getNameAsString();
		}
		String text = expression.toString().replace(" ", "");
		return text.equals(type.getNameAsString() + ".class") ? text : null;
	}

	private static Optional<FieldDeclaration> guardField(ClassOrInterfaceDeclaration type, String guard) {
		String name = guard.startsWith("this.") ? guard.substring("this.".length()) : guard;
		String typePrefix = type.getNameAsString() + ".";
		if (name.startsWith(typePrefix)) {
			name = name.substring(typePrefix.length());
		}
		if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
			return Optional.empty();
		}
		String fieldName = name;
		return type.getFields()
			.stream()
			.filter(field -> field.getVariables()
				.stream()
				.anyMatch(variable -> variable.getNameAsString().equals(fieldName)))
			.findFirst();
	}

	private static Optional<String> guard(InspectionContext context, NodeWithAnnotations<?> declaration) {
		AnnotationExpr annotation = knownAnnotation(context, declaration, "GuardedBy", GUARDED_BY_ANNOTATIONS)
			.orElse(null);
		if (annotation instanceof SingleMemberAnnotationExpr single
				&& single.getMemberValue() instanceof StringLiteralExpr literal) {
			return Optional.of(literal.asString().trim());
		}
		if (annotation != null && annotation.isNormalAnnotationExpr()) {
			return annotation.asNormalAnnotationExpr()
				.getPairs()
				.stream()
				.filter(pair -> "value".equals(pair.getNameAsString()))
				.map(pair -> pair.getValue())
				.filter(StringLiteralExpr.class::isInstance)
				.map(StringLiteralExpr.class::cast)
				.map(StringLiteralExpr::asString)
				.map(String::trim)
				.findFirst();
		}
		return Optional.empty();
	}

	private static Optional<AnnotationExpr> knownAnnotation(InspectionContext context,
			NodeWithAnnotations<?> declaration, String simpleName, Set<String> knownNames) {
		return declaration.getAnnotations()
			.stream()
			.filter(annotation -> annotation.getName().getIdentifier().equals(simpleName))
			.filter(annotation -> knownAnnotationName(context, annotation.getNameAsString(), simpleName, knownNames))
			.findFirst();
	}

	private static boolean knownAnnotationName(InspectionContext context, String spelling, String simpleName,
			Set<String> knownNames) {
		if (spelling.contains(".")) {
			return knownNames.contains(spelling);
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
			return explicit.stream().allMatch(knownNames::contains);
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.filter(imported -> imported.isAsterisk())
			.map(imported -> imported.getNameAsString() + "." + simpleName)
			.anyMatch(knownNames::contains);
	}

	private static List<MethodDeclaration> directMethods(ClassOrInterfaceDeclaration type) {
		return type.getMembers()
			.stream()
			.filter(MethodDeclaration.class::isInstance)
			.map(MethodDeclaration.class::cast)
			.toList();
	}

}

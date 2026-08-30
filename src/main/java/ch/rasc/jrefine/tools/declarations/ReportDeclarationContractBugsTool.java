package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
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
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import java.util.Objects;

/**
 * Reports declaration-level contract, reflection, utility-class, and package mistakes.
 */
public final class ReportDeclarationContractBugsTool implements InspectionTool {

	@Override
	public String id() {
		return "report-declaration-contract-bugs";
	}

	@Override
	public String description() {
		return "Report broken declaration contracts, utility instantiation, reflection, and package mistakes";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		confusingMain(context, findings);
		safeVarargs(context, findings);
		contracts(context, findings);
		copyConstructors(context, findings);
		utilityInstantiation(context, findings);
		comparatorReferences(context, findings);
		recordAnnotations(context, findings);
		sourceAnnotations(context, findings);
		invocationHandlers(context, findings);
		inheritedStaticAccess(context, findings);
		returnRanges(context, findings);
		wrongPackage(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void confusingMain(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(method -> "main".equals(method.getNameAsString()))
			.filter(method -> !method.isPublic() || !method.isStatic() || !method.getType().isVoidType()
					|| method.getParameters().size() != 1
					|| !("String[]".equals(method.getParameter(0).getType().asString())
							|| method.getParameter(0).isVarArgs()
									&& "String".equals(method.getParameter(0).getType().asString())))
			.forEach(method -> findings
				.add(Finding.at(method, "Confusing main() method does not have the standard entry-point signature")));
	}

	private static void safeVarargs(InspectionContext context, List<Finding> findings) {
		List<CallableDeclaration<?>> callables = new ArrayList<>(
				context.compilationUnit().findAll(MethodDeclaration.class));
		callables.addAll(context.compilationUnit().findAll(ConstructorDeclaration.class));
		for (CallableDeclaration<?> callable : callables) {
			if (callable.getAnnotations()
				.stream()
				.noneMatch(annotation -> "SafeVarargs".equals(annotation.getName().getIdentifier()))
					|| callable.getParameters().stream().noneMatch(parameter -> parameter.isVarArgs())) {
				continue;
			}
			String parameter = callable.getParameters()
				.stream()
				.filter(value -> value.isVarArgs())
				.findFirst()
				.orElseThrow()
				.getNameAsString();
			boolean writes = callable.findAll(AssignExpr.class)
				.stream()
				.anyMatch(assignment -> assignment.getTarget() instanceof NameExpr name
						&& name.getNameAsString().equals(parameter)
						|| assignment.getTarget() instanceof ArrayAccessExpr access
								&& access.getName().toString().equals(parameter));
			if (writes) {
				findings.add(Finding.at(callable,
						"@SafeVarargs method performs potentially unsafe writes to its vararg parameter"));
			}
		}
	}

	private static void contracts(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			for (AnnotationExpr annotation : method.getAnnotations()) {
				if (!"Contract".equals(annotation.getName().getIdentifier())) {
					continue;
				}
				String value = annotationString(annotation).orElse(null);
				if (value == null) {
					continue;
				}
				for (String clause : value.split(";")) {
					int arrow = clause.indexOf("->");
					if (arrow < 0) {
						findings.add(Finding.at(annotation, "Malformed @Contract clause has no '->'"));
						break;
					}
					String arguments = clause.substring(0, arrow).trim();
					int count = arguments.isEmpty() ? 0 : arguments.split(",").length;
					if (count != method.getParameters().size()) {
						findings
							.add(Finding.at(annotation, "@Contract clause parameter count does not match the method"));
						break;
					}
				}
			}
		}
	}

	private static Optional<String> annotationString(AnnotationExpr annotation) {
		if (annotation instanceof SingleMemberAnnotationExpr single
				&& single.getMemberValue() instanceof StringLiteralExpr literal) {
			return Optional.of(literal.asString());
		}
		if (annotation.isNormalAnnotationExpr()) {
			return annotation.asNormalAnnotationExpr()
				.getPairs()
				.stream()
				.filter(pair -> "value".equals(pair.getNameAsString()))
				.map(MemberValuePair::getValue)
				.filter(StringLiteralExpr.class::isInstance)
				.map(StringLiteralExpr.class::cast)
				.map(StringLiteralExpr::asString)
				.findFirst();
		}
		return Optional.empty();
	}

	private static void copyConstructors(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			Set<String> fields = type.getFields()
				.stream()
				.filter(field -> !field.isStatic())
				.flatMap(field -> field.getVariables().stream())
				.map(variable -> variable.getNameAsString())
				.collect(java.util.stream.Collectors.toSet());
			for (ConstructorDeclaration constructor : type.getConstructors()) {
				if (constructor.getParameters().size() != 1
						|| !simple(constructor.getParameter(0).getType().asString()).equals(type.getNameAsString())) {
					continue;
				}
				Set<String> copied = constructor.findAll(AssignExpr.class)
					.stream()
					.map(assignment -> assignedField(assignment).orElse(null))
					.filter(Objects::nonNull)
					.collect(java.util.stream.Collectors.toSet());
				HashSet<String> missing = new HashSet<>(fields);
				missing.removeAll(copied);
				if (!missing.isEmpty()) {
					findings.add(Finding.at(constructor,
							"Copy constructor does not copy fields: " + String.join(", ", missing)));
				}
			}
		}
	}

	private static Optional<String> assignedField(AssignExpr assignment) {
		if (assignment.getTarget() instanceof NameExpr name) {
			return Optional.of(name.getNameAsString());
		}
		if (assignment.getTarget() instanceof FieldAccessExpr access && access.getScope().isThisExpr()) {
			return Optional.of(access.getNameAsString());
		}
		return Optional.empty();
	}

	private static void utilityInstantiation(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			ClassOrInterfaceDeclaration type = context.compilationUnit()
				.findAll(ClassOrInterfaceDeclaration.class)
				.stream()
				.filter(value -> value.getNameAsString().equals(creation.getType().getNameAsString()))
				.findFirst()
				.orElse(null);
			boolean createdInsideType = type != null && AstSupport.ancestor(creation, ClassOrInterfaceDeclaration.class)
				.filter(owner -> owner == type)
				.isPresent();
			if (type != null && utility(type) && !createdInsideType) {
				findings.add(Finding.at(creation, "Instantiation of utility class"));
			}
		}
	}

	private static boolean utility(ClassOrInterfaceDeclaration type) {
		List<BodyDeclaration<?>> members = type.getMembers()
			.stream()
			.filter(member -> !(member instanceof ConstructorDeclaration))
			.toList();
		if (members.isEmpty()) {
			return false;
		}
		boolean allStatic = members.stream()
			.allMatch(member -> member instanceof MethodDeclaration method && method.isStatic()
					|| member instanceof FieldDeclaration field && field.isStatic()
					|| member instanceof ClassOrInterfaceDeclaration nested && nested.isStatic());
		List<ConstructorDeclaration> constructors = type.getConstructors();
		return allStatic
				&& (constructors.isEmpty() || constructors.stream().allMatch(ConstructorDeclaration::isPrivate));
	}

	private static void comparatorReferences(InspectionContext context, List<Finding> findings) {
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (!"Comparator".equals(simple(variable.getType().asString()))
					|| !(variable.getInitializer().orElse(null) instanceof MethodReferenceExpr reference)) {
				continue;
			}
			if (Set.of("indexOf", "lastIndexOf", "hashCode", "length", "size").contains(reference.getIdentifier())) {
				findings.add(Finding.at(reference, "Invalid method reference used for Comparator contract"));
			}
		}
	}

	private static void recordAnnotations(InspectionContext context, List<Finding> findings) {
		HashMap<String, String> targets = new HashMap<>();
		for (AnnotationDeclaration annotation : context.compilationUnit().findAll(AnnotationDeclaration.class)) {
			annotation.getAnnotations()
				.stream()
				.filter(value -> "Target".equals(value.getName().getIdentifier()))
				.findFirst()
				.ifPresent(target -> targets.put(annotation.getNameAsString(), target.toString()));
		}
		for (RecordDeclaration record : context.compilationUnit().findAll(RecordDeclaration.class)) {
			record.getParameters().forEach(component -> component.getAnnotations().forEach(annotation -> {
				String target = targets.get(annotation.getName().getIdentifier());
				if (target != null && !target.contains("RECORD_COMPONENT") && !target.contains("FIELD")
						&& !target.contains("METHOD") && !target.contains("PARAMETER")
						&& !target.contains("TYPE_USE")) {
					findings.add(Finding.at(annotation,
							"Meaningless record annotation has no applicable propagation target"));
				}
			}));
		}
	}

	private static void sourceAnnotations(InspectionContext context, List<Finding> findings) {
		Set<String> sourceOnly = context.compilationUnit()
			.findAll(AnnotationDeclaration.class)
			.stream()
			.filter(annotation -> annotation.getAnnotations()
				.stream()
				.anyMatch(value -> "Retention".equals(value.getName().getIdentifier())
						&& value.toString().contains("SOURCE")))
			.map(AnnotationDeclaration::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		if (sourceOnly.isEmpty()) {
			return;
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!Set.of("isAnnotationPresent", "getAnnotation", "getAnnotationsByType")
				.contains(call.getNameAsString())) {
				continue;
			}
			call.getArguments()
				.stream()
				.filter(ClassExpr.class::isInstance)
				.map(ClassExpr.class::cast)
				.filter(clazz -> sourceOnly.contains(simple(clazz.getType().asString())))
				.forEach(clazz -> findings.add(Finding.at(call, "Reflective access to a source-only annotation")));
		}
	}

	private static void invocationHandlers(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> type.getImplementedTypes()
				.stream()
				.anyMatch(parent -> "InvocationHandler".equals(parent.getNameAsString())))
			.forEach(type -> type.getMethodsByName("invoke").stream().findFirst().ifPresent(method -> {
				String source = method.toString();
				if (!source.contains("equals") || !source.contains("hashCode") || !source.contains("toString")) {
					findings
						.add(Finding.at(method, "Suspicious InvocationHandler does not proxy standard Object methods"));
				}
			}));
	}

	private static void inheritedStaticAccess(InspectionContext context, List<Finding> findings) {
		List<ClassOrInterfaceDeclaration> types = context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class);
		for (ClassOrInterfaceDeclaration subtype : types) {
			for (ClassOrInterfaceType parentRef : subtype.getExtendedTypes()) {
				ClassOrInterfaceDeclaration parent = types.stream()
					.filter(type -> type.getNameAsString().equals(parentRef.getNameAsString()))
					.findFirst()
					.orElse(null);
				if (parent == null) {
					continue;
				}
				Set<String> staticFields = parent.getFields()
					.stream()
					.filter(field -> field.isStatic())
					.flatMap(field -> field.getVariables().stream())
					.map(variable -> variable.getNameAsString())
					.collect(java.util.stream.Collectors.toSet());
				Set<String> staticMethods = parent.getMethods()
					.stream()
					.filter(MethodDeclaration::isStatic)
					.map(MethodDeclaration::getNameAsString)
					.collect(java.util.stream.Collectors.toSet());
				context.compilationUnit()
					.findAll(FieldAccessExpr.class)
					.stream()
					.filter(access -> access.getScope().toString().equals(subtype.getNameAsString())
							&& staticFields.contains(access.getNameAsString()))
					.forEach(access -> findings
						.add(Finding.at(access, "Static field is referenced via subclass instead of declaring class")));
				context.compilationUnit()
					.findAll(MethodCallExpr.class)
					.stream()
					.filter(call -> call.getScope()
						.filter(scope -> scope.toString().equals(subtype.getNameAsString()))
						.isPresent() && staticMethods.contains(call.getNameAsString()))
					.forEach(call -> findings
						.add(Finding.at(call, "Static method is referenced via subclass instead of declaring class")));
				context.compilationUnit()
					.findAll(ClassOrInterfaceType.class)
					.stream()
					.filter(reference -> reference.getScope()
						.filter(scope -> scope.toString().equals(subtype.getNameAsString()))
						.isPresent()
							&& parent.getMembers()
								.stream()
								.filter(ClassOrInterfaceDeclaration.class::isInstance)
								.map(ClassOrInterfaceDeclaration.class::cast)
								.anyMatch(nested -> nested.getNameAsString().equals(reference.getNameAsString())))
					.forEach(reference -> findings.add(Finding.at(reference,
							"Inner class is referenced via subclass instead of declaring class")));
			}
		}
	}

	private static void returnRanges(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			Range range = method.getAnnotations()
				.stream()
				.filter(annotation -> Set.of("Range", "IntRange").contains(annotation.getName().getIdentifier()))
				.findFirst()
				.flatMap(ReportDeclarationContractBugsTool::range)
				.orElse(null);
			if (range == null || method.getBody().isEmpty()) {
				continue;
			}
			for (ReturnStmt returned : method.getBody().orElseThrow().findAll(ReturnStmt.class)) {
				returned.getExpression()
					.flatMap(ReportDeclarationContractBugsTool::integer)
					.filter(value -> value < range.minimum() || value > range.maximum())
					.ifPresent(
							value -> findings.add(Finding.at(returned, "Return value is outside the declared range")));
			}
		}
	}

	private static Optional<Range> range(AnnotationExpr annotation) {
		if (!annotation.isNormalAnnotationExpr()) {
			return Optional.empty();
		}
		Long from = null;
		Long to = null;
		for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
			Long value = integer(pair.getValue()).map(Integer::longValue).orElse(null);
			if (Set.of("from", "min").contains(pair.getNameAsString())) {
				from = value;
			}
			if (Set.of("to", "max").contains(pair.getNameAsString())) {
				to = value;
			}
		}
		return from != null && to != null ? Optional.of(new Range(from, to)) : Optional.empty();
	}

	private static void wrongPackage(InspectionContext context, List<Finding> findings) {
		if (context.compilationUnit().getPackageDeclaration().isEmpty() || context.path().getParent() == null
				|| context.path().getNameCount() < 2) {
			return;
		}
		String packageName = context.compilationUnit()
			.getPackageDeclaration()
			.orElseThrow()
			.getNameAsString()
			.replace('.', java.io.File.separatorChar);
		String parent = context.path().toAbsolutePath().normalize().getParent().toString();
		if (!parent.endsWith(packageName)) {
			findings.add(Finding.at(context.compilationUnit().getPackageDeclaration().orElseThrow(),
					"Package statement does not correspond to the source file directory"));
		}
	}

	private static Optional<Integer> integer(Expression expression) {
		if (expression instanceof IntegerLiteralExpr literal) {
			return Optional.of(literal.asNumber().intValue());
		}
		if (expression instanceof UnaryExpr unary
				&& unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS) {
			return integer(unary.getExpression()).map(value -> -value);
		}
		return Optional.empty();
	}

	private static String simple(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		while (currentType.endsWith("[]")) {
			currentType = currentType.substring(0, currentType.length() - 2);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

	private record Range(long minimum, long maximum) {
	}

}

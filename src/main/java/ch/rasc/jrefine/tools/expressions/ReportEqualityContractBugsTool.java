package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.AssertStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

/** Reports equality, hashing, comparison, and incompatible-type mistakes. */
public final class ReportEqualityContractBugsTool implements InspectionTool {

	private static final Set<String> GENERATED_EQUALITY_ANNOTATIONS = Set.of("com.google.auto.value.AutoValue",
			"lombok.Data", "lombok.EqualsAndHashCode", "lombok.Value", "org.immutables.value.Value.Immutable");

	private static final Set<String> IDENTITY_EQUALS_TYPES = Set.of("StringBuilder", "StringBuffer", "AtomicBoolean",
			"AtomicInteger", "AtomicLong", "AtomicReference", "AtomicIntegerArray", "AtomicLongArray",
			"AtomicReferenceArray");

	private static final Set<String> FINAL_VALUE_TYPES = Set.of("String", "Boolean", "Byte", "Short", "Integer", "Long",
			"Float", "Double", "Character", "BigInteger", "BigDecimal", "UUID");

	private static final Set<String> NUMBER_TYPES = Set.of("Byte", "Short", "Integer", "Long", "Float", "Double",
			"BigInteger", "BigDecimal");

	@Override
	public String id() {
		return "report-equality-contract-bugs";
	}

	@Override
	public String description() {
		return "Report broken equals/hashCode/Comparable contracts and suspicious comparisons";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		declarations(context, findings);
		equalityCalls(context, findings);
		comparisons(context, findings);
		casts(context, findings);
		shallowArrayCalls(context, findings);
		compareUsage(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void declarations(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (type.isInterface()) {
				continue;
			}
			boolean equalsObject = type.getMethodsByName("equals")
				.stream()
				.anyMatch(ReportEqualityContractBugsTool::equalsObject);
			boolean generatedEquality = generatedEquality(context, type);
			boolean hashCode = type.getMethodsByName("hashCode")
				.stream()
				.anyMatch(method -> method.getParameters().isEmpty());
			if (type.getImplementedTypes()
				.stream()
				.anyMatch(implemented -> "Comparable".equals(implemented.getNameAsString())) && !equalsObject
					&& !generatedEquality) {
				findings.add(Finding.at(type, "Comparable is implemented but equals(Object) is not overridden"));
			}
			if (!generatedEquality && equalsObject != hashCode) {
				findings.add(Finding.at(type, "equals() and hashCode() should be overridden together"));
			}
			type.getMethodsByName("equal")
				.stream()
				.filter(method -> method.getParameters().size() == 1)
				.forEach(method -> findings
					.add(Finding.at(method, "Method named equal() may be a misspelling of equals()")));
			for (MethodDeclaration method : type.getMethodsByName("equals")) {
				if (method.getParameters().size() == 1 && !equalsObject(method)) {
					findings.add(Finding.at(method, "Covariant equals() does not override equals(Object)"));
				}
				if (equalsObject(method) && method.getBody().isPresent()
						&& method.getBody().orElseThrow().findAll(InstanceOfExpr.class).isEmpty()
						&& method.getBody()
							.orElseThrow()
							.findAll(MethodCallExpr.class)
							.stream()
							.noneMatch(call -> "getClass".equals(call.getNameAsString()))) {
					findings.add(Finding.at(method, "equals() method does not check the class of its parameter"));
				}
			}
			nonFinalFields(type, findings);
			suspiciousCompareImplementations(type, findings);
		}
	}

	private static boolean equalsObject(MethodDeclaration method) {
		return "equals".equals(method.getNameAsString()) && method.getParameters().size() == 1
				&& "Object".equals(simple(method.getParameter(0).getType().asString()))
				&& method.getType().isPrimitiveType()
				&& method.getType()
					.asPrimitiveType()
					.getType() == com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN;
	}

	private static boolean generatedEquality(InspectionContext context, ClassOrInterfaceDeclaration type) {
		return type.getAnnotations()
			.stream()
			.map(annotation -> annotation.getNameAsString())
			.anyMatch(spelling -> GENERATED_EQUALITY_ANNOTATIONS.stream()
				.anyMatch(canonical -> annotationResolvesTo(context, spelling, canonical)));
	}

	private static boolean annotationResolvesTo(InspectionContext context, String spelling, String canonical) {
		if (spelling.equals(canonical)) {
			return true;
		}
		int separator = canonical.lastIndexOf('.');
		String simple = canonical.substring(separator + 1);
		if (spelling.equals(simple) && context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic()
					&& (!imported.isAsterisk() && imported.getNameAsString().equals(canonical)
							|| imported.isAsterisk() && canonical.startsWith(imported.getNameAsString() + ".")))) {
			return true;
		}
		int ownerSeparator = canonical.lastIndexOf('.', separator - 1);
		if (ownerSeparator < 0) {
			return false;
		}
		String owner = canonical.substring(0, separator);
		String ownerSimple = owner.substring(ownerSeparator + 1);
		return spelling.equals(ownerSimple + "." + simple) && context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && !imported.isAsterisk()
					&& imported.getNameAsString().equals(owner));
	}

	private static void nonFinalFields(ClassOrInterfaceDeclaration type, List<Finding> findings) {
		Set<String> mutable = type.getFields()
			.stream()
			.filter(field -> !field.isFinal())
			.flatMap(field -> field.getVariables().stream())
			.map(variable -> variable.getNameAsString())
			.collect(java.util.stream.Collectors.toSet());
		if (mutable.isEmpty()) {
			return;
		}
		for (MethodDeclaration method : type.getMethods()) {
			String kind = switch (method.getNameAsString()) {
				case "equals" -> "equals()";
				case "hashCode" -> "hashCode()";
				case "compareTo" -> "compareTo()";
				default -> null;
			};
			if (kind == null || method.getBody().isEmpty()) {
				continue;
			}
			method.getBody()
				.orElseThrow()
				.findAll(NameExpr.class)
				.stream()
				.filter(name -> mutable.contains(name.getNameAsString()))
				.findFirst()
				.ifPresent(name -> findings.add(Finding.at(name, "Non-final field referenced in " + kind)));
		}
	}

	private static void suspiciousCompareImplementations(ClassOrInterfaceDeclaration type, List<Finding> findings) {
		for (MethodDeclaration method : type.getMethods()) {
			if (!"compareTo".equals(method.getNameAsString()) && !"compare".equals(method.getNameAsString())
					|| method.getBody().isEmpty()) {
				continue;
			}
			List<ReturnStmt> returns = method.getBody().orElseThrow().findAll(ReturnStmt.class);
			if (returns.stream()
				.anyMatch(returned -> returned.getExpression()
					.filter(expression -> expression instanceof BinaryExpr binary
							&& binary.getOperator() == BinaryExpr.Operator.MINUS)
					.isPresent())) {
				findings.add(Finding.at(method, "Subtraction in comparison method may overflow"));
			}
			HashSet<Integer> signs = new HashSet<>();
			boolean allLiteralReturns = !returns.isEmpty();
			for (ReturnStmt returned : returns) {
				Optional<Integer> value = returned.getExpression().flatMap(ReportEqualityContractBugsTool::integer);
				if (value.isPresent()) {
					signs.add(Integer.signum(value.orElseThrow()));
				}
				else {
					allLiteralReturns = false;
				}
			}
			if (allLiteralReturns && signs.size() >= 2
					&& (!signs.contains(-1) || !signs.contains(0) || !signs.contains(1))) {
				findings.add(Finding.at(method,
						"Suspicious Comparator implementation does not represent all comparison outcomes"));
			}
		}
	}

	private static void equalityCalls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isEmpty()) {
				continue;
			}
			Expression scope = call.getScope().orElseThrow();
			String scopeType = typeOf(context, scope, call).orElse("");
			if ("equals".equals(call.getNameAsString()) && call.getArguments().size() == 1) {
				Expression argument = call.getArgument(0);
				String argumentType = typeOf(context, argument, call).orElse("");
				if (array(scopeType) && array(argumentType)) {
					findings.add(Finding.at(call, "equals() called on array; use an Arrays equality method"));
				}
				if (IDENTITY_EQUALS_TYPES.contains(simple(scopeType))) {
					findings.add(Finding.at(call, "equals() called on a class that retains identity equality"));
				}
				if ("String".equals(simple(scopeType)) && "CharSequence".equals(simple(argumentType))) {
					findings.add(Finding.at(call, "String.equals() is called with a CharSequence argument"));
				}
				if (inconvertible(scopeType, argumentType)) {
					findings.add(Finding.at(call, "equals() compares objects of inconvertible types"));
				}
			}
			if (Set.of("equals", "compareTo", "compareToIgnoreCase").contains(call.getNameAsString())
					&& call.getArguments().size() == 1 && scope.equals(call.getArgument(0))
					&& !assertionContext(call)) {
				findings.add(Finding.at(call, "Object is compared with itself"));
			}
			if (Set.of("hashCode", "toString").contains(call.getNameAsString()) && call.getArguments().isEmpty()
					&& array(scopeType)) {
				findings.add(Finding.at(call,
						call.getNameAsString() + "() called on array uses identity rather than array contents"));
			}
			if ("toString".equals(call.getNameAsString()) && call.getArguments().isEmpty()
					&& defaultToString(context, scopeType)) {
				findings.add(Finding.at(call, "Call resolves to the default Object.toString() implementation"));
			}
		}
	}

	private static void comparisons(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			Operator operator = binary.getOperator();
			if (Set.of(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.LESS,
					BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS)
				.contains(operator) && binary.getLeft().equals(binary.getRight()) && !assertionContext(binary)) {
				findings.add(Finding.at(binary, "Expression is compared to itself"));
			}
			if (operator == BinaryExpr.Operator.PLUS && (array(typeOf(context, binary.getLeft(), binary).orElse(""))
					|| array(typeOf(context, binary.getRight(), binary).orElse("")))) {
				findings
					.add(Finding.at(binary, "Array is converted with the default toString() in string concatenation"));
			}
			if (operator != BinaryExpr.Operator.EQUALS && operator != BinaryExpr.Operator.NOT_EQUALS) {
				continue;
			}
			String leftType = typeOf(context, binary.getLeft(), binary).orElse("");
			String rightType = typeOf(context, binary.getRight(), binary).orElse("");
			boolean fastPath = identityFastPath(binary);
			if (array(leftType) && array(rightType) && !fastPath) {
				findings.add(Finding.at(binary, "Array comparison uses identity equality instead of Arrays.equals()"));
			}
			else if ("String".equals(simple(leftType)) && "String".equals(simple(rightType)) && !fastPath) {
				findings.add(Finding.at(binary, "String comparison uses identity equality instead of equals()"));
			}
			else if (NUMBER_TYPES.contains(simple(leftType)) && NUMBER_TYPES.contains(simple(rightType)) && !fastPath) {
				findings.add(Finding.at(binary, "Number comparison uses identity equality instead of equals()"));
			}
			else if ("Object".equals(simple(leftType)) && "Object".equals(simple(rightType)) && !fastPath) {
				findings.add(Finding.at(binary, "Object comparison uses identity equality instead of equals()"));
			}
			if (binary.getLeft() instanceof ObjectCreationExpr || binary.getRight() instanceof ObjectCreationExpr) {
				findings.add(Finding.at(binary, "New object is compared using identity equality"));
			}
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!Set.of("print", "println", "printf").contains(call.getNameAsString())) {
				continue;
			}
			call.getArguments()
				.stream()
				.filter(argument -> array(typeOf(context, argument, call).orElse("")))
				.forEach(argument -> findings
					.add(Finding.at(argument, "Array is passed to a printing method using default toString()")));
		}
	}

	private static boolean identityFastPath(BinaryExpr binary) {
		if (binary.getOperator() != BinaryExpr.Operator.EQUALS) {
			return false;
		}
		Node current = binary;
		Node parent = current.getParentNode().orElse(null);
		while (parent instanceof com.github.javaparser.ast.expr.EnclosedExpr) {
			current = parent;
			parent = current.getParentNode().orElse(null);
		}
		if (parent instanceof BinaryExpr disjunction && disjunction.getOperator() == BinaryExpr.Operator.OR
				&& (disjunction.getLeft() == current || disjunction.getLeft().isAncestorOf(current))
				&& disjunction.getRight()
					.findAll(MethodCallExpr.class)
					.stream()
					.anyMatch(call -> "equals".equals(call.getNameAsString()))) {
			return true;
		}
		IfStmt conditional = binary.findAncestor(IfStmt.class)
			.filter(statement -> statement.getCondition() == binary || statement.getCondition().isAncestorOf(binary))
			.orElse(null);
		MethodDeclaration method = binary.findAncestor(MethodDeclaration.class).orElse(null);
		if (conditional == null || method == null
				|| !Set.of("compare", "compareTo", "equal", "equals").contains(method.getNameAsString())) {
			return false;
		}
		ReturnStmt returned = directReturn(conditional.getThenStmt());
		if (returned == null || returned.getExpression().isEmpty()) {
			return false;
		}
		Expression value = returned.getExpression().orElseThrow();
		return value instanceof BooleanLiteralExpr literal && literal.getValue()
				|| integer(value).filter(number -> number == 0).isPresent();
	}

	private static ReturnStmt directReturn(com.github.javaparser.ast.stmt.Statement statement) {
		if (statement instanceof ReturnStmt returned) {
			return returned;
		}
		if (statement.isBlockStmt() && statement.asBlockStmt().getStatements().size() == 1
				&& statement.asBlockStmt().getStatement(0) instanceof ReturnStmt returned) {
			return returned;
		}
		return null;
	}

	private static void casts(InspectionContext context, List<Finding> findings) {
		for (InstanceOfExpr instance : context.compilationUnit().findAll(InstanceOfExpr.class)) {
			String source = typeOf(context, instance.getExpression(), instance).orElse("");
			String target = instance.getType().asString();
			if (locallyIncompatible(context, source, target)) {
				findings.add(Finding.at(instance, "instanceof check uses an incompatible type"));
			}
		}
		for (CastExpr cast : context.compilationUnit().findAll(CastExpr.class)) {
			String source = typeOf(context, cast.getExpression(), cast).orElse("");
			String target = cast.getType().asString();
			if (locallyIncompatible(context, source, target)) {
				findings.add(Finding.at(cast, "Cast converts between incompatible types"));
			}
			AstSupport.ancestor(cast, IfStmt.class).ifPresent(statement -> {
				String checked = statement.getCondition()
					.findAll(InstanceOfExpr.class)
					.stream()
					.filter(value -> value.getExpression().equals(cast.getExpression()))
					.map(value -> simple(value.getType().asString()))
					.findFirst()
					.orElse(null);
				if (checked != null && conflictingCheckedCast(checked, target)) {
					findings.add(Finding.at(cast, "Cast conflicts with the preceding instanceof check"));
				}
			});
			if (array(source) && array(target) && dimensions(source) != dimensions(target)) {
				findings.add(Finding.at(cast, "Suspicious array cast changes array dimensions"));
			}
		}
	}

	private static void shallowArrayCalls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isEmpty() || !Set.of("equals", "hash", "hashCode").contains(call.getNameAsString())
					|| call.getArguments().isEmpty() || !ExpressionToolSupport.knownType(context.compilationUnit(),
							call.getScope().orElseThrow().toString(), "java.util", Set.of("Objects"))) {
				continue;
			}
			if (call.getArguments().stream().anyMatch(argument -> array(typeOf(context, argument, call).orElse("")))) {
				findings.add(Finding.at(call, "Use of shallow Objects method with an array may ignore array contents"));
			}
		}
	}

	private static void compareUsage(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			MethodCallExpr call = binary.getLeft() instanceof MethodCallExpr left ? left
					: binary.getRight() instanceof MethodCallExpr right ? right : null;
			Expression other = call == binary.getLeft() ? binary.getRight() : binary.getLeft();
			if (call == null || !Set.of("compare", "compareTo").contains(call.getNameAsString())) {
				continue;
			}
			integer(other).filter(value -> value != 0)
				.ifPresent(value -> findings
					.add(Finding.at(binary, "Comparison method result should normally be compared with zero")));
		}
	}

	private static Optional<String> typeOf(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof StringLiteralExpr) {
			return Optional.of("String");
		}
		if (expression instanceof ObjectCreationExpr creation) {
			return Optional.of(creation.getType().asString());
		}
		if (expression instanceof CastExpr cast) {
			return Optional.of(cast.getType().asString());
		}
		if (expression instanceof ArrayCreationExpr creation) {
			return Optional.of(creation.getElementType().asString() + "[]".repeat(creation.getLevels().size()));
		}
		if (expression instanceof IntegerLiteralExpr) {
			return Optional.of("int");
		}
		return TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), expression, use);
	}

	private static boolean inconvertible(String left, String right) {
		String leftSimple = simple(left);
		String rightSimple = simple(right);
		return !leftSimple.isEmpty() && !rightSimple.isEmpty() && !leftSimple.equals(rightSimple)
				&& FINAL_VALUE_TYPES.contains(leftSimple) && FINAL_VALUE_TYPES.contains(rightSimple);
	}

	private static boolean locallyIncompatible(InspectionContext context, String source, String target) {
		String left = simple(source);
		String right = simple(target);
		if (left.isEmpty() || right.isEmpty() || left.equals(right)) {
			return false;
		}
		List<ClassOrInterfaceDeclaration> types = context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class);
		ClassOrInterfaceDeclaration leftType = types.stream()
			.filter(type -> type.getNameAsString().equals(left))
			.findFirst()
			.orElse(null);
		ClassOrInterfaceDeclaration rightType = types.stream()
			.filter(type -> type.getNameAsString().equals(right))
			.findFirst()
			.orElse(null);
		if (leftType == null || rightType == null || !leftType.isFinal() && !rightType.isFinal()) {
			return false;
		}
		return !related(leftType, right) && !related(rightType, left);
	}

	private static boolean related(ClassOrInterfaceDeclaration type, String other) {
		return type.getExtendedTypes().stream().anyMatch(parent -> parent.getNameAsString().equals(other))
				|| type.getImplementedTypes().stream().anyMatch(parent -> parent.getNameAsString().equals(other));
	}

	private static boolean defaultToString(InspectionContext context, String type) {
		return context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(declaration -> declaration.getNameAsString().equals(simple(type)))
			.anyMatch(declaration -> declaration.getMethodsByName("toString")
				.stream()
				.noneMatch(method -> method.getParameters().isEmpty()));
	}

	private static boolean conflictingCheckedCast(String checked, String target) {
		String left = simple(checked);
		String right = simple(target);
		return !left.equals(right) && FINAL_VALUE_TYPES.contains(left) && FINAL_VALUE_TYPES.contains(right);
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

	private static boolean assertionContext(Node node) {
		Node current = node;
		while (current != null) {
			if (current instanceof AssertStmt) {
				return true;
			}
			if (current instanceof MethodCallExpr call && call.getNameAsString().startsWith("assert")) {
				return true;
			}
			current = current.getParentNode().orElse(null);
		}
		return false;
	}

	private static boolean array(String type) {
		return type.endsWith("[]");
	}

	private static int dimensions(String type) {
		String currentType = type;
		int result = 0;
		while (currentType.endsWith("[]")) {
			result++;
			currentType = currentType.substring(0, currentType.length() - 2);
		}
		return result;
	}

	private static String simple(String type) {
		String currentType = type;
		while (currentType.endsWith("[]")) {
			currentType = currentType.substring(0, currentType.length() - 2);
		}
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

}

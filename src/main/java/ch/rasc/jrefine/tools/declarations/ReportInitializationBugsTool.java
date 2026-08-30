package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reports source-local construction and initialization order hazards. */
public final class ReportInitializationBugsTool implements InspectionTool {

	private static final Set<String> PUBLICATION_PREFIXES = Set.of("add", "register", "subscribe", "publish", "submit",
			"execute", "schedule", "offer", "put", "set");

	@Override
	public String id() {
		return "report-initialization-bugs";
	}

	@Override
	public String description() {
		return "Report construction-time dispatch, this escape, field-order, and unsafe lazy initialization bugs";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		doubleBraceInitialization(context, findings);
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (type.isInterface()) {
				continue;
			}
			if (!suppressesThisEscape(type)) {
				constructorHazards(type, findings);
			}
			fieldOrderHazards(context, type, findings);
			unsafeLazyInitialization(context, type, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void doubleBraceInitialization(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			if (creation.getAnonymousClassBody()
				.filter(body -> body.stream()
					.filter(InitializerDeclaration.class::isInstance)
					.map(InitializerDeclaration.class::cast)
					.anyMatch(initializer -> !initializer.isStatic()))
				.isPresent()) {
				findings.add(Finding.at(creation,
						"Double-brace initialization creates an anonymous class and captures initialization context"));
			}
		}
	}

	private static void constructorHazards(ClassOrInterfaceDeclaration type, List<Finding> findings) {
		for (ConstructorDeclaration constructor : type.getConstructors()) {
			for (MethodCallExpr call : constructor.getBody().findAll(MethodCallExpr.class)) {
				if (!directlyWithin(call, constructor)
						|| call.getScope().filter(scope -> !(scope instanceof ThisExpr)).isPresent()) {
					continue;
				}
				List<MethodDeclaration> candidates = type.getMethodsByName(call.getNameAsString())
					.stream()
					.filter(method -> method.getParameters().size() == call.getArguments().size())
					.toList();
				if (candidates.size() != 1) {
					continue;
				}
				MethodDeclaration method = candidates.getFirst();
				if (method.isStatic() || method.isPrivate()) {
					continue;
				}
				if (method.isAbstract()) {
					findings.add(Finding.at(call, "Abstract method is called during object construction"));
				}
				else if (method.getAnnotationByName("Override").isPresent()
						|| method.getAnnotationByName("java.lang.Override").isPresent()) {
					findings.add(Finding.at(call, "Overridden method is called during object construction"));
				}
				else if (!method.isFinal() && !type.isFinal() && hasSourceSubclass(type)) {
					findings.add(Finding.at(call, "Overridable method is called during object construction"));
				}
			}
			escapedThis(type, constructor, findings);
		}
	}

	private static boolean hasSourceSubclass(ClassOrInterfaceDeclaration type) {
		return type.findCompilationUnit()
			.stream()
			.flatMap(unit -> unit.findAll(ClassOrInterfaceDeclaration.class).stream())
			.filter(candidate -> candidate != type)
			.flatMap(candidate -> candidate.getExtendedTypes().stream())
			.anyMatch(parent -> TypeLookup.simpleName(parent.asString()).equals(type.getNameAsString()));
	}

	private static boolean suppressesThisEscape(ClassOrInterfaceDeclaration type) {
		return type.getAnnotations()
			.stream()
			.filter(annotation -> Set.of("SuppressWarnings", "java.lang.SuppressWarnings")
				.contains(annotation.getNameAsString()))
			.map(Object::toString)
			.anyMatch(annotation -> annotation.contains("\"this-escape\"") || annotation.contains("\"all\""));
	}

	private static void escapedThis(ClassOrInterfaceDeclaration type, ConstructorDeclaration constructor,
			List<Finding> findings) {
		for (ThisExpr reference : constructor.getBody().findAll(ThisExpr.class)) {
			if (!directlyWithin(reference, constructor)) {
				continue;
			}
			ObjectCreationExpr creation = reference.findAncestor(ObjectCreationExpr.class)
				.filter(candidate -> directlyWithin(candidate, constructor))
				.filter(candidate -> candidate.getArguments()
					.stream()
					.anyMatch(argument -> argument == reference || argument.isAncestorOf(reference)))
				.orElse(null);
			if (creation != null) {
				findings.add(
						Finding.at(reference, "'this' is passed to another object during construction and may escape"));
				continue;
			}
			MethodCallExpr call = reference.findAncestor(MethodCallExpr.class)
				.filter(candidate -> directlyWithin(candidate, constructor))
				.filter(candidate -> candidate.getArguments()
					.stream()
					.anyMatch(argument -> argument == reference || argument.isAncestorOf(reference)))
				.orElse(null);
			if (call != null && publicationName(call.getNameAsString()) && externalPublicationCall(type, call)) {
				findings.add(Finding.at(reference,
						"'this' is published from the constructor before initialization completes"));
				continue;
			}
			AssignExpr assignment = reference.findAncestor(AssignExpr.class)
				.filter(candidate -> candidate.getValue() == reference || candidate.getValue().isAncestorOf(reference))
				.orElse(null);
			if (assignment != null && externallyVisibleTarget(type, assignment.getTarget())) {
				findings.add(
						Finding.at(reference, "'this' is assigned outside the object during construction and escapes"));
			}
		}
	}

	private static boolean publicationName(String name) {
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		return PUBLICATION_PREFIXES.stream().anyMatch(lower::startsWith);
	}

	private static boolean externalPublicationCall(ClassOrInterfaceDeclaration type, MethodCallExpr call) {
		if (call.getScope().isPresent()) {
			return !(call.getScope().orElseThrow() instanceof ThisExpr);
		}
		return type.getMethodsByName(call.getNameAsString())
			.stream()
			.noneMatch(method -> method.getParameters().size() == call.getArguments().size());
	}

	private static boolean externallyVisibleTarget(ClassOrInterfaceDeclaration type, Expression target) {
		if (target instanceof FieldAccessExpr access) {
			return !(access.getScope() instanceof ThisExpr) && !(access.getScope() instanceof SuperExpr);
		}
		if (!(target instanceof NameExpr name)) {
			return false;
		}
		return type.getFields()
			.stream()
			.filter(FieldDeclaration::isStatic)
			.flatMap(field -> field.getVariables().stream())
			.anyMatch(variable -> variable.getNameAsString().equals(name.getNameAsString()));
	}

	private static void fieldOrderHazards(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		ArrayList<FieldInfo> fields = new ArrayList<>();
		for (FieldDeclaration declaration : type.getFields()) {
			for (VariableDeclarator variable : declaration.getVariables()) {
				fields.add(new FieldInfo(declaration, variable));
			}
		}
		for (int index = 0; index < fields.size(); index++) {
			FieldInfo current = fields.get(index);
			Expression initializer = current.variable().getInitializer().orElse(null);
			if (initializer == null) {
				continue;
			}
			for (int laterIndex = index + 1; laterIndex < fields.size(); laterIndex++) {
				FieldInfo later = fields.get(laterIndex);
				if (current.declaration().isStatic() != later.declaration().isStatic() || constantVariable(later)) {
					continue;
				}
				Node reference = fieldReference(context, type, initializer, later.variable()).stream()
					.findFirst()
					.orElse(null);
				if (reference != null) {
					findings.add(Finding.at(reference,
							current.declaration().isStatic() ? "Static field is read before its initializer runs"
									: "Instance field is read before its initializer runs"));
				}
			}
		}
	}

	private static List<Node> fieldReference(InspectionContext context, ClassOrInterfaceDeclaration type,
			Expression initializer, VariableDeclarator field) {
		ArrayList<Node> references = new ArrayList<>();
		String name = field.getNameAsString();
		initializer.findAll(NameExpr.class)
			.stream()
			.filter(reference -> reference.getNameAsString().equals(name))
			.filter(reference -> directlyEvaluated(reference, initializer))
			.filter(reference -> !TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name,
					reference))
			.forEach(references::add);
		initializer.findAll(FieldAccessExpr.class)
			.stream()
			.filter(reference -> reference.getNameAsString().equals(name))
			.filter(reference -> directlyEvaluated(reference, initializer))
			.filter(reference -> reference.getScope() instanceof ThisExpr
					|| reference.getScope().toString().equals(type.getNameAsString()))
			.forEach(references::add);
		return references;
	}

	private static boolean constantVariable(FieldInfo field) {
		return field.declaration().isStatic() && field.declaration().isFinal()
				&& field.variable()
					.getInitializer()
					.filter(ReportInitializationBugsTool::constantExpression)
					.isPresent();
	}

	private static boolean constantExpression(Expression expression) {
		if (expression.isLiteralExpr()) {
			return true;
		}
		if (expression instanceof EnclosedExpr enclosed) {
			return constantExpression(enclosed.getInner());
		}
		if (expression instanceof CastExpr cast) {
			return constantExpression(cast.getExpression());
		}
		if (expression instanceof UnaryExpr unary) {
			return constantExpression(unary.getExpression());
		}
		if (expression instanceof BinaryExpr binary) {
			return constantExpression(binary.getLeft()) && constantExpression(binary.getRight());
		}
		return expression instanceof ConditionalExpr conditional && constantExpression(conditional.getCondition())
				&& constantExpression(conditional.getThenExpr()) && constantExpression(conditional.getElseExpr());
	}

	private static void unsafeLazyInitialization(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		for (FieldDeclaration field : type.getFields()) {
			if (!field.isStatic() || field.isFinal() || field.isVolatile()) {
				continue;
			}
			for (VariableDeclarator variable : field.getVariables()) {
				String name = variable.getNameAsString();
				for (IfStmt statement : type.findAll(IfStmt.class)) {
					if (statement.findAncestor(TypeDeclaration.class).orElse(null) != type
							|| !nullCheck(context, type, statement.getCondition(), name, statement)) {
						continue;
					}
					AssignExpr assignment = statement.getThenStmt()
						.findAll(AssignExpr.class)
						.stream()
						.filter(candidate -> directlyWithin(candidate, statement.getThenStmt()))
						.filter(candidate -> assignsField(context, type, candidate.getTarget(), name, candidate))
						.filter(candidate -> !(candidate.getValue() instanceof NullLiteralExpr))
						.findFirst()
						.orElse(null);
					if (assignment != null && !synchronizedContext(assignment)) {
						findings.add(Finding.at(statement,
								"Static field is lazily initialized without synchronization or volatile publication"));
					}
				}
			}
		}
	}

	private static boolean nullCheck(InspectionContext context, ClassOrInterfaceDeclaration owner,
			Expression expression, String field, Node use) {
		if (!(expression instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.EQUALS) {
			return false;
		}
		return binary.getLeft() instanceof NullLiteralExpr
				&& fieldExpression(context, owner, binary.getRight(), field, use)
				|| binary.getRight() instanceof NullLiteralExpr
						&& fieldExpression(context, owner, binary.getLeft(), field, use);
	}

	private static boolean assignsField(InspectionContext context, ClassOrInterfaceDeclaration owner,
			Expression expression, String field, Node use) {
		return fieldExpression(context, owner, expression, field, use);
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

	private static boolean synchronizedContext(Node node) {
		Node current = node;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof SynchronizedStmt) {
				return true;
			}
			if (current instanceof MethodDeclaration method) {
				return method.isSynchronized();
			}
			if (current instanceof CallableDeclaration<?> || current instanceof TypeDeclaration<?>) {
				return false;
			}
		}
		return false;
	}

	private static boolean directlyWithin(Node node, Node owner) {
		Node current = node;
		while (current != owner) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof LambdaExpr
					|| parent instanceof CallableDeclaration<?> && parent != owner
					|| parent instanceof TypeDeclaration<?>
					|| parent instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
				return false;
			}
			current = parent;
		}
		return true;
	}

	private static boolean directlyEvaluated(Node node, Expression initializer) {
		Node current = node;
		while (current != initializer) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof LambdaExpr || parent instanceof CallableDeclaration<?>
					|| parent instanceof TypeDeclaration<?>) {
				return false;
			}
			current = parent;
		}
		return true;
	}

	private record FieldInfo(FieldDeclaration declaration, VariableDeclarator variable) {
	}

}

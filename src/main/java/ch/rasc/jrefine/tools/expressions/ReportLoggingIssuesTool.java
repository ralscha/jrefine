package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reports logger declaration mistakes and eager dynamic message concatenation. */
public final class ReportLoggingIssuesTool implements InspectionTool {

	private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "warning", "error", "fatal",
			"severe", "config", "fine", "finer", "finest", "log");

	@Override
	public String id() {
		return "report-logging-issues";
	}

	@Override
	public String description() {
		return "Report foreign or mutable loggers and eager string concatenation in logging calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		loggerFields(context, findings);
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			foreignLoggerCategory(context, call, findings);
			concatenatedMessage(context, call, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void loggerFields(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			List<FieldDeclaration> loggers = type.getFields()
				.stream()
				.filter(field -> loggerType(context, field.getElementType().asString()))
				.toList();
			long loggerCount = loggers.stream().mapToLong(field -> field.getVariables().size()).sum();
			if (loggerCount > 1) {
				findings.add(Finding.at(type, "Class declares multiple logger fields"));
			}
			loggers.stream()
				.filter(field -> !field.isFinal())
				.forEach(field -> findings.add(Finding.at(field, "Logger field is mutable; declare it final")));
		}
	}

	private static void foreignLoggerCategory(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		if (!loggerFactory(context, call) || call.getArguments().isEmpty()) {
			return;
		}
		String category = classCategory(call.getArgument(0));
		if (category == null) {
			return;
		}
		ClassOrInterfaceDeclaration owner = call.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		if (owner == null || enclosingTypeNames(owner).contains(category)) {
			return;
		}
		findings.add(Finding.at(call.getArgument(0),
				"Logger is initialized with foreign class " + category + "; use the enclosing class category"));
	}

	private static Set<String> enclosingTypeNames(ClassOrInterfaceDeclaration type) {
		java.util.HashSet<String> names = new java.util.HashSet<>();
		Node current = type;
		while (current != null) {
			if (current instanceof TypeDeclaration<?> declaration) {
				names.add(declaration.getNameAsString());
			}
			current = current.getParentNode().orElse(null);
		}
		return Set.copyOf(names);
	}

	private static String classCategory(Expression expression) {
		if (expression instanceof ClassExpr literal) {
			return TypeLookup.simpleName(literal.getType().asString());
		}
		if (expression instanceof MethodCallExpr call && "getName".equals(call.getNameAsString())
				&& call.getArguments().isEmpty() && call.getScope().orElse(null) instanceof ClassExpr literal) {
			return TypeLookup.simpleName(literal.getType().asString());
		}
		return null;
	}

	private static void concatenatedMessage(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		if (!LOG_METHODS.contains(call.getNameAsString()) || call.getScope().isEmpty()) {
			return;
		}
		LoggerKind receiver = loggerReceiver(context, call);
		if (receiver == null || receiver == LoggerKind.LEGACY) {
			return;
		}
		int messageIndex = "log".equals(call.getNameAsString()) ? 1 : 0;
		if (call.getArguments().size() <= messageIndex || !(call.getArgument(messageIndex) instanceof BinaryExpr binary)
				|| !containsStringLiteral(binary) || !dynamicPart(context, binary, call)) {
			return;
		}
		findings.add(Finding.at(binary, "Logging message is built with eager string concatenation"));
	}

	private static LoggerKind loggerReceiver(InspectionContext context, MethodCallExpr call) {
		Expression scope = call.getScope().orElseThrow();
		if (scope instanceof MethodCallExpr factory && loggerFactory(context, factory)) {
			return factoryKind(context, factory);
		}
		Optional<String> visible = TypeLookup.visibleType(context.compilationUnit(), scope, call);
		if (visible.isPresent()) {
			LoggerKind kind = loggerKind(context, visible.orElseThrow());
			if (kind != null) {
				return kind;
			}
		}
		if (scope instanceof FieldAccessExpr access && access.getScope().isThisExpr()) {
			ClassOrInterfaceDeclaration owner = call.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
			if (owner != null) {
				for (FieldDeclaration field : owner.getFields()) {
					if (field.getVariables()
						.stream()
						.anyMatch(variable -> variable.getNameAsString().equals(access.getNameAsString()))) {
						return loggerKind(context, field.getElementType().asString());
					}
				}
			}
		}
		return null;
	}

	private static boolean loggerFactory(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty()) {
			return false;
		}
		String method = call.getNameAsString();
		String owner = call.getScope().orElseThrow().toString();
		return "getLogger".equals(method)
				&& (TypeLookup.isKnownType(context.compilationUnit(), owner, "org.slf4j", Set.of("LoggerFactory"))
						|| TypeLookup.isKnownType(context.compilationUnit(), owner, "org.apache.logging.log4j",
								Set.of("LogManager"))
						|| TypeLookup.isKnownType(context.compilationUnit(), owner, "java.util.logging",
								Set.of("Logger")))
				|| "getLog".equals(method) && TypeLookup.isKnownType(context.compilationUnit(), owner,
						"org.apache.commons.logging", Set.of("LogFactory"));
	}

	private static boolean loggerType(InspectionContext context, String type) {
		return loggerKind(context, type) != null;
	}

	private static LoggerKind loggerKind(InspectionContext context, String type) {
		if (TypeLookup.isKnownType(context.compilationUnit(), type, "org.slf4j", Set.of("Logger")) || TypeLookup
			.isKnownType(context.compilationUnit(), type, "org.apache.logging.log4j", Set.of("Logger"))) {
			return LoggerKind.PARAMETERIZED;
		}
		if (TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.logging", Set.of("Logger"))) {
			return LoggerKind.LAZY;
		}
		if (TypeLookup.isKnownType(context.compilationUnit(), type, "org.apache.commons.logging", Set.of("Log"))) {
			return LoggerKind.LEGACY;
		}
		return null;
	}

	private static LoggerKind factoryKind(InspectionContext context, MethodCallExpr factory) {
		String owner = factory.getScope().map(Object::toString).orElse("");
		if (TypeLookup.isKnownType(context.compilationUnit(), owner, "org.slf4j", Set.of("LoggerFactory")) || TypeLookup
			.isKnownType(context.compilationUnit(), owner, "org.apache.logging.log4j", Set.of("LogManager"))) {
			return LoggerKind.PARAMETERIZED;
		}
		if (TypeLookup.isKnownType(context.compilationUnit(), owner, "java.util.logging", Set.of("Logger"))) {
			return LoggerKind.LAZY;
		}
		if (TypeLookup.isKnownType(context.compilationUnit(), owner, "org.apache.commons.logging",
				Set.of("LogFactory"))) {
			return LoggerKind.LEGACY;
		}
		return null;
	}

	private static boolean containsStringLiteral(Expression expression) {
		return expression.isStringLiteralExpr() || expression instanceof TextBlockLiteralExpr
				|| expression instanceof BinaryExpr binary
						&& (containsStringLiteral(binary.getLeft()) || containsStringLiteral(binary.getRight()));
	}

	private static boolean dynamicPart(InspectionContext context, Expression expression, Node use) {
		if (expression.isLiteralExpr()) {
			return false;
		}
		if (expression instanceof EnclosedExpr enclosed) {
			return dynamicPart(context, enclosed.getInner(), use);
		}
		if (expression instanceof CastExpr cast) {
			return dynamicPart(context, cast.getExpression(), use);
		}
		if (expression instanceof UnaryExpr unary) {
			return dynamicPart(context, unary.getExpression(), use);
		}
		if (expression instanceof BinaryExpr binary) {
			return dynamicPart(context, binary.getLeft(), use) || dynamicPart(context, binary.getRight(), use);
		}
		if (expression instanceof NameExpr name) {
			return !constantName(name.getNameAsString()) && !visibleConstant(context, name.getNameAsString(), use);
		}
		if (expression instanceof FieldAccessExpr access) {
			return !constantName(access.getNameAsString());
		}
		return true;
	}

	private static boolean visibleConstant(InspectionContext context, String name, Node use) {
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (!variable.getNameAsString().equals(name) || !before(variable, use)
					|| variable.getInitializer().filter(ReportLoggingIssuesTool::constantExpression).isEmpty()) {
				continue;
			}
			FieldDeclaration field = variable.findAncestor(FieldDeclaration.class).orElse(null);
			if (field != null && field.isStatic() && field.isFinal()) {
				return true;
			}
			VariableDeclarationExpr local = variable.findAncestor(VariableDeclarationExpr.class).orElse(null);
			if (local != null && local.isFinal()) {
				return true;
			}
		}
		return false;
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
		return expression instanceof BinaryExpr binary && constantExpression(binary.getLeft())
				&& constantExpression(binary.getRight());
	}

	private static boolean constantName(String name) {
		return name.matches("[A-Z][A-Z0-9_]*");
	}

	private static boolean before(Node left, Node right) {
		Position first = left.getBegin().orElse(Position.HOME);
		Position second = right.getBegin().orElse(Position.HOME);
		return first.line < second.line || first.line == second.line && first.column < second.column;
	}

	private enum LoggerKind {

		PARAMETERIZED, LAZY, LEGACY

	}

}

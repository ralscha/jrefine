package ch.rasc.jrefine.tools.syntax;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * Reports APIs and literals whose behavior is tied to a JVM vendor or operating system.
 */
public final class ReportPortabilityIssuesTool implements InspectionTool {

	@Override
	public String id() {
		return "report-portability-issues";
	}

	@Override
	public String description() {
		return "Report process, exit, environment, separator, native, internal, AWT peer, and JDBC portability issues";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		runtimeCalls(context, findings);
		separators(context, findings);
		nativeMethods(context, findings);
		typeUsage(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void runtimeCalls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if ("getenv".equals(call.getNameAsString()) && call.getScope()
				.filter(scope -> Set.of("System", "java.lang.System").contains(scope.toString()))
				.isPresent()) {
				findings.add(Finding.at(call, "Call to System.getenv() reduces portability"));
			}
			if ("exec".equals(call.getNameAsString()) && runtimeScope(context, call)) {
				findings.add(Finding.at(call, "Call to Runtime.exec() reduces portability"));
			}
			boolean systemExit = "exit".equals(call.getNameAsString()) && call.getScope()
				.filter(scope -> Set.of("System", "java.lang.System").contains(scope.toString()))
				.isPresent();
			if (systemExit && !applicationEntryPoint(call)
					|| Set.of("exit", "halt").contains(call.getNameAsString()) && runtimeScope(context, call)) {
				findings.add(Finding.at(call, "Call to System.exit(), Runtime.exit(), or Runtime.halt()"));
			}
		}
	}

	private static boolean applicationEntryPoint(MethodCallExpr call) {
		return AstSupport.ancestor(call, MethodDeclaration.class)
			.filter(method -> method.isStatic() && "main".equals(method.getNameAsString())
					&& method.getType().isVoidType() && method.getParameters().size() == 1
					&& (method.getParameter(0).getType().isArrayType()
							&& "String".equals(simple(
									method.getParameter(0).getType().asArrayType().getComponentType().asString()))
							|| method.getParameter(0).isVarArgs()
									&& "String".equals(simple(method.getParameter(0).getType().asString()))))
			.isPresent();
	}

	private static boolean runtimeScope(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty()) {
			return false;
		}
		Expression scope = call.getScope().orElseThrow();
		if (scope instanceof MethodCallExpr receiver && "getRuntime".equals(receiver.getNameAsString())
				&& receiver.getScope()
					.filter(value -> Set.of("Runtime", "java.lang.Runtime").contains(value.toString()))
					.isPresent()) {
			return true;
		}
		if (scope instanceof NameExpr) {
			return TypeLookup.visibleType(context.compilationUnit(), scope, call)
				.map(ReportPortabilityIssuesTool::simple)
				.filter(type -> "Runtime".equals(type))
				.isPresent();
		}
		return false;
	}

	private static void separators(InspectionContext context, List<Finding> findings) {
		for (StringLiteralExpr literal : context.compilationUnit().findAll(StringLiteralExpr.class)) {
			inspectLiteral(literal, literal.asString(), findings);
		}
		for (CharLiteralExpr literal : context.compilationUnit().findAll(CharLiteralExpr.class)) {
			inspectLiteral(literal, String.valueOf(literal.asChar()), findings);
		}
		for (TextBlockLiteralExpr literal : context.compilationUnit().findAll(TextBlockLiteralExpr.class)) {
			if (looksLikeFilePath(literal, literal.getValue())) {
				findings.add(Finding.at(literal, "Hardcoded file separator"));
			}
		}
	}

	private static void inspectLiteral(Node literal, String value, List<Finding> findings) {
		if (looksLikeFilePath(literal, value)) {
			findings.add(Finding.at(literal, "Hardcoded file separator"));
		}
		if ((value.indexOf(LineEndingSupport.LINE_FEED_CHAR) >= 0
				|| value.indexOf(LineEndingSupport.CARRIAGE_RETURN_CHAR) >= 0) && lineSeparatorContext(literal)) {
			findings.add(Finding.at(literal, "Hardcoded line separator"));
		}
	}

	private static boolean looksLikeFilePath(Node literal, String value) {
		String lower = value.toLowerCase(java.util.Locale.ROOT);
		if (lower.matches("^[a-z][a-z0-9+.-]*://.*")) {
			return false;
		}
		if (AstSupport.ancestor(literal, MethodCallExpr.class)
			.map(call -> Set.of("contains", "equals", "matches", "startsWith", "endsWith")
				.contains(call.getNameAsString()))
			.orElse(false)) {
			return false;
		}
		if (value.matches("^[A-Za-z]:[\\\\/].*") || value.startsWith("./") || value.startsWith("../")
				|| value.startsWith(".\\") || value.startsWith("..\\")
				|| value.startsWith("\\\\") && value.length() > 2 && pathCharacter(value.charAt(2))) {
			return true;
		}
		if (!pathContext(literal)) {
			return false;
		}
		if ("/".equals(value) || "\\".equals(value)) {
			return true;
		}
		for (int index = 1; index + 1 < value.length(); index++) {
			char character = value.charAt(index);
			if (character != '/' && character != '\\') {
				continue;
			}
			int left = segmentLength(value, index - 1, -1);
			int right = segmentLength(value, index + 1, 1);
			if (left > 0 && right > 0 && (left > 1 || right > 1)) {
				return true;
			}
		}
		return false;
	}

	private static boolean lineSeparatorContext(Node literal) {
		if (AstSupport.ancestor(literal, VariableDeclarator.class)
			.map(variable -> lineSeparatorName(variable.getNameAsString()))
			.orElse(false)) {
			return true;
		}
		if (AstSupport.ancestor(literal, AssignExpr.class)
			.map(assignment -> lineSeparatorName(assignment.getTarget().toString()))
			.orElse(false)) {
			return true;
		}
		return AstSupport.ancestor(literal, ReturnStmt.class).isPresent()
				&& AstSupport.ancestor(literal, MethodDeclaration.class)
					.map(method -> lineSeparatorName(method.getNameAsString()))
					.orElse(false);
	}

	private static boolean pathContext(Node literal) {
		if (AstSupport.ancestor(literal, ObjectCreationExpr.class)
			.map(creation -> "File".equals(creation.getType().getNameAsString()))
			.orElse(false)) {
			return true;
		}
		return AstSupport.ancestor(literal, MethodCallExpr.class).map(call -> {
			String method = call.getNameAsString();
			if (Set.of("resolve", "resolveSibling", "relativize").contains(method)) {
				return true;
			}
			return Set.of("of", "get").contains(method) && call.getScope()
				.map(scope -> Set.of("Path", "Paths", "java.nio.file.Path", "java.nio.file.Paths")
					.contains(scope.toString()))
				.orElse(false);
		}).orElse(false);
	}

	private static boolean lineSeparatorName(String name) {
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("lineseparator") || lower.contains("lineending") || lower.contains("newline")
				|| "eol".equals(lower);
	}

	private static int segmentLength(String value, int index, int direction) {
		int currentIndex = index;
		int length = 0;
		while (currentIndex >= 0 && currentIndex < value.length() && pathCharacter(value.charAt(currentIndex))) {
			length++;
			currentIndex += direction;
		}
		return length;
	}

	private static boolean pathCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '.' || character == '_' || character == '-'
				|| character == '~';
	}

	private static void nativeMethods(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(MethodDeclaration::isNative)
			.forEach(method -> findings.add(Finding.at(method, "Native method reduces portability")));
	}

	private static void typeUsage(InspectionContext context, List<Finding> findings) {
		HashSet<String> sunTypes = new HashSet<>();
		HashSet<String> peerTypes = new HashSet<>();
		HashSet<String> driverTypes = new HashSet<>();
		context.compilationUnit().getImports().forEach(imported -> {
			String name = imported.getNameAsString();
			String simple = simple(name);
			if (name.startsWith("sun.") || name.startsWith("com.sun.")) {
				sunTypes.add(simple);
				findings.add(Finding.at(imported, "Use of sun.* internal class"));
			}
			if (name.startsWith("java.awt.peer.")) {
				peerTypes.add(simple);
				findings.add(Finding.at(imported, "Use of AWT peer class"));
			}
			if (concreteJdbcDriver(name)) {
				driverTypes.add(simple);
				findings.add(Finding.at(imported, "Use of concrete JDBC driver class"));
			}
		});
		for (ClassOrInterfaceType type : context.compilationUnit().findAll(ClassOrInterfaceType.class)) {
			String spelling = type.toString();
			String simple = type.getNameAsString();
			if (TypeLookup.isKnownJavaLangType(context.compilationUnit(), spelling, Set.of("ProcessBuilder"))) {
				findings.add(Finding.at(type, "Use of java.lang.ProcessBuilder may be platform-dependent"));
			}
			if (spelling.startsWith("sun.") || spelling.startsWith("com.sun.") || sunTypes.contains(simple)) {
				findings.add(Finding.at(type, "Use of sun.* internal class"));
			}
			if (spelling.startsWith("java.awt.peer.") || peerTypes.contains(simple)) {
				findings.add(Finding.at(type, "Use of AWT peer class"));
			}
			if (concreteJdbcDriver(spelling) || driverTypes.contains(simple)) {
				findings.add(Finding.at(type, "Use of concrete JDBC driver class"));
			}
		}
	}

	private static boolean concreteJdbcDriver(String type) {
		String lower = type.toLowerCase(java.util.Locale.ROOT);
		return simple(type).endsWith("Driver") && (lower.contains(".jdbc.") || lower.startsWith("org.postgresql.")
				|| lower.startsWith("com.mysql.") || lower.startsWith("oracle.jdbc."));
	}

	private static String simple(String type) {
		int dot = type.lastIndexOf('.');
		return dot < 0 ? type : type.substring(dot + 1);
	}

}

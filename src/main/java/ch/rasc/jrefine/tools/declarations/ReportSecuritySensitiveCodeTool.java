package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reports source shapes that deserve explicit review at a security boundary. */
public final class ReportSecuritySensitiveCodeTool implements PolicyInspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "SortedSet", "NavigableSet",
			"Queue", "Deque", "Map", "SortedMap", "NavigableMap", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet",
			"TreeSet", "HashMap", "LinkedHashMap", "TreeMap", "Hashtable", "Vector", "Stack");

	private static final Set<String> SYSTEM_PROPERTY_METHODS = Set.of("getProperty", "getProperties", "setProperty",
			"setProperties", "clearProperty");

	private static final Set<String> SENSITIVE_WORDS = Set.of("auth", "credential", "csrf", "key", "nonce", "otp",
			"password", "passwd", "pwd", "reset", "salt", "secret", "secure", "session", "security", "token",
			"verification", "verify");

	@Override
	public String id() {
		return "report-security-sensitive-code";
	}

	@Override
	public String description() {
		return "Report exposed mutable state, security-sensitive JDK APIs, and insecure random generation";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		exposedStaticState(context, findings);
		customSecurityTypes(context, findings);
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			classLoaderCreation(context, creation, findings);
			insecureRandomCreation(context, creation, findings);
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			systemCalls(context, call, findings);
			insecureRandomCall(context, call, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void exposedStaticState(InspectionContext context, List<Finding> findings) {
		for (FieldDeclaration field : context.compilationUnit().findAll(FieldDeclaration.class)) {
			if (!field.isPublic() || !field.isStatic()) {
				continue;
			}
			for (VariableDeclarator variable : field.getVariables()) {
				if (variable.getType().isArrayType()) {
					findings.add(Finding.at(variable, "Public static array exposes mutable global state"));
				}
				else if (knownCollection(context, variable.getType().asString())
						&& !immutableCollectionInitializer(context, variable)) {
					findings.add(Finding.at(variable, "Public static collection exposes mutable global state"));
				}
			}
		}
	}

	private static boolean knownCollection(InspectionContext context, String type) {
		return TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, COLLECTION_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent",
						Set.of("ConcurrentMap", "ConcurrentHashMap", "CopyOnWriteArrayList", "CopyOnWriteArraySet",
								"BlockingQueue", "BlockingDeque"));
	}

	private static boolean immutableCollectionInitializer(InspectionContext context, VariableDeclarator variable) {
		if (!(variable.getInitializer().orElse(null) instanceof MethodCallExpr call) || call.getScope().isEmpty()) {
			return false;
		}
		String method = call.getNameAsString();
		String owner = call.getScope().orElseThrow().toString();
		if (Set.of("of", "ofEntries", "copyOf").contains(method)) {
			return TypeLookup.isKnownJavaUtilType(context.compilationUnit(), owner, Set.of("List", "Set", "Map"));
		}
		return (method.startsWith("unmodifiable") || method.startsWith("empty") || method.startsWith("singleton"))
				&& TypeLookup.isKnownJavaUtilType(context.compilationUnit(), owner, Set.of("Collections"));
	}

	private static void customSecurityTypes(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (type.isInterface()) {
				continue;
			}
			if (type.getExtendedTypes()
				.stream()
				.anyMatch(parent -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), parent.asString(),
						Set.of("ClassLoader")))) {
				findings.add(Finding.at(type, "Custom ClassLoader implementation is security-sensitive"));
			}
			if (type.getExtendedTypes()
				.stream()
				.anyMatch(parent -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), parent.asString(),
						Set.of("SecurityManager")))) {
				findings.add(Finding.at(type, "Custom SecurityManager is obsolete and security-sensitive"));
			}
		}
	}

	private static void classLoaderCreation(InspectionContext context, ObjectCreationExpr creation,
			List<Finding> findings) {
		String type = creation.getType().asString();
		if (TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("ClassLoader"))
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.net", Set.of("URLClassLoader"))) {
			findings
				.add(Finding.at(creation, "ClassLoader instantiation creates a security-sensitive loading boundary"));
		}
	}

	private static void systemCalls(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		if (call.getScope()
			.filter(scope -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), scope.toString(),
					Set.of("System")))
			.isEmpty()) {
			return;
		}
		if (SYSTEM_PROPERTY_METHODS.contains(call.getNameAsString())) {
			findings.add(Finding.at(call, "System property access crosses a process-wide configuration boundary"));
		}
		if ("setSecurityManager".equals(call.getNameAsString())) {
			findings.add(Finding.at(call, "System.setSecurityManager() is obsolete and must not be used"));
		}
	}

	private static void insecureRandomCreation(InspectionContext context, ObjectCreationExpr creation,
			List<Finding> findings) {
		if (TypeLookup.isKnownJavaUtilType(context.compilationUnit(), creation.getType().asString(),
				Set.of("Random", "SplittableRandom")) && securityContext(creation)) {
			findings.add(Finding.at(creation,
					"Security-sensitive value uses a predictable random generator; use SecureRandom"));
		}
	}

	private static void insecureRandomCall(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		boolean mathRandom = "random".equals(call.getNameAsString()) && call.getScope()
			.filter(scope -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), scope.toString(),
					Set.of("Math")))
			.isPresent();
		boolean threadLocalRandom = "current".equals(call.getNameAsString()) && call.getScope()
			.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), "java.util.concurrent",
					Set.of("ThreadLocalRandom")))
			.isPresent();
		if ((mathRandom || threadLocalRandom) && securityContext(call)) {
			findings.add(
					Finding.at(call, "Security-sensitive value uses a predictable random generator; use SecureRandom"));
		}
	}

	private static boolean securityContext(Node node) {
		VariableDeclarator variable = node.findAncestor(VariableDeclarator.class).orElse(null);
		if (variable != null && sensitiveName(variable.getNameAsString())) {
			return true;
		}
		MethodDeclaration method = node.findAncestor(MethodDeclaration.class).orElse(null);
		if (method != null && sensitiveName(method.getNameAsString())) {
			return true;
		}
		TypeDeclaration<?> type = node.findAncestor(TypeDeclaration.class).orElse(null);
		return type != null && sensitiveName(type.getNameAsString());
	}

	private static boolean sensitiveName(String name) {
		String words = name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
			.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
			.toLowerCase(Locale.ROOT);
		return Arrays.stream(words.split("[^a-z]+")).anyMatch(SENSITIVE_WORDS::contains);
	}

}

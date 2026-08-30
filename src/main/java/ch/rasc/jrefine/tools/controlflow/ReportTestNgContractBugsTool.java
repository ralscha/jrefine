package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reports source-local TestNG data-provider, dependency, exception, and annotation
 * errors.
 */
public final class ReportTestNgContractBugsTool implements InspectionTool {

	private static final String TESTNG_ANNOTATIONS = "org.testng.annotations";

	private static final Pattern TESTNG_JAVADOC = Pattern.compile("(?m)^\\s*\\*?\\s*@testng\\.[a-z][a-z0-9-]*\\b");

	private static final Set<String> JAVA_LANG_CHECKED = Set.of("ClassNotFoundException", "CloneNotSupportedException",
			"Exception", "IllegalAccessException", "InstantiationException", "InterruptedException",
			"NoSuchFieldException", "NoSuchMethodException", "ReflectiveOperationException", "Throwable");

	private static final Set<String> JAVA_IO_CHECKED = Set.of("CharConversionException", "EOFException",
			"FileNotFoundException", "IOException", "InterruptedIOException", "InvalidClassException",
			"NotActiveException", "NotSerializableException", "ObjectStreamException", "OptionalDataException",
			"StreamCorruptedException", "SyncFailedException", "UTFDataFormatException", "UnsupportedEncodingException",
			"WriteAbortedException");

	private static final Set<String> JAVA_NET_CHECKED = Set.of("BindException", "ConnectException",
			"HttpRetryException", "MalformedURLException", "NoRouteToHostException", "PortUnreachableException",
			"ProtocolException", "SocketException", "SocketTimeoutException", "UnknownHostException",
			"UnknownServiceException", "URISyntaxException");

	private static final Map<String, Set<String>> OTHER_CHECKED = Map.of("java.sql",
			Set.of("SQLException", "SQLTimeoutException", "SQLWarning"), "java.text", Set.of("ParseException"),
			"java.security", Set.of("GeneralSecurityException", "PrivilegedActionException"), "javax.naming",
			Set.of("NamingException"));

	@Override
	public String id() {
		return "report-testng-contract-bugs";
	}

	@Override
	public String description() {
		return "Report invalid TestNG data providers, dependencies, expected exceptions, and annotations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		CompilationUnit root = context.compilationUnit();
		ArrayList<Finding> findings = new ArrayList<>();
		Map<String, ClassOrInterfaceDeclaration> localTypes = localTypes(root);
		Map<ClassOrInterfaceDeclaration, List<Provider>> providers = providers(root, findings);

		duplicateProviderNames(providers, findings);
		for (ClassOrInterfaceDeclaration type : root.findAll(ClassOrInterfaceDeclaration.class)) {
			for (MethodDeclaration method : type.getMethods()) {
				annotation(root, method, "Test").ifPresent(test -> {
					dataProviderReference(root, test, type, localTypes, providers, findings);
					methodDependencies(root, method, test, type, localTypes, findings);
					expectedExceptions(root, method, test, localTypes, findings);
				});
				annotation(root, method, "Factory")
					.ifPresent(factory -> dataProviderReference(root, factory, type, localTypes, providers, findings));
			}
			for (ConstructorDeclaration constructor : type.getConstructors()) {
				annotation(root, constructor, "Factory")
					.ifPresent(factory -> dataProviderReference(root, factory, type, localTypes, providers, findings));
			}
		}

		for (AnnotationExpr annotation : root.findAll(AnnotationExpr.class)) {
			if (knownAnnotation(root, annotation, "Configuration")) {
				findings.add(Finding.at(annotation, "Obsolete TestNG @Configuration annotation is used"));
			}
		}
		for (JavadocComment comment : root.getAllComments()
			.stream()
			.filter(JavadocComment.class::isInstance)
			.map(JavadocComment.class::cast)
			.toList()) {
			if (TESTNG_JAVADOC.matcher(comment.getContent()).find()) {
				findings
					.add(Finding.at(comment, "Legacy TestNG Javadoc annotation can be converted to a Java annotation"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static Map<ClassOrInterfaceDeclaration, List<Provider>> providers(CompilationUnit root,
			List<Finding> findings) {
		LinkedHashMap<ClassOrInterfaceDeclaration, List<Provider>> result = new LinkedHashMap<>();
		for (ClassOrInterfaceDeclaration type : root.findAll(ClassOrInterfaceDeclaration.class)) {
			ArrayList<Provider> declared = new ArrayList<>();
			for (MethodDeclaration method : type.getMethods()) {
				AnnotationExpr annotation = annotation(root, method, "DataProvider").orElse(null);
				if (annotation == null) {
					continue;
				}
				if (!validProviderReturnType(root, method.getType())) {
					findings.add(Finding.at(method.getType(), "TestNG data provider has an invalid return type"));
				}
				String name = providerName(annotation, method).orElse(null);
				declared.add(new Provider(method, name));
			}
			result.put(type, List.copyOf(declared));
		}
		return Map.copyOf(result);
	}

	private static void duplicateProviderNames(Map<ClassOrInterfaceDeclaration, List<Provider>> providers,
			List<Finding> findings) {
		for (List<Provider> declared : providers.values()) {
			HashMap<String, List<Provider>> byName = new HashMap<>();
			declared.stream()
				.filter(provider -> provider.name() != null)
				.forEach(provider -> byName.computeIfAbsent(provider.name(), ignored -> new ArrayList<>())
					.add(provider));
			for (Map.Entry<String, List<Provider>> entry : byName.entrySet()) {
				if (entry.getValue().size() < 2) {
					continue;
				}
				for (Provider provider : entry.getValue()) {
					findings.add(Finding.at(provider.method(), "TestNG data provider name '" + entry.getKey()
							+ "' is declared more than once in this class"));
				}
			}
		}
	}

	private static void dataProviderReference(CompilationUnit root, AnnotationExpr test,
			ClassOrInterfaceDeclaration owner, Map<String, ClassOrInterfaceDeclaration> localTypes,
			Map<ClassOrInterfaceDeclaration, List<Provider>> providers, List<Finding> findings) {
		StringValue requested = member(test, "dataProvider").flatMap(ReportTestNgContractBugsTool::stringValue)
			.orElse(null);
		if (requested == null || requested.value().isBlank()) {
			return;
		}

		ClassOrInterfaceDeclaration target = owner;
		boolean explicitTarget = false;
		Optional<Expression> targetValue = member(test, "dataProviderClass");
		if (targetValue.isPresent()) {
			if (!(targetValue.orElseThrow() instanceof ClassExpr classExpr)) {
				return;
			}
			String spelling = classExpr.getType().asString();
			if (!TypeLookup.isKnownJavaLangType(root, spelling, Set.of("Object"))) {
				target = resolveLocalType(spelling, localTypes).orElse(null);
				if (target == null) {
					return;
				}
				explicitTarget = true;
			}
		}

		ClassOrInterfaceDeclaration selectedTarget = target;
		TypeScope scope = hierarchy(selectedTarget, localTypes);
		List<Provider> matching = scope.types()
			.stream()
			.flatMap(type -> providers.getOrDefault(type, List.of()).stream())
			.filter(provider -> requested.value().equals(provider.name()))
			.toList();
		if (matching.isEmpty()) {
			if (scope.complete()) {
				findings.add(Finding.at(requested.expression(),
						"TestNG data provider '" + requested.value() + "' cannot be resolved"));
			}
			return;
		}
		if (explicitTarget && matching.stream()
			.noneMatch(provider -> provider.method().isStatic() || instantiable(selectedTarget))) {
			findings.add(Finding.at(requested.expression(),
					"TestNG data provider '" + requested.value() + "' is not accessible through dataProviderClass"));
		}
	}

	private static void methodDependencies(CompilationUnit root, MethodDeclaration consumer, AnnotationExpr test,
			ClassOrInterfaceDeclaration owner, Map<String, ClassOrInterfaceDeclaration> localTypes,
			List<Finding> findings) {
		Optional<Expression> value = member(test, "dependsOnMethods");
		if (value.isEmpty()) {
			return;
		}
		TypeScope scope = hierarchy(owner, localTypes);
		if (!scope.complete()) {
			return;
		}
		List<MethodDeclaration> tests = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : scope.types()) {
			for (MethodDeclaration candidate : type.getMethods()) {
				if (type != owner && candidate.isPrivate()) {
					continue;
				}
				if (annotation(root, candidate, "Test").isPresent()
						|| annotation(root, type, "Test").isPresent() && candidate.isPublic()) {
					tests.add(candidate);
				}
			}
		}
		for (StringValue dependency : stringValues(value.orElseThrow())) {
			Pattern pattern;
			try {
				pattern = Pattern.compile(dependency.value());
			}
			catch (PatternSyntaxException ex) {
				findings.add(Finding.at(dependency.expression(),
						"TestNG dependsOnMethods contains invalid pattern '" + dependency.value() + "'"));
				continue;
			}
			if (tests.stream().noneMatch(method -> pattern.matcher(method.getNameAsString()).matches())) {
				findings.add(Finding.at(dependency.expression(), "TestNG dependsOnMethods entry '" + dependency.value()
						+ "' does not resolve to an accessible test method"));
			}
		}
	}

	private static void expectedExceptions(CompilationUnit root, MethodDeclaration method, AnnotationExpr test,
			Map<String, ClassOrInterfaceDeclaration> localTypes, List<Finding> findings) {
		Optional<Expression> value = member(test, "expectedExceptions");
		if (value.isEmpty() || bodyMayThrowCheckedException(method)) {
			return;
		}
		for (ClassValue exception : classValues(value.orElseThrow())) {
			if (definitelyChecked(root, exception.name(), localTypes, new HashSet<>())) {
				findings.add(Finding.at(exception.expression(), "Expected checked exception '" + exception.name()
						+ "' is never thrown in the test method body"));
			}
		}
	}

	private static boolean bodyMayThrowCheckedException(MethodDeclaration method) {
		if (method.getBody().isEmpty()) {
			return false;
		}
		return method.getBody()
			.orElseThrow()
			.findAll(Node.class)
			.stream()
			.filter(node -> node instanceof ThrowStmt || node instanceof MethodCallExpr
					|| node instanceof ObjectCreationExpr)
			.anyMatch(node -> directlyExecutedBy(node, method));
	}

	private static boolean directlyExecutedBy(Node node, MethodDeclaration method) {
		Node current = node;
		while (current != method) {
			if (current instanceof LambdaExpr) {
				return false;
			}
			Optional<Node> parent = current.getParentNode();
			if (parent.isEmpty()) {
				return false;
			}
			current = parent.orElseThrow();
			if (current instanceof MethodDeclaration && current != method) {
				return false;
			}
		}
		return true;
	}

	private static boolean definitelyChecked(CompilationUnit root, String spelling,
			Map<String, ClassOrInterfaceDeclaration> localTypes, Set<ClassOrInterfaceDeclaration> visiting) {
		if (TypeLookup.isKnownType(root, spelling, "java.lang", JAVA_LANG_CHECKED)
				|| TypeLookup.isKnownType(root, spelling, "java.io", JAVA_IO_CHECKED)
				|| TypeLookup.isKnownType(root, spelling, "java.net", JAVA_NET_CHECKED)) {
			return true;
		}
		for (Map.Entry<String, Set<String>> entry : OTHER_CHECKED.entrySet()) {
			if (TypeLookup.isKnownType(root, spelling, entry.getKey(), entry.getValue())) {
				return true;
			}
		}
		ClassOrInterfaceDeclaration local = resolveLocalType(spelling, localTypes).orElse(null);
		if (local == null || !visiting.add(local)) {
			return false;
		}
		return local.getExtendedTypes()
			.stream()
			.anyMatch(parent -> definitelyChecked(root, parent.asString(), localTypes, visiting));
	}

	private static boolean validProviderReturnType(CompilationUnit root, Type returnType) {
		if (returnType instanceof ArrayType array && !array.getComponentType().isPrimitiveType()) {
			return true;
		}
		return TypeLookup.isKnownJavaUtilType(root, returnType.asString(), Set.of("Iterator"));
	}

	private static Optional<String> providerName(AnnotationExpr annotation, MethodDeclaration method) {
		Optional<Expression> configured = member(annotation, "name");
		if (configured.isEmpty()) {
			return Optional.of(method.getNameAsString());
		}
		return stringValue(configured.orElseThrow())
			.map(value -> value.value().isEmpty() ? method.getNameAsString() : value.value());
	}

	private static boolean instantiable(ClassOrInterfaceDeclaration type) {
		if (type.isInterface() || type.isAbstract()
				|| type.findAncestor(ClassOrInterfaceDeclaration.class).isPresent() && !type.isStatic()) {
			return false;
		}
		return type.getConstructors().isEmpty() || type.getConstructors()
			.stream()
			.anyMatch(constructor -> constructor.getParameters().isEmpty() && !constructor.isPrivate());
	}

	private static TypeScope hierarchy(ClassOrInterfaceDeclaration start,
			Map<String, ClassOrInterfaceDeclaration> localTypes) {
		ArrayList<ClassOrInterfaceDeclaration> types = new ArrayList<>();
		HashSet<ClassOrInterfaceDeclaration> seen = new HashSet<>();
		boolean complete = collectHierarchy(start, localTypes, seen, types);
		return new TypeScope(List.copyOf(types), complete);
	}

	private static boolean collectHierarchy(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> localTypes, Set<ClassOrInterfaceDeclaration> seen,
			List<ClassOrInterfaceDeclaration> result) {
		if (!seen.add(type)) {
			return true;
		}
		result.add(type);
		boolean complete = true;
		for (var parentType : type.getExtendedTypes()) {
			ClassOrInterfaceDeclaration parent = resolveLocalType(parentType.asString(), localTypes).orElse(null);
			if (parent == null) {
				complete = false;
			}
			else {
				complete &= collectHierarchy(parent, localTypes, seen, result);
			}
		}
		return complete;
	}

	private static Map<String, ClassOrInterfaceDeclaration> localTypes(CompilationUnit root) {
		HashMap<String, ClassOrInterfaceDeclaration> result = new HashMap<>();
		HashSet<String> duplicates = new HashSet<>();
		for (ClassOrInterfaceDeclaration type : root.findAll(ClassOrInterfaceDeclaration.class)) {
			if (result.putIfAbsent(type.getNameAsString(), type) != null) {
				duplicates.add(type.getNameAsString());
			}
		}
		duplicates.forEach(result::remove);
		return Map.copyOf(result);
	}

	private static Optional<ClassOrInterfaceDeclaration> resolveLocalType(String spelling,
			Map<String, ClassOrInterfaceDeclaration> localTypes) {
		String simple = TypeLookup.simpleName(spelling);
		ClassOrInterfaceDeclaration candidate = localTypes.get(simple);
		if (candidate == null || !spelling.contains(".")) {
			return Optional.ofNullable(candidate);
		}
		return candidate.getFullyQualifiedName()
			.filter(name -> name.equals(spelling) || name.endsWith("." + spelling))
			.map(ignored -> candidate);
	}

	private static Optional<AnnotationExpr> annotation(CompilationUnit root, NodeWithAnnotations<?> declaration,
			String name) {
		return declaration.getAnnotations()
			.stream()
			.filter(annotation -> knownAnnotation(root, annotation, name))
			.findFirst();
	}

	private static boolean knownAnnotation(CompilationUnit root, AnnotationExpr annotation, String name) {
		return TypeLookup.isKnownType(root, annotation.getNameAsString(), TESTNG_ANNOTATIONS, Set.of(name));
	}

	private static Optional<Expression> member(AnnotationExpr annotation, String name) {
		if (!annotation.isNormalAnnotationExpr()) {
			return Optional.empty();
		}
		return annotation.asNormalAnnotationExpr()
			.getPairs()
			.stream()
			.filter(pair -> pair.getNameAsString().equals(name))
			.map(pair -> pair.getValue())
			.findFirst();
	}

	private static Optional<StringValue> stringValue(Expression expression) {
		if (expression instanceof StringLiteralExpr literal) {
			return Optional.of(new StringValue(literal.asString(), literal));
		}
		return Optional.empty();
	}

	private static List<StringValue> stringValues(Expression expression) {
		if (expression instanceof ArrayInitializerExpr array) {
			return array.getValues()
				.stream()
				.map(ReportTestNgContractBugsTool::stringValue)
				.flatMap(Optional::stream)
				.toList();
		}
		return stringValue(expression).stream().toList();
	}

	private static List<ClassValue> classValues(Expression expression) {
		if (expression instanceof ArrayInitializerExpr array) {
			return array.getValues()
				.stream()
				.map(ReportTestNgContractBugsTool::classValue)
				.flatMap(Optional::stream)
				.toList();
		}
		return classValue(expression).stream().toList();
	}

	private static Optional<ClassValue> classValue(Expression expression) {
		if (expression instanceof ClassExpr classExpr) {
			return Optional.of(new ClassValue(classExpr.getType().asString(), classExpr));
		}
		return Optional.empty();
	}

	private record Provider(MethodDeclaration method, String name) {
	}

	private record TypeScope(List<ClassOrInterfaceDeclaration> types, boolean complete) {
	}

	private record StringValue(String value, Expression expression) {
	}

	private record ClassValue(String name, ClassExpr expression) {
	}

}

package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reports source-local Lombok annotations that are obsolete, ineffective, or unsafe to
 * import.
 */
public final class ReportLombokContractIssuesTool implements InspectionTool {

	private static final String LOMBOK = "lombok";

	private static final String LOMBOK_EXPERIMENTAL = "lombok.experimental";

	@Override
	public String id() {
		return "report-lombok-contract-issues";
	}

	@Override
	public String description() {
		return "Report deprecated, invalid, redundant, and statically imported Lombok generation";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		CompilationUnit root = context.compilationUnit();
		ArrayList<Finding> findings = new ArrayList<>();

		deprecatedAnnotations(root, findings);
		invalidAndRedundantAnnotations(root, findings);
		generatedStaticImports(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void deprecatedAnnotations(CompilationUnit root, List<Finding> findings) {
		for (AnnotationExpr annotation : root.findAll(AnnotationExpr.class)) {
			if (knownAnnotation(root, annotation, LOMBOK_EXPERIMENTAL, "Builder")) {
				findings.add(Finding.at(annotation, "Deprecated Lombok @Builder annotation; use lombok.Builder"));
			}
			else if (knownAnnotation(root, annotation, LOMBOK_EXPERIMENTAL, "Value")) {
				findings.add(Finding.at(annotation, "Deprecated Lombok @Value annotation; use lombok.Value"));
			}
			else if (knownAnnotation(root, annotation, LOMBOK_EXPERIMENTAL, "Wither")) {
				findings.add(Finding.at(annotation, "Deprecated Lombok @Wither annotation; use lombok.With"));
			}
		}
		root.getImports()
			.stream()
			.filter(imported -> !imported.isStatic() && !imported.isAsterisk())
			.filter(imported -> imported.getNameAsString().equals("lombok.experimental.var"))
			.forEach(imported -> findings
				.add(Finding.at(imported, "Deprecated lombok.experimental.var type; use lombok.var")));
	}

	private static void invalidAndRedundantAnnotations(CompilationUnit root, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : root.findAll(ClassOrInterfaceDeclaration.class)) {
			annotation(root, type, LOMBOK, "ToString")
				.filter(ignored -> type.getMethodsByName("toString")
					.stream()
					.anyMatch(method -> method.getParameters().isEmpty()))
				.ifPresent(annotation -> findings
					.add(Finding.at(annotation, "Lombok @ToString is redundant because toString() is declared")));

			annotation(root, type, LOMBOK, "EqualsAndHashCode").filter(ignored -> declaresEqualsAndHashCode(type))
				.ifPresent(annotation -> findings.add(Finding.at(annotation,
						"Lombok @EqualsAndHashCode is redundant because both methods are declared")));

			annotation(root, type, LOMBOK, "NoArgsConstructor")
				.filter(ignored -> type.getConstructors()
					.stream()
					.anyMatch(constructor -> constructor.getParameters().isEmpty()))
				.ifPresent(annotation -> findings.add(Finding.at(annotation,
						"Lombok @NoArgsConstructor conflicts with the declared no-argument constructor")));

			if (type.isInterface()) {
				annotation(root, type, LOMBOK_EXPERIMENTAL, "UtilityClass").ifPresent(annotation -> findings
					.add(Finding.at(annotation, "Lombok @UtilityClass cannot be applied to an interface")));
			}
		}

		for (RecordDeclaration record : root.findAll(RecordDeclaration.class)) {
			annotation(root, record, LOMBOK, "Value").ifPresent(
					annotation -> findings.add(Finding.at(annotation, "Lombok @Value cannot be applied to a record")));
			annotation(root, record, LOMBOK, "Data").ifPresent(
					annotation -> findings.add(Finding.at(annotation, "Lombok @Data cannot be applied to a record")));
			annotation(root, record, LOMBOK_EXPERIMENTAL, "UtilityClass").ifPresent(annotation -> findings
				.add(Finding.at(annotation, "Lombok @UtilityClass cannot be applied to a record")));
		}
	}

	private static boolean declaresEqualsAndHashCode(ClassOrInterfaceDeclaration type) {
		boolean equals = type.getMethodsByName("equals")
			.stream()
			.anyMatch(method -> method.getParameters().size() == 1);
		boolean hashCode = type.getMethodsByName("hashCode")
			.stream()
			.anyMatch(method -> method.getParameters().isEmpty());
		return equals && hashCode;
	}

	private static void generatedStaticImports(InspectionContext context, List<Finding> findings) {
		CompilationUnit root = context.compilationUnit();
		Map<String, ClassOrInterfaceDeclaration> localTypes = localTypes(root);
		for (ImportDeclaration imported : root.getImports()) {
			if (!imported.isStatic()) {
				continue;
			}
			StaticImport value = staticImport(imported);
			SourceType owner = Optional.ofNullable(localTypes.get(value.owner()))
				.map(type -> new SourceType(root, type))
				.or(() -> sourceType(context, value.owner()))
				.orElse(null);
			if (owner == null) {
				continue;
			}
			Set<String> generated = generatedStaticMethods(owner.root(), owner.type());
			if (!generated.isEmpty() && (value.wildcard() || generated.contains(value.member()))) {
				findings.add(Finding.at(imported,
						"Static import refers to a method generated by Lombok and will not compile with javac"));
			}
		}
	}

	private static Set<String> generatedStaticMethods(CompilationUnit root, ClassOrInterfaceDeclaration type) {
		HashSet<String> names = new HashSet<>();
		annotation(root, type, LOMBOK, "Builder").flatMap(ReportLombokContractIssuesTool::builderMethodName)
			.ifPresent(names::add);
		for (var method : type.getMethods()) {
			annotation(root, method, LOMBOK, "Builder").flatMap(ReportLombokContractIssuesTool::builderMethodName)
				.ifPresent(names::add);
		}
		for (var constructor : type.getConstructors()) {
			annotation(root, constructor, LOMBOK, "Builder").flatMap(ReportLombokContractIssuesTool::builderMethodName)
				.ifPresent(names::add);
			for (String constructorAnnotation : Set.of("NoArgsConstructor", "RequiredArgsConstructor",
					"AllArgsConstructor")) {
				annotation(root, constructor, LOMBOK, constructorAnnotation)
					.flatMap(annotation -> stringMember(annotation, "staticName"))
					.filter(name -> !name.isBlank())
					.ifPresent(names::add);
			}
		}
		for (String constructorAnnotation : Set.of("NoArgsConstructor", "RequiredArgsConstructor",
				"AllArgsConstructor")) {
			annotation(root, type, LOMBOK, constructorAnnotation)
				.flatMap(annotation -> stringMember(annotation, "staticName"))
				.filter(name -> !name.isBlank())
				.ifPresent(names::add);
		}
		for (String aggregate : Set.of("Data", "Value")) {
			annotation(root, type, LOMBOK, aggregate)
				.flatMap(annotation -> stringMember(annotation, "staticConstructor"))
				.filter(name -> !name.isBlank())
				.ifPresent(names::add);
		}

		type.getMethods().stream().map(method -> method.getNameAsString()).forEach(names::remove);
		type.getFields()
			.stream()
			.flatMap(field -> field.getVariables().stream())
			.map(variable -> variable.getNameAsString())
			.forEach(names::remove);
		return Set.copyOf(names);
	}

	private static Optional<String> builderMethodName(AnnotationExpr annotation) {
		if (!annotation.isNormalAnnotationExpr()) {
			return Optional.of("builder");
		}
		Optional<String> configured = stringMember(annotation, "builderMethodName");
		boolean hasMember = annotation.asNormalAnnotationExpr()
			.getPairs()
			.stream()
			.anyMatch(pair -> pair.getNameAsString().equals("builderMethodName"));
		if (hasMember) {
			return configured.filter(name -> !name.isBlank());
		}
		return Optional.of("builder");
	}

	private static Optional<String> stringMember(AnnotationExpr annotation, String member) {
		if (!annotation.isNormalAnnotationExpr()) {
			return Optional.empty();
		}
		return annotation.asNormalAnnotationExpr()
			.getPairs()
			.stream()
			.filter(pair -> pair.getNameAsString().equals(member))
			.map(pair -> pair.getValue())
			.filter(StringLiteralExpr.class::isInstance)
			.map(StringLiteralExpr.class::cast)
			.map(StringLiteralExpr::asString)
			.findFirst();
	}

	private static StaticImport staticImport(ImportDeclaration imported) {
		String name = imported.getNameAsString();
		if (imported.isAsterisk()) {
			return new StaticImport(name, "", true);
		}
		int separator = name.lastIndexOf('.');
		return new StaticImport(name.substring(0, separator), name.substring(separator + 1), false);
	}

	private static Map<String, ClassOrInterfaceDeclaration> localTypes(CompilationUnit root) {
		HashMap<String, ClassOrInterfaceDeclaration> result = new HashMap<>();
		for (ClassOrInterfaceDeclaration type : root.findAll(ClassOrInterfaceDeclaration.class)) {
			type.getFullyQualifiedName().ifPresent(name -> result.put(name, type));
		}
		return Map.copyOf(result);
	}

	private static Optional<SourceType> sourceType(InspectionContext context, String qualifiedName) {
		Path sourceFile = context.path().toAbsolutePath().normalize();
		if (!Files.isRegularFile(sourceFile)) {
			return Optional.empty();
		}
		Path sourceRoot = sourceRoot(context.compilationUnit(), sourceFile).orElse(null);
		if (sourceRoot == null) {
			return Optional.empty();
		}
		Path target = sourceRoot.resolve(qualifiedName.replace('.', '/') + ".java").normalize();
		if (!target.startsWith(sourceRoot) || !Files.isRegularFile(target)) {
			return Optional.empty();
		}
		try {
			JavaParser parser = new JavaParser(
					new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
			ParseResult<CompilationUnit> parsed = parser.parse(Files.readString(target, StandardCharsets.UTF_8));
			if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
				return Optional.empty();
			}
			CompilationUnit root = parsed.getResult().orElseThrow();
			return Optional.ofNullable(localTypes(root).get(qualifiedName)).map(type -> new SourceType(root, type));
		}
		catch (IOException ex) {
			return Optional.empty();
		}
	}

	private static Optional<Path> sourceRoot(CompilationUnit root, Path sourceFile) {
		Path directory = sourceFile.getParent();
		if (directory == null) {
			return Optional.empty();
		}
		String packageName = root.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
		if (packageName.isEmpty()) {
			return Optional.of(directory);
		}
		String[] segments = packageName.split("\\.");
		Path current = directory;
		for (int index = segments.length - 1; index >= 0; index--) {
			if (current == null || current.getFileName() == null
					|| !current.getFileName().toString().equals(segments[index])) {
				return Optional.empty();
			}
			current = current.getParent();
		}
		return Optional.ofNullable(current);
	}

	private static Optional<AnnotationExpr> annotation(CompilationUnit root, NodeWithAnnotations<?> declaration,
			String packageName, String name) {
		return declaration.getAnnotations()
			.stream()
			.filter(annotation -> knownAnnotation(root, annotation, packageName, name))
			.findFirst();
	}

	private static boolean knownAnnotation(CompilationUnit root, AnnotationExpr annotation, String packageName,
			String name) {
		return TypeLookup.isKnownType(root, annotation.getNameAsString(), packageName, Set.of(name));
	}

	private record StaticImport(String owner, String member, boolean wildcard) {
	}

	private record SourceType(CompilationUnit root, ClassOrInterfaceDeclaration type) {
	}

}

package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleExportsDirective;
import com.github.javaparser.ast.modules.ModuleOpensDirective;
import com.github.javaparser.ast.modules.ModuleUsesDirective;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Reports mechanically invalid or incomplete source-local Java module contracts. */
public final class ReportModuleContractIssuesTool implements InspectionTool {

	private final ConcurrentMap<Path, Optional<ModuleDescriptor>> descriptors = new ConcurrentHashMap<>();

	@Override
	public String id() {
		return "report-module-contract-issues";
	}

	@Override
	public String description() {
		return "Report empty modules, self-targeted directives, and undeclared ServiceLoader uses";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		context.compilationUnit().getModule().ifPresent(module -> inspectDescriptor(module, findings));
		if (context.compilationUnit().getModule().isEmpty()) {
			undeclaredServices(context, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void inspectDescriptor(ModuleDeclaration module, List<Finding> findings) {
		if (module.getDirectives().isEmpty()) {
			findings.add(Finding.at(module, "Empty module-info.java has no module directives"));
		}
		String moduleName = module.getNameAsString();
		module.getDirectives()
			.stream()
			.filter(ModuleExportsDirective.class::isInstance)
			.map(ModuleExportsDirective.class::cast)
			.filter(directive -> directive.getModuleNames()
				.stream()
				.anyMatch(name -> name.asString().equals(moduleName)))
			.forEach(directive -> findings.add(Finding.at(directive, "Module exports a package to itself")));
		module.getDirectives()
			.stream()
			.filter(ModuleOpensDirective.class::isInstance)
			.map(ModuleOpensDirective.class::cast)
			.filter(directive -> directive.getModuleNames()
				.stream()
				.anyMatch(name -> name.asString().equals(moduleName)))
			.forEach(directive -> findings.add(Finding.at(directive, "Module opens a package to itself")));
	}

	private void undeclaredServices(InspectionContext context, List<Finding> findings) {
		ModuleDescriptor descriptor = nearestDescriptor(context.path()).orElse(null);
		if (descriptor == null) {
			return;
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!serviceLoaderCall(context, call)) {
				continue;
			}
			ClassExpr serviceClass = call.getArguments()
				.stream()
				.filter(ClassExpr.class::isInstance)
				.map(ClassExpr.class::cast)
				.reduce((first, second) -> second)
				.orElse(null);
			if (serviceClass == null) {
				continue;
			}
			String service = qualifiedType(context.compilationUnit(), serviceClass.getType().asString()).orElse(null);
			if (service != null && !descriptor.uses().contains(service)) {
				findings.add(Finding.at(call,
						"ServiceLoader use of '" + service + "' is not declared with uses in module-info.java"));
			}
		}
	}

	private static boolean serviceLoaderCall(InspectionContext context, MethodCallExpr call) {
		if (!Set.of("load", "loadInstalled").contains(call.getNameAsString()) || call.getScope().isEmpty()) {
			return false;
		}
		return TypeLookup.isKnownType(context.compilationUnit(), call.getScope().orElseThrow().toString(), "java.util",
				Set.of("ServiceLoader"));
	}

	private static Optional<String> qualifiedType(CompilationUnit unit, String spelling) {
		if (spelling.isBlank() || spelling.contains("[") || spelling.contains("<")) {
			return Optional.empty();
		}
		int dot = spelling.indexOf('.');
		if (dot > 0 && Character.isLowerCase(spelling.charAt(0))) {
			return Optional.of(spelling);
		}
		String simple = dot < 0 ? spelling : spelling.substring(0, dot);
		List<ImportDeclaration> explicit = unit.getImports()
			.stream()
			.filter(imported -> !imported.isStatic() && !imported.isAsterisk())
			.filter(imported -> imported.getName().getIdentifier().equals(simple))
			.toList();
		if (explicit.size() == 1) {
			String imported = explicit.getFirst().getNameAsString();
			return Optional.of(imported + (dot < 0 ? "" : spelling.substring(dot)));
		}
		if (!explicit.isEmpty()
				|| unit.getImports().stream().anyMatch(imported -> !imported.isStatic() && imported.isAsterisk())) {
			return Optional.empty();
		}
		return unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString() + "." + spelling);
	}

	private Optional<ModuleDescriptor> nearestDescriptor(Path sourcePath) {
		Path current = sourcePath.toAbsolutePath().normalize().getParent();
		while (current != null) {
			Path candidate = current.resolve("module-info.java").normalize();
			if (Files.isRegularFile(candidate)) {
				return descriptors.computeIfAbsent(candidate, ReportModuleContractIssuesTool::parseDescriptor);
			}
			current = current.getParent();
		}
		return Optional.empty();
	}

	private static Optional<ModuleDescriptor> parseDescriptor(Path path) {
		try {
			JavaParser parser = new JavaParser(
					new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
			ParseResult<CompilationUnit> result = parser.parse(Files.readString(path, StandardCharsets.UTF_8));
			return result.getResult().flatMap(CompilationUnit::getModule).map(module -> {
				HashSet<String> uses = new HashSet<>();
				module.getDirectives()
					.stream()
					.filter(ModuleUsesDirective.class::isInstance)
					.map(ModuleUsesDirective.class::cast)
					.map(ModuleUsesDirective::getNameAsString)
					.forEach(uses::add);
				return new ModuleDescriptor(Set.copyOf(uses));
			});
		}
		catch (IOException exception) {
			return Optional.empty();
		}
	}

	private record ModuleDescriptor(Set<String> uses) {
	}

}

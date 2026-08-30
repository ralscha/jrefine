package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import java.util.Optional;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces repeated java.io.File attribute calls with one Files.readAttributes() call.
 */
public final class UseBulkFileAttributesTool implements InspectionTool {

	private static final Map<String, String> ATTRIBUTE_METHODS = Map.of("isDirectory", "isDirectory()", "isFile",
			"isRegularFile()", "lastModified", "lastModifiedTime().toMillis()", "length", "size()");

	@Override
	public String id() {
		return "use-bulk-file-attributes";
	}

	@Override
	public String description() {
		return "Use Files.readAttributes() for repeated File attribute reads";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = candidates(context);
		ArrayList<Finding> findings = new ArrayList<>();
		if (candidates.isEmpty()) {
			return ToolResult.of(findings, applyFixes);
		}

		String filesType = introducedType(context, "java.nio.file.Files", applyFixes);
		String attributesType = introducedType(context, "java.nio.file.attribute.BasicFileAttributes", applyFixes);
		LinkedHashSet<String> usedNames = new LinkedHashSet<>();
		context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.forEach(variable -> usedNames.add(variable.getNameAsString()));
		context.compilationUnit()
			.findAll(Parameter.class)
			.forEach(parameter -> usedNames.add(parameter.getNameAsString()));
		LinkedHashMap<Statement, List<String>> declarations = new LinkedHashMap<>();

		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.calls().get(0).call(), "Use one Files.readAttributes() call"));
			if (!applyFixes) {
				continue;
			}
			String attributesName = availableName(usedNames, "fileAttributes");
			usedNames.add(attributesName);
			String declaration = attributesType + " " + attributesName + " = " + filesType + ".readAttributes("
					+ context.editor().text(candidate.file()) + ".toPath(), " + attributesType + ".class);";
			declarations.computeIfAbsent(candidate.statement(), ignored -> new ArrayList<>()).add(declaration);
			for (AttributeCall call : candidate.calls()) {
				context.editor()
					.replace(call.call().getRange().orElseThrow(),
							attributesName + "." + ATTRIBUTE_METHODS.get(call.method()));
			}
		}
		if (applyFixes) {
			String lineEnding = LineEndingSupport.detect(context.editor().source());
			declarations.forEach((statement, values) -> {
				String indent = " ".repeat(Math.max(0, statement.getBegin().orElseThrow().column - 1));
				context.editor()
					.insert(statement.getBegin().orElseThrow(),
							String.join(lineEnding + indent, values) + lineEnding + indent);
			});
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static List<Candidate> candidates(InspectionContext context) {
		LinkedHashMap<Group, List<AttributeCall>> grouped = new LinkedHashMap<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!ATTRIBUTE_METHODS.containsKey(call.getNameAsString()) || !call.getArguments().isEmpty()
					|| !(call.getScope().orElse(null) instanceof NameExpr file)) {
				continue;
			}
			Optional<Statement> statement = AstSupport.ancestor(call, Statement.class);
			if (statement.isEmpty() || !insertable(statement.orElseThrow())
					|| AstSupport.ancestor(call, LambdaExpr.class).isPresent()
					|| AstSupport.hasComment(context, statement.orElseThrow())
					|| !handlesIOException(context, statement.orElseThrow())) {
				continue;
			}
			Optional<String> type = TypeLookup.visibleType(context.compilationUnit(), file, call);
			if (type
				.filter(value -> ExpressionToolSupport.knownType(context.compilationUnit(), value, "java.io",
						Set.of("File")))
				.isEmpty()) {
				continue;
			}
			Group group = new Group(statement.orElseThrow(), file.getNameAsString());
			grouped.computeIfAbsent(group, ignored -> new ArrayList<>())
				.add(new AttributeCall(call, call.getNameAsString()));
		}
		return grouped.entrySet()
			.stream()
			.filter(entry -> entry.getValue().size() >= 2)
			.map(entry -> new Candidate(entry.getKey().statement(),
					entry.getValue().get(0).call().getScope().orElseThrow(), entry.getValue()))
			.toList();
	}

	private static boolean insertable(Statement statement) {
		return statement.getParentNode()
			.filter(parent -> parent instanceof BlockStmt || parent instanceof SwitchEntry)
			.isPresent();
	}

	private static boolean handlesIOException(InspectionContext context, Statement statement) {
		Optional<Node> parent = statement.getParentNode();
		while (parent.isPresent()) {
			Node node = parent.orElseThrow();
			if (node instanceof LambdaExpr) {
				return false;
			}
			if (node instanceof TryStmt tryStatement && tryStatement.getTryBlock().isAncestorOf(statement)
					&& tryStatement.getCatchClauses()
						.stream()
						.map(catchClause -> catchClause.getParameter().getType())
						.anyMatch(type -> catchesIOException(context, type))) {
				return true;
			}
			if (node instanceof CallableDeclaration<?> callable) {
				return callable.getThrownExceptions().stream().anyMatch(type -> catchesIOException(context, type));
			}
			parent = node.getParentNode();
		}
		return false;
	}

	private static boolean catchesIOException(InspectionContext context, Type type) {
		if (type.isUnionType()) {
			return type.asUnionType().getElements().stream().anyMatch(element -> catchesIOException(context, element));
		}
		String spelling = type.asString();
		return ExpressionToolSupport.knownType(context.compilationUnit(), spelling, "java.io", Set.of("IOException"))
				|| TypeLookup.isKnownJavaLangType(context.compilationUnit(), spelling,
						Set.of("Exception", "Throwable"));
	}

	private static String introducedType(InspectionContext context, String qualifiedName, boolean applyFixes) {
		String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
		boolean declared = context.compilationUnit()
			.findAll(TypeDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(simple));
		return declared ? qualifiedName : ImportSupport.useType(context, qualifiedName, applyFixes);
	}

	private static String availableName(Set<String> usedNames, String preferred) {
		if (!usedNames.contains(preferred)) {
			return preferred;
		}
		int index = 2;
		while (usedNames.contains(preferred + index)) {
			index++;
		}
		return preferred + index;
	}

	private record Group(Statement statement, String fileName) {
	}

	private record AttributeCall(MethodCallExpr call, String method) {
	}

	private record Candidate(Statement statement, Expression file, List<AttributeCall> calls) {
	}

}

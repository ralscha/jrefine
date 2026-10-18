package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import java.util.Optional;
import com.github.javaparser.ast.body.CallableDeclaration;
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
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports repeated File reads whose replacement requires reviewing filesystem semantics.
 */
public final class UseBulkFileAttributesTool implements PolicyInspectionTool {

	private static final Map<String, String> ATTRIBUTE_METHODS = Map.of("isDirectory", "isDirectory()", "isFile",
			"isRegularFile()", "lastModified", "lastModifiedTime().toMillis()", "length", "size()");

	@Override
	public String id() {
		return "use-bulk-file-attributes";
	}

	@Override
	public String description() {
		return "Review repeated File attribute reads for a possible bulk snapshot";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Finding> findings = candidates(context).stream()
			.map(candidate -> Finding.at(candidate.calls().get(0).call(),
					"Repeated File attribute reads could use a snapshot; review missing-file and IOException behavior"))
			.toList();
		return new ToolResult(findings, false);
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

	private record Group(Statement statement, String fileName) {
		@Override
		public boolean equals(Object other) {
			return other instanceof Group group && statement == group.statement && fileName.equals(group.fileName);
		}

		@Override
		public int hashCode() {
			return 31 * System.identityHashCode(statement) + fileName.hashCode();
		}
	}

	private record AttributeCall(MethodCallExpr call, String method) {
	}

	private record Candidate(Statement statement, Expression file, List<AttributeCall> calls) {
	}

}

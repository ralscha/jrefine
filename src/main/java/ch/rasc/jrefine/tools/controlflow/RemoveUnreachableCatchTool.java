package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;

/**
 * Removes a broader catch made unreachable by an earlier catch of the only thrown
 * exception.
 */
public final class RemoveUnreachableCatchTool implements InspectionTool {

	private static final Map<String, Parent> PARENTS = Map.ofEntries(
			Map.entry("FileNotFoundException", new Parent("IOException", "java.io")),
			Map.entry("EOFException", new Parent("IOException", "java.io")),
			Map.entry("UTFDataFormatException", new Parent("IOException", "java.io")),
			Map.entry("UnsupportedEncodingException", new Parent("IOException", "java.io")),
			Map.entry("CharConversionException", new Parent("IOException", "java.io")),
			Map.entry("InterruptedIOException", new Parent("IOException", "java.io")),
			Map.entry("ObjectStreamException", new Parent("IOException", "java.io")),
			Map.entry("MalformedURLException", new Parent("IOException", "java.net")),
			Map.entry("ProtocolException", new Parent("IOException", "java.net")),
			Map.entry("SocketException", new Parent("IOException", "java.net")),
			Map.entry("UnknownHostException", new Parent("IOException", "java.net")),
			Map.entry("HttpRetryException", new Parent("IOException", "java.net")),
			Map.entry("IOException", new Parent("Exception", "java.lang")));

	@Override
	public String id() {
		return "remove-unreachable-catch";
	}

	@Override
	public String description() {
		return "Remove catch sections proven unreachable";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<CatchClause> candidates = context.compilationUnit()
			.findAll(TryStmt.class)
			.stream()
			.flatMap(statement -> candidates(context, statement).stream())
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (CatchClause clause : candidates) {
			findings.add(Finding.at(clause, "Remove unreachable catch section"));
			if (applyFixes) {
				context.editor().removeLine(clause);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static List<CatchClause> candidates(InspectionContext context, TryStmt statement) {
		Optional<String> thrown = onlyThrownType(context, statement);
		if (thrown.isEmpty()) {
			return java.util.List.of();
		}
		String thrownType = thrown.orElseThrow();
		boolean caught = false;
		ArrayList<CatchClause> result = new ArrayList<>();
		for (CatchClause clause : statement.getCatchClauses()) {
			if (clause.getParameter().getType().isUnionType()) {
				return java.util.List.of();
			}
			String catchType = clause.getParameter().getType().asString();
			if (!catches(context, catchType, thrownType)) {
				continue;
			}
			if (caught && !Set.of("Exception", "Throwable").contains(simpleType(catchType))
					&& !AstSupport.hasComment(context, clause)) {
				result.add(clause);
			}
			caught = true;
		}
		return result;
	}

	private static Optional<String> onlyThrownType(InspectionContext context, TryStmt statement) {
		if (!statement.getResources().isEmpty() || statement.getTryBlock().getStatements().size() != 1
				|| !(statement.getTryBlock().getStatement(0) instanceof ThrowStmt thrown)
				|| !(thrown.getExpression() instanceof ObjectCreationExpr creation)
				|| creation.getAnonymousClassBody().isPresent()
				|| creation.getArguments().stream().anyMatch(argument -> !(argument instanceof LiteralExpr))) {
			return Optional.empty();
		}
		String type = creation.getType().asString();
		String simple = simpleType(type);
		Parent parent = PARENTS.get(simple);
		if (parent == null || !knownType(context, type, packageOf(simple))) {
			return Optional.empty();
		}
		return Optional.of(simple);
	}

	private static boolean catches(InspectionContext context, String catchType, String thrownType) {
		String expected = simpleType(catchType);
		String current = thrownType;
		while (true) {
			if (current.equals(expected)) {
				return knownType(context, catchType, packageOf(expected));
			}
			Parent parent = PARENTS.get(current);
			if (parent == null) {
				return false;
			}
			current = parent.type();
		}
	}

	private static String packageOf(String simple) {
		if ("Exception".equals(simple) || "Throwable".equals(simple)) {
			return "java.lang";
		}
		Parent parent = PARENTS.get(simple);
		if (parent != null && ("IOException".equals(simple) || Set
			.of("FileNotFoundException", "EOFException", "UTFDataFormatException", "UnsupportedEncodingException",
					"CharConversionException", "InterruptedIOException", "ObjectStreamException")
			.contains(simple))) {
			return "java.io";
		}
		return "java.net";
	}

	private static boolean knownType(InspectionContext context, String spelling, String packageName) {
		String simple = simpleType(spelling);
		if (context.compilationUnit()
			.findAll(TypeDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(simple))) {
			return false;
		}
		if (spelling.contains(".")) {
			return spelling.equals(packageName + "." + simple);
		}
		if ("java.lang".equals(packageName)) {
			return context.compilationUnit()
				.getImports()
				.stream()
				.filter(imported -> !imported.isAsterisk() && imported.getName().getIdentifier().equals(simple))
				.allMatch(imported -> imported.getNameAsString().equals(packageName + "." + simple));
		}
		List<ImportDeclaration> explicit = context.compilationUnit()
			.getImports()
			.stream()
			.filter(imported -> !imported.isAsterisk() && imported.getName().getIdentifier().equals(simple))
			.toList();
		if (!explicit.isEmpty()) {
			return explicit.stream()
				.allMatch(imported -> imported.getNameAsString().equals(packageName + "." + simple));
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> imported.isAsterisk() && imported.getNameAsString().equals(packageName));
	}

	private static String simpleType(String type) {
		int dot = type.lastIndexOf('.');
		return dot < 0 ? type : type.substring(dot + 1);
	}

	private record Parent(String type, String packageName) {
	}

}

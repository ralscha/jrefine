package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.UnionType;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reports ineffective, suppressed, reversed, and mechanically simplifiable JUnit
 * assertions.
 */
public final class ReportTestAssertionBugsTool implements InspectionTool {

	private static final Set<String> ASSERTION_METHODS = Set.of("assertEquals", "assertNotEquals", "assertTrue",
			"assertFalse", "assertNull", "assertNotNull", "assertSame", "assertNotSame", "fail");

	@Override
	public String id() {
		return "report-test-assertion-bugs";
	}

	@Override
	public String description() {
		return "Report constant, suppressed, reversed, and simplifiable JUnit assertions";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			Framework framework = framework(context, call).orElse(null);
			if (framework == null) {
				continue;
			}
			constantAssertion(context, call, framework, findings);
			misorderedEquals(context, call, framework, findings);
			simplifiableAssertion(context, call, framework, findings);
			suppressedAssertion(context, call, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void constantAssertion(InspectionContext context, MethodCallExpr call, Framework framework,
			List<Finding> findings) {
		if (!Set.of("assertTrue", "assertFalse").contains(call.getNameAsString())) {
			return;
		}
		int condition = junit4MessageOffset(context, call, framework, 2);
		if (call.getArguments().size() > condition
				&& call.getArgument(condition) instanceof BooleanLiteralExpr literal) {
			findings.add(Finding.at(literal, "Assertion has constant " + literal.getValue() + " argument"));
		}
	}

	private static void misorderedEquals(InspectionContext context, MethodCallExpr call, Framework framework,
			List<Finding> findings) {
		if (!"assertEquals".equals(call.getNameAsString())) {
			return;
		}
		int expected = junit4MessageOffset(context, call, framework, 3);
		int actual = expected + 1;
		if (call.getArguments().size() <= actual) {
			return;
		}
		Expression expectedValue = call.getArgument(expected);
		Expression actualValue = call.getArgument(actual);
		if (!obviousConstant(expectedValue) && obviousConstant(actualValue)) {
			findings
				.add(Finding.at(call, "JUnit assertEquals() arguments appear reversed; expected value comes first"));
		}
	}

	private static void simplifiableAssertion(InspectionContext context, MethodCallExpr call, Framework framework,
			List<Finding> findings) {
		if (!Set.of("assertEquals", "assertNotEquals").contains(call.getNameAsString())) {
			return;
		}
		int expected = junit4MessageOffset(context, call, framework, 3);
		int actual = expected + 1;
		if (call.getArguments().size() <= actual) {
			return;
		}
		Expression value = call.getArgument(expected);
		if (value instanceof BooleanLiteralExpr || value instanceof NullLiteralExpr) {
			findings.add(Finding.at(call, "Assertion can use a dedicated boolean or null assertion"));
		}
	}

	private static void suppressedAssertion(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		TryStmt statement = enclosingTry(call).orElse(null);
		if (statement == null || !statement.getTryBlock().isAncestorOf(call)) {
			return;
		}
		for (CatchClause clause : statement.getCatchClauses()) {
			if (catchesAssertion(context, clause) && !rethrows(clause)) {
				findings.add(Finding.at(call, "Assertion failure is suppressed by the surrounding catch block"));
				return;
			}
		}
	}

	private static Optional<TryStmt> enclosingTry(MethodCallExpr call) {
		Node current = call;
		while (current.getParentNode().isPresent()) {
			current = current.getParentNode().orElseThrow();
			if (current instanceof LambdaExpr || current instanceof CallableDeclaration<?>
					|| current instanceof TypeDeclaration<?>) {
				return Optional.empty();
			}
			if (current instanceof TryStmt statement) {
				return Optional.of(statement);
			}
		}
		return Optional.empty();
	}

	private static boolean catchesAssertion(InspectionContext context, CatchClause clause) {
		List<ReferenceType> types = clause.getParameter().getType() instanceof UnionType union
				? List.copyOf(union.getElements()) : List.of(clause.getParameter().getType().asReferenceType());
		return types.stream()
			.map(ReferenceType::asString)
			.anyMatch(type -> TypeLookup.isKnownType(context.compilationUnit(), type, "java.lang",
					Set.of("AssertionError", "Error", "Throwable")));
	}

	private static boolean rethrows(CatchClause clause) {
		String parameter = clause.getParameter().getNameAsString();
		return clause.getBody()
			.findAll(ThrowStmt.class)
			.stream()
			.map(ThrowStmt::getExpression)
			.filter(NameExpr.class::isInstance)
			.map(NameExpr.class::cast)
			.anyMatch(name -> name.getNameAsString().equals(parameter));
	}

	private static Optional<Framework> framework(InspectionContext context, MethodCallExpr call) {
		if (!ASSERTION_METHODS.contains(call.getNameAsString())) {
			return Optional.empty();
		}
		if (call.getScope().isPresent()) {
			String owner = call.getScope().orElseThrow().toString();
			if (TypeLookup.isKnownType(context.compilationUnit(), owner, "org.junit.jupiter.api",
					Set.of("Assertions"))) {
				return Optional.of(Framework.JUNIT5);
			}
			if (TypeLookup.isKnownType(context.compilationUnit(), owner, "org.junit", Set.of("Assert"))) {
				return Optional.of(Framework.JUNIT4);
			}
			return Optional.empty();
		}
		TypeDeclaration<?> owner = call.findAncestor(TypeDeclaration.class).orElse(null);
		if (owner != null && owner.getMethodsByName(call.getNameAsString())
			.stream()
			.anyMatch(method -> method.getParameters().size() == call.getArguments().size())) {
			return Optional.empty();
		}
		String method = call.getNameAsString();
		if (staticImport(context, "org.junit.jupiter.api.Assertions", method)) {
			return Optional.of(Framework.JUNIT5);
		}
		if (staticImport(context, "org.junit.Assert", method)) {
			return Optional.of(Framework.JUNIT4);
		}
		return Optional.empty();
	}

	private static boolean staticImport(InspectionContext context, String owner, String method) {
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> imported.isStatic()
					&& (imported.isAsterisk() && imported.getNameAsString().equals(owner)
							|| !imported.isAsterisk() && imported.getNameAsString().equals(owner + "." + method)));
	}

	private static boolean obviousConstant(Expression expression) {
		return expression.isLiteralExpr() || expression.isClassExpr() || expression.isFieldAccessExpr()
				&& expression.asFieldAccessExpr().getNameAsString().matches("[A-Z][A-Z0-9_]*");
	}

	private static int junit4MessageOffset(InspectionContext context, MethodCallExpr call, Framework framework,
			int minimumArguments) {
		return framework == Framework.JUNIT4 && call.getArguments().size() >= minimumArguments
				&& definitelyString(context, call.getArgument(0), call) ? 1 : 0;
	}

	private static boolean definitelyString(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr) {
			return true;
		}
		if (expression instanceof BinaryExpr binary) {
			return definitelyString(context, binary.getLeft(), use)
					|| definitelyString(context, binary.getRight(), use);
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
			.isPresent();
	}

	private enum Framework {

		JUNIT4, JUNIT5

	}

}

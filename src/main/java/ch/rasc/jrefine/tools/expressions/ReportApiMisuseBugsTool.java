package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.ThrowStmt;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.UnaryExpr;
import java.util.HashSet;

/** Reports locally provable misuse of common JDK APIs. */
public final class ReportApiMisuseBugsTool implements InspectionTool {

	private static final Set<String> ROUNDING = Set.of("round", "ceil", "floor", "rint");

	private static final Set<String> PURE_MATH_METHODS = Set.of("abs", "ceil", "floor", "max", "min", "round", "sqrt");

	private static final Set<String> PURE_STRING_METHODS = Set.of("trim", "strip", "substring", "replace", "replaceAll",
			"toLowerCase", "toUpperCase", "concat");

	private static final Set<String> PURE_OPTIONAL_METHODS = Set.of("get", "orElse", "map", "filter");

	@Override
	public String id() {
		return "report-api-misuse-bugs";
	}

	@Override
	public String description() {
		return "Report suspicious Math, Optional, array, regex, Cleaner, and reflection API calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		casts(context, findings);
		creations(context, findings);
		calls(context, findings);
		optionalGets(context, findings);
		ignoredResults(context, findings);
		suspiciousRead(context, findings);
		integerDivision(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void casts(InspectionContext context, List<Finding> findings) {
		for (CastExpr cast : context.compilationUnit().findAll(CastExpr.class)) {
			if (cast.getType().isPrimitiveType() && "int".equals(cast.getType().asString())
					&& cast.getExpression() instanceof MethodCallExpr call && "random".equals(call.getNameAsString())
					&& call.getArguments().isEmpty()
					&& call.getScope()
						.filter(scope -> Set.of("Math", "java.lang.Math").contains(scope.toString()))
						.isPresent()) {
				findings.add(Finding.at(cast, "Math.random() is cast directly to int and always produces zero"));
			}
		}
	}

	private static void creations(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			String type = creation.getType().getNameAsString();
			boolean ignored = isIgnoredAllocation(creation);
			if ("ScheduledThreadPoolExecutor".equals(type) && !creation.getArguments().isEmpty()
					&& zero(creation.getArgument(0))) {
				findings.add(Finding.at(creation, "ScheduledThreadPoolExecutor is created with zero core threads"));
			}
			if (Set.of("StringBuilder", "StringBuffer").contains(type) && creation.getArguments().size() == 1
					&& NumericSupport.typeOf(context, creation.getArgument(0), creation)
						.filter(value -> "char".equals(value))
						.isPresent()) {
				findings.add(Finding.at(creation, type + " constructor interprets char as an integer capacity"));
			}
			if (throwableType(context, type) && ignored) {
				findings.add(Finding.at(creation, "Throwable is instantiated but never thrown"));
			}
			if ("StringTokenizer".equals(type) && creation.getArguments().size() >= 2) {
				duplicateDelimiters(creation.getArgument(1)).ifPresent(value -> findings
					.add(Finding.at(creation.getArgument(1), "Duplicated delimiters in StringTokenizer")));
			}
		}
	}

	private static boolean isIgnoredAllocation(ObjectCreationExpr creation) {
		if (!(creation.getParentNode().orElse(null) instanceof ExpressionStmt statement)) {
			return false;
		}
		if (statement.getParentNode().orElse(null) instanceof LambdaExpr) {
			return false;
		}
		if (statement.getParentNode().orElse(null) instanceof SwitchEntry entry) {
			return entry.getType() == SwitchEntry.Type.STATEMENT_GROUP;
		}
		return true;
	}

	private static void calls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			Expression scope = call.getScope().orElse(null);
			if (scope != null
					&& Set.of("Math", "StrictMath", "java.lang.Math", "java.lang.StrictMath").contains(scope.toString())
					&& ROUNDING.contains(call.getNameAsString()) && call.getArguments().size() == 1
					&& NumericSupport.typeOf(context, call.getArgument(0), call)
						.filter(NumericSupport::isIntegral)
						.isPresent()) {
				findings.add(Finding.at(call, "Math rounding method is called with an integral argument"));
			}
			if ("setCorePoolSize".equals(call.getNameAsString()) && call.getArguments().size() == 1
					&& zero(call.getArgument(0)) && scope != null
					&& TypeLookup.visibleType(context.compilationUnit(), scope, call)
						.map(ReportApiMisuseBugsTool::simple)
						.filter(type -> "ScheduledThreadPoolExecutor".equals(type))
						.isPresent()) {
				findings.add(Finding.at(call, "ScheduledThreadPoolExecutor core pool size is set to zero"));
			}
			if ("nextToken".equals(call.getNameAsString()) && call.getArguments().size() == 1) {
				duplicateDelimiters(call.getArgument(0)).ifPresent(value -> findings
					.add(Finding.at(call.getArgument(0), "Duplicated delimiters in StringTokenizer")));
			}
			chronoUnit(context, call).ifPresent(message -> findings.add(Finding.at(call, message)));
			stringCaseMismatch(call).ifPresent(message -> findings.add(Finding.at(call, message)));
			if ("getClass".equals(call.getNameAsString()) && call.getArguments().isEmpty() && scope != null
					&& TypeLookup.visibleType(context.compilationUnit(), scope, call)
						.map(ReportApiMisuseBugsTool::simple)
						.filter(type -> "Class".equals(type))
						.isPresent()) {
				findings.add(Finding.at(call, "Suspicious getClass() call on a Class object"));
			}
			if ("newInstance".equals(call.getNameAsString()) && call.getArguments().isEmpty() && scope != null
					&& (scope.isClassExpr() || TypeLookup.visibleType(context.compilationUnit(), scope, call)
						.map(ReportApiMisuseBugsTool::simple)
						.filter(type -> "Class".equals(type))
						.isPresent())) {
				findings.add(Finding.at(call, "Unsafe call to deprecated Class.newInstance()"));
			}
			optionalFactory(call).ifPresent(message -> findings.add(Finding.at(call, message)));
			suspiciousRegex(call).ifPresent(message -> findings.add(Finding.at(call, message)));
			suspiciousArrays(context, call).ifPresent(message -> findings.add(Finding.at(call, message)));
			suspiciousArraycopy(context, call).ifPresent(message -> findings.add(Finding.at(call, message)));
			cleanerCapture(call).ifPresent(message -> findings.add(Finding.at(call, message)));
			magicConstant(context, call).ifPresent(message -> findings.add(Finding.at(call, message)));
		}
	}

	private static void optionalGets(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!Set.of("get", "getAsInt", "getAsLong", "getAsDouble").contains(call.getNameAsString())
					|| call.getArguments().size() != 0
					|| !(call.getScope().orElse(null) instanceof NameExpr optional)) {
				continue;
			}
			String type = TypeLookup.visibleType(context.compilationUnit(), optional, call)
				.map(ReportApiMisuseBugsTool::simple)
				.orElse("");
			if (!Set.of("Optional", "OptionalInt", "OptionalLong", "OptionalDouble").contains(type)) {
				continue;
			}
			if (!guarded(call, optional.getNameAsString())) {
				findings.add(Finding.at(call, "Optional.get() is called without a matching presence check"));
			}
		}
	}

	private static boolean guarded(MethodCallExpr call, String name) {
		if (shortCircuitGuard(call, name)) {
			return true;
		}
		Optional<Node> parent = call.getParentNode();
		while (parent.isPresent()) {
			if (parent.orElseThrow() instanceof ConditionalExpr conditional) {
				boolean inThen = conditional.getThenExpr() == call || conditional.getThenExpr().isAncestorOf(call);
				boolean inElse = conditional.getElseExpr() == call || conditional.getElseExpr().isAncestorOf(call);
				if (inThen && presentImpliesTrue(conditional.getCondition(), name)
						|| inElse && absentImpliesTrue(conditional.getCondition(), name)) {
					return true;
				}
			}
			if (parent.orElseThrow() instanceof IfStmt statement && statement.getThenStmt().isAncestorOf(call)
					&& statement.getCondition()
						.findAll(MethodCallExpr.class)
						.stream()
						.anyMatch(check -> "isPresent".equals(check.getNameAsString())
								&& check.getScope().filter(scope -> scope.toString().equals(name)).isPresent())) {
				return true;
			}
			parent = parent.orElseThrow().getParentNode();
		}
		return precedingAbsenceExit(call, name);
	}

	private static boolean shortCircuitGuard(MethodCallExpr call, String name) {
		Node child = call;
		Optional<Node> parent = call.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof BinaryExpr binary
					&& (binary.getRight() == child || binary.getRight().isAncestorOf(child))) {
				if (binary.getOperator() == BinaryExpr.Operator.OR && absentImpliesTrue(binary.getLeft(), name)
						|| binary.getOperator() == BinaryExpr.Operator.AND
								&& presentImpliesTrue(binary.getLeft(), name)) {
					return true;
				}
			}
			if (value instanceof Statement) {
				break;
			}
			child = value;
			parent = value.getParentNode();
		}
		return false;
	}

	private static boolean precedingAbsenceExit(MethodCallExpr call, String name) {
		Node current = call;
		while (true) {
			BlockStmt block = current.findAncestor(BlockStmt.class).orElse(null);
			if (block == null) {
				return false;
			}
			Statement use = null;
			for (Statement statement : block.getStatements()) {
				if (statement == current || statement.isAncestorOf(current)) {
					use = statement;
					break;
				}
			}
			if (use == null) {
				current = block;
				continue;
			}
			int useIndex = block.getStatements().indexOf(use);
			for (int index = 0; index < useIndex; index++) {
				if (block.getStatement(index) instanceof IfStmt guard && guard.getElseStmt().isEmpty()
						&& absentImpliesTrue(guard.getCondition(), name) && terminates(guard.getThenStmt())) {
					return true;
				}
			}
			current = block;
		}
	}

	private static boolean terminates(Statement statement) {
		if (statement instanceof ReturnStmt || statement instanceof ThrowStmt || statement instanceof ContinueStmt
				|| statement instanceof BreakStmt) {
			return true;
		}
		return statement instanceof BlockStmt block && !block.getStatements().isEmpty()
				&& terminates(block.getStatement(block.getStatements().size() - 1));
	}

	private static boolean absentImpliesTrue(Expression expression, String name) {
		Expression value = unwrap(expression);
		if (presenceCall(value, name, "isEmpty")) {
			return true;
		}
		if (value instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
				&& presenceCall(unwrap(unary.getExpression()), name, "isPresent")) {
			return true;
		}
		return value instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.OR
				&& (absentImpliesTrue(binary.getLeft(), name) || absentImpliesTrue(binary.getRight(), name));
	}

	private static boolean presentImpliesTrue(Expression expression, String name) {
		Expression value = unwrap(expression);
		if (presenceCall(value, name, "isPresent")) {
			return true;
		}
		if (value instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
				&& presenceCall(unwrap(unary.getExpression()), name, "isEmpty")) {
			return true;
		}
		return value instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.AND
				&& (presentImpliesTrue(binary.getLeft(), name) || presentImpliesTrue(binary.getRight(), name));
	}

	private static boolean presenceCall(Expression expression, String name, String method) {
		return expression instanceof MethodCallExpr call && method.equals(call.getNameAsString())
				&& call.getArguments().isEmpty()
				&& call.getScope().filter(scope -> scope.toString().equals(name)).isPresent();
	}

	private static Expression unwrap(Expression expression) {
		Expression value = expression;
		while (value.isEnclosedExpr()) {
			value = value.asEnclosedExpr().getInner();
		}
		return value;
	}

	private static void ignoredResults(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(ExpressionStmt.class)
			.stream()
			.filter(statement -> !(statement.getParentNode().orElse(null) instanceof LambdaExpr))
			.map(ExpressionStmt::getExpression)
			.filter(MethodCallExpr.class::isInstance)
			.map(MethodCallExpr.class::cast)
			.filter(call -> pureCallWithIgnoredResult(context, call))
			.forEach(call -> findings.add(Finding.at(call, "Result of method call is ignored")));
	}

	private static boolean pureCallWithIgnoredResult(InspectionContext context, MethodCallExpr call) {
		Expression scope = call.getScope().orElse(null);
		if (scope == null) {
			return false;
		}
		String method = call.getNameAsString();
		String scopeText = scope.toString();
		if (PURE_MATH_METHODS.contains(method) && ExpressionToolSupport.knownType(context.compilationUnit(), scopeText,
				"java.lang", Set.of("Math", "StrictMath"))) {
			return true;
		}
		if (PURE_STRING_METHODS.contains(method)
				&& knownReceiverType(context, scope, call, "java.lang", Set.of("String"))) {
			return true;
		}
		if ("valueOf".equals(method) && ExpressionToolSupport.knownType(context.compilationUnit(), scopeText,
				"java.lang", Set.of("String"))) {
			return true;
		}
		if (Set.of("valueOf", "parseInt", "parseLong").contains(method)
				&& ExpressionToolSupport.knownType(context.compilationUnit(), scopeText, "java.lang",
						Set.of("Integer", "Long", "Double", "Float", "Short", "Byte", "Boolean", "Character"))) {
			return true;
		}
		return PURE_OPTIONAL_METHODS.contains(method) && knownReceiverType(context, scope, call, "java.util",
				Set.of("Optional", "OptionalInt", "OptionalLong", "OptionalDouble"));
	}

	private static boolean knownReceiverType(InspectionContext context, Expression scope, MethodCallExpr call,
			String packageName, Set<String> types) {
		if (scope instanceof StringLiteralExpr) {
			return "java.lang".equals(packageName) && types.contains("String");
		}
		return TypeLookup.visibleType(context.compilationUnit(), scope, call)
			.filter(type -> ExpressionToolSupport.knownType(context.compilationUnit(), type, packageName, types))
			.isPresent();
	}

	private static void suspiciousRead(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			if (!"read".equals(method.getNameAsString()) || !method.getParameters().isEmpty()
					|| !"int".equals(method.getType().asString()) || method.getBody().isEmpty()) {
				continue;
			}
			for (ReturnStmt returned : method.getBody().orElseThrow().findAll(ReturnStmt.class)) {
				returned.getExpression()
					.filter(expression -> NumericSupport.typeOf(context, expression, returned)
						.filter(type -> "byte".equals(type))
						.isPresent())
					.ifPresent(expression -> findings
						.add(Finding.at(expression, "Suspicious byte value returned from InputStream.read()")));
			}
		}
	}

	private static void integerDivision(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			if (binary.getOperator() != BinaryExpr.Operator.DIVIDE
					|| NumericSupport.typeOf(context, binary, binary)
						.filter(type -> "int".equals(type) || "long".equals(type))
						.isEmpty()
					|| NumericSupport.expectedType(context, binary).filter(NumericSupport::isFloatingPoint).isEmpty()) {
				continue;
			}
			findings.add(Finding.at(binary, "Suspicious integer division is assigned to a floating-point target"));
		}
	}

	private static Optional<String> chronoUnit(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty() || call.getArguments().isEmpty()) {
			return Optional.empty();
		}
		String receiver = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
			.map(ReportApiMisuseBugsTool::simple)
			.orElse("");
		String constant = call.getArgument(call.getArguments().size() - 1).toString();
		boolean date = Set.of("LocalDate", "Year", "YearMonth", "MonthDay").contains(receiver);
		boolean time = Set.of("LocalTime", "OffsetTime").contains(receiver);
		if (date && (constant.contains("HOUR") || constant.contains("MINUTE") || constant.contains("SECOND")
				|| constant.contains("NANOS"))) {
			return Optional.of("java.time call uses an unsupported time field or unit");
		}
		if (time && (constant.contains("DAY") || constant.contains("MONTH") || constant.contains("YEAR"))) {
			return Optional.of("java.time call uses an unsupported date field or unit");
		}
		return Optional.empty();
	}

	private static Optional<String> stringCaseMismatch(MethodCallExpr call) {
		if (!(call.getScope().orElse(null) instanceof StringLiteralExpr source) || call.getArguments().size() != 1
				|| !(call.getArgument(0) instanceof StringLiteralExpr searched)
				|| !Set.of("contains", "indexOf", "lastIndexOf", "startsWith", "endsWith")
					.contains(call.getNameAsString())) {
			return Optional.empty();
		}
		String left = source.asString();
		String right = searched.asString();
		boolean mismatch = lettersOnlyOneCase(left, true) && lettersOnlyOneCase(right, false)
				|| lettersOnlyOneCase(left, false) && lettersOnlyOneCase(right, true);
		return mismatch ? Optional.of("Mismatched case in String search always produces no match") : Optional.empty();
	}

	private static boolean lettersOnlyOneCase(String value, boolean upper) {
		int[] letters = value.chars().filter(Character::isLetter).toArray();
		return letters.length > 0 && java.util.Arrays.stream(letters)
			.allMatch(character -> upper ? Character.isUpperCase(character) : Character.isLowerCase(character));
	}

	private static Optional<String> optionalFactory(MethodCallExpr call) {
		if (!"ofNullable".equals(call.getNameAsString()) || call.getArguments().size() != 1
				|| call.getScope()
					.filter(scope -> Set.of("Optional", "java.util.Optional").contains(scope.toString()))
					.isEmpty()) {
			return Optional.empty();
		}
		Expression argument = call.getArgument(0);
		if (argument instanceof NullLiteralExpr) {
			return Optional.of("Optional.ofNullable(null) can be replaced with Optional.empty()");
		}
		if (argument instanceof ObjectCreationExpr || argument instanceof ArrayCreationExpr
				|| argument instanceof StringLiteralExpr) {
			return Optional.of("Optional.ofNullable() is called with an obviously non-null argument");
		}
		return Optional.empty();
	}

	private static Optional<String> suspiciousRegex(MethodCallExpr call) {
		if (!Set.of("replaceAll", "replaceFirst", "split").contains(call.getNameAsString())
				|| call.getArguments().isEmpty() || !(call.getArgument(0) instanceof StringLiteralExpr regex)) {
			return Optional.empty();
		}
		return regex.asString().length() == 1 && ".[]{}()*+-?^$|\\".contains(regex.asString())
				? Optional.of("Suspicious single regex metacharacter argument") : Optional.empty();
	}

	private static Optional<String> suspiciousArrays(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().filter(scope -> Set.of("Arrays", "java.util.Arrays").contains(scope.toString())).isEmpty()
				|| !"fill".equals(call.getNameAsString()) || call.getArguments().size() < 2) {
			return Optional.empty();
		}
		String array = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), call.getArgument(0), call)
			.orElse("");
		String value = NumericSupport.typeOf(context, call.getArgument(call.getArguments().size() - 1), call)
			.orElse("");
		if (array.endsWith("[]")) {
			String component = array.substring(0, array.length() - 2);
			if (NumericSupport.isNumeric(component) && NumericSupport.isNumeric(value)
					&& !NumericSupport.canWiden(value, component)) {
				return Optional.of("Suspicious Arrays.fill() value does not fit the array component type");
			}
		}
		return Optional.empty();
	}

	private static Optional<String> suspiciousArraycopy(InspectionContext context, MethodCallExpr call) {
		if (!"arraycopy".equals(call.getNameAsString()) || call.getArguments().size() != 5
				|| call.getScope()
					.filter(scope -> Set.of("System", "java.lang.System").contains(scope.toString()))
					.isEmpty()) {
			return Optional.empty();
		}
		for (Integer index : List.of(1, 3, 4)) {
			if (negative(call.getArgument(index))) {
				return Optional.of("Suspicious System.arraycopy() uses a negative index or length");
			}
		}
		String source = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), call.getArgument(0), call)
			.orElse("");
		String target = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), call.getArgument(2), call)
			.orElse("");
		if (source.endsWith("[]") && target.endsWith("[]") && !simple(source).equals(simple(target))) {
			return Optional.of("Suspicious System.arraycopy() uses incompatible array types");
		}
		return Optional.empty();
	}

	private static Optional<String> cleanerCapture(MethodCallExpr call) {
		if (!"register".equals(call.getNameAsString()) || call.getArguments().size() != 2
				|| !(call.getArgument(0) instanceof NameExpr registered)
				|| !(call.getArgument(1) instanceof LambdaExpr cleanup)) {
			return Optional.empty();
		}
		return cleanup.findAll(NameExpr.class)
			.stream()
			.anyMatch(name -> name.getNameAsString().equals(registered.getNameAsString()))
					? Optional.of("Cleaner action captures the registered object") : Optional.empty();
	}

	private static Optional<String> magicConstant(InspectionContext context, MethodCallExpr call) {
		if ("setPriority".equals(call.getNameAsString()) && call.getArguments().size() == 1
				&& call.getArgument(0) instanceof IntegerLiteralExpr
				&& call.getScope()
					.filter(scope -> knownReceiverType(context, scope, call, "java.lang", Set.of("Thread")))
					.isPresent()) {
			return Optional.of("Numeric thread priority can be replaced with a named magic constant");
		}
		if ("set".equals(call.getNameAsString()) && call.getArguments().size() >= 2
				&& call.getArgument(0) instanceof IntegerLiteralExpr
				&& call.getScope()
					.filter(scope -> knownReceiverType(context, scope, call, "java.util", Set.of("Calendar")))
					.isPresent()) {
			return Optional.of("Numeric API argument can be replaced with a named magic constant");
		}
		return Optional.empty();
	}

	private static boolean throwableType(InspectionContext context, String type) {
		if (Set.of("Throwable", "Exception", "RuntimeException", "Error", "AssertionError").contains(type)
				|| type.endsWith("Exception") || type.endsWith("Error")) {
			return true;
		}
		return context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(declaration -> declaration.getNameAsString().equals(type))
			.anyMatch(declaration -> declaration.getExtendedTypes()
				.stream()
				.anyMatch(parent -> parent.getNameAsString().endsWith("Exception")
						|| parent.getNameAsString().endsWith("Error")));
	}

	private static Optional<String> duplicateDelimiters(Expression expression) {
		if (!(expression instanceof StringLiteralExpr literal)) {
			return Optional.empty();
		}
		HashSet<Integer> seen = new HashSet<>();
		return literal.asString().chars().anyMatch(character -> !seen.add(character)) ? Optional.of(literal.asString())
				: Optional.empty();
	}

	private static boolean zero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private static boolean negative(Expression expression) {
		return expression instanceof UnaryExpr unary
				&& unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS
				&& unary.getExpression() instanceof IntegerLiteralExpr;
	}

	private static String simple(String type) {
		String currentType = type;
		while (currentType.endsWith("[]")) {
			currentType = currentType.substring(0, currentType.length() - 2);
		}
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

}

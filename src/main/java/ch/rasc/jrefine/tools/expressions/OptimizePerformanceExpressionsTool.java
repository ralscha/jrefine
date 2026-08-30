package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Applies local performance rewrites whose API and evaluation semantics are provable. */
public final class OptimizePerformanceExpressionsTool implements InspectionTool {

	private static final Set<String> BUILDERS = Set.of("StringBuilder", "StringBuffer", "Appendable");

	@Override
	public String id() {
		return "optimize-performance-expressions";
	}

	@Override
	public String description() {
		return "Use efficient JDK forms for common temporary objects and expression APIs";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.map(creation -> creationCandidate(context, creation))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit().findAll(MethodCallExpr.class).forEach(call -> {
			methodCandidate(context, call).ifPresent(candidates::add);
			temporaryToString(context, call).ifPresent(candidates::add);
		});
		context.compilationUnit()
			.findAll(CastExpr.class)
			.stream()
			.map(cast -> randomCandidate(context, cast))
			.flatMap(Optional::stream)
			.forEach(candidates::add);

		List<Candidate> nonOverlapping = candidates.stream()
			.filter(candidate -> candidates.stream()
				.noneMatch(other -> other != candidate && other.node().isAncestorOf(candidate.node())))
			.toList();
		String files = nonOverlapping.stream().anyMatch(Candidate::needsFiles)
				? ImportSupport.useType(context, "java.nio.file.Files", applyFixes) : "Files";
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : nonOverlapping) {
			findings.add(Finding.at(candidate.node(), candidate.message()));
			if (applyFixes) {
				context.editor()
					.replace(candidate.node().getRange().orElseThrow(),
							candidate.replacement().replace("$FILES$", files));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> creationCandidate(InspectionContext context, ObjectCreationExpr creation) {
		if (creation.getAnonymousClassBody().isPresent() || AstSupport.hasComment(context, creation)) {
			return Optional.empty();
		}
		String type = creation.getType().getNameAsString();
		if ("Boolean".equals(type) && creation.getArguments().size() == 1 && ExpressionToolSupport
			.knownType(context.compilationUnit(), creation.getType().asString(), "java.lang", Set.of("Boolean"))) {
			return Optional.of(new Candidate(creation, "Replace Boolean constructor with valueOf()",
					context.editor().text(creation.getType()) + ".valueOf("
							+ context.editor().text(creation.getArgument(0)) + ")",
					false));
		}
		if (Set.of("FileInputStream", "FileOutputStream").contains(type) && creation.getArguments().size() == 1
				&& ExpressionToolSupport.knownType(context.compilationUnit(), creation.getType().asString(), "java.io",
						Set.of(type))
				&& streamContextAccepts(creation, "FileInputStream".equals(type) ? "InputStream" : "OutputStream")) {
			Expression argument = creation.getArgument(0);
			String argumentType = TypeLookup.visibleType(context.compilationUnit(), argument, creation)
				.map(ExpressionToolSupport::simpleName)
				.orElse("");
			if ("File".equals(argumentType) && ExpressionToolSupport.knownType(context.compilationUnit(), argumentType,
					"java.io", Set.of("File"))) {
				String method = "FileInputStream".equals(type) ? "newInputStream" : "newOutputStream";
				return Optional.of(new Candidate(creation, "Construct stream with Files." + method + "()",
						"$FILES$." + method + "(" + context.editor().text(argument) + ".toPath())", true));
			}
		}
		return Optional.empty();
	}

	private static Optional<Candidate> methodCandidate(InspectionContext context, MethodCallExpr call) {
		if (AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Optional<Candidate> toArray = toArrayCandidate(context, call);
		if (toArray.isPresent()) {
			return toArray;
		}
		Optional<Candidate> equality = equalityCandidate(context, call);
		if (equality.isPresent()) {
			return equality;
		}
		Optional<Candidate> classObject = classObjectCandidate(context, call);
		if (classObject.isPresent()) {
			return classObject;
		}
		Optional<Candidate> character = characterSearchCandidate(context, call);
		if (character.isPresent()) {
			return character;
		}
		Optional<Candidate> append = appendCandidate(context, call);
		if (append.isPresent()) {
			return append;
		}
		return temporaryFromString(context, call);
	}

	private static Optional<Candidate> toArrayCandidate(InspectionContext context, MethodCallExpr call) {
		if (!"toArray".equals(call.getNameAsString()) || call.getArguments().size() != 1
				|| !(call.getArgument(0) instanceof ArrayCreationExpr array) || array.getInitializer().isPresent()
				|| array.getLevels().size() != 1 || array.getLevels().get(0).getDimension().isEmpty()
				|| !(array.getLevels().get(0).getDimension().orElseThrow() instanceof IntegerLiteralExpr size)
				|| size.asNumber().intValue() != 0) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(array, "Use array constructor reference with Collection.toArray()",
				context.editor().text(array.getElementType()) + "[]::new", false));
	}

	private static Optional<Candidate> equalityCandidate(InspectionContext context, MethodCallExpr call) {
		if (!"equals".equals(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().size() != 1) {
			return Optional.empty();
		}
		String left = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call).orElse("");
		String right = ExpressionToolSupport.visibleSimpleType(context, call.getArgument(0), call).orElse("");
		boolean localEnum = !left.isEmpty() && left.equals(right)
				&& context.compilationUnit()
					.findAll(EnumDeclaration.class)
					.stream()
					.anyMatch(declaration -> declaration.getNameAsString().equals(left));
		boolean classObject = "Class".equals(left) && "Class".equals(right)
				&& ExpressionToolSupport.knownType(context.compilationUnit(), left, "java.lang", Set.of("Class"));
		if (!localEnum && !classObject) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, "Replace identity-based equals() with ==",
				"(" + context.editor().text(call.getScope().orElseThrow()) + " == "
						+ context.editor().text(call.getArgument(0)) + ")",
				false));
	}

	private static Optional<Candidate> classObjectCandidate(InspectionContext context, MethodCallExpr call) {
		if (!"getClass".equals(call.getNameAsString()) || !call.getArguments().isEmpty()
				|| !(call.getScope().orElse(null) instanceof ObjectCreationExpr creation)
				|| !creation.getArguments().isEmpty() || creation.getAnonymousClassBody().isPresent()) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, "Use class literal without instantiating an object",
				context.editor().text(creation.getType()) + ".class", false));
	}

	private static Optional<Candidate> characterSearchCandidate(InspectionContext context, MethodCallExpr call) {
		if (!Set.of("indexOf", "lastIndexOf").contains(call.getNameAsString()) || call.getScope().isEmpty()
				|| call.getArguments().isEmpty() || !(call.getArgument(0) instanceof StringLiteralExpr literal)
				|| literal.asString().length() != 1 || !knownString(context, call.getScope().orElseThrow(), call)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(literal, "Use character overload for String search",
				charLiteral(literal.asString().charAt(0)), false));
	}

	private static Optional<Candidate> appendCandidate(InspectionContext context, MethodCallExpr call) {
		if (!"append".equals(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().size() != 1
				|| !(call.getArgument(0) instanceof BinaryExpr binary)
				|| binary.getOperator() != BinaryExpr.Operator.PLUS) {
			return Optional.empty();
		}
		String receiver = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call)
			.orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), receiver, "java.lang", BUILDERS)
				|| !knownString(context, binary.getLeft(), binary)) {
			return Optional.empty();
		}
		String rightType = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), binary.getRight(), binary)
			.orElse("");
		if ("char[]".equals(rightType)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, "Append concatenation operands separately",
				context.editor().text(call.getScope().orElseThrow()) + ".append("
						+ context.editor().text(binary.getLeft()) + ").append("
						+ context.editor().text(binary.getRight()) + ")",
				false));
	}

	private static Optional<Candidate> temporaryFromString(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty() || !call.getArguments().isEmpty()
				|| !(call.getScope().orElseThrow() instanceof ObjectCreationExpr creation)
				|| creation.getArguments().size() != 1 || !knownString(context, creation.getArgument(0), creation)) {
			return Optional.empty();
		}
		String wrapper = creation.getType().getNameAsString();
		String method = switch (wrapper) {
			case "Byte" -> "parseByte";
			case "Short" -> "parseShort";
			case "Integer" -> "parseInt";
			case "Long" -> "parseLong";
			case "Float" -> "parseFloat";
			case "Double" -> "parseDouble";
			case "Boolean" -> "parseBoolean";
			default -> null;
		};
		String valueMethod = switch (wrapper) {
			case "Byte" -> "byteValue";
			case "Short" -> "shortValue";
			case "Integer" -> "intValue";
			case "Long" -> "longValue";
			case "Float" -> "floatValue";
			case "Double" -> "doubleValue";
			case "Boolean" -> "booleanValue";
			default -> "";
		};
		if (method == null || !call.getNameAsString().equals(valueMethod) || !ExpressionToolSupport
			.knownType(context.compilationUnit(), creation.getType().asString(), "java.lang", Set.of(wrapper))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, "Parse String without a temporary wrapper",
				context.editor().text(creation.getType()) + "." + method + "("
						+ context.editor().text(creation.getArgument(0)) + ")",
				false));
	}

	private static Optional<Candidate> temporaryToString(InspectionContext context, MethodCallExpr call) {
		if (!"toString".equals(call.getNameAsString()) || !call.getArguments().isEmpty()
				|| !(call.getScope().orElse(null) instanceof ObjectCreationExpr creation)
				|| creation.getArguments().size() != 1) {
			return Optional.empty();
		}
		String wrapper = creation.getType().getNameAsString();
		String primitive = switch (wrapper) {
			case "Byte" -> "byte";
			case "Short" -> "short";
			case "Integer" -> "int";
			case "Long" -> "long";
			case "Float" -> "float";
			case "Double" -> "double";
			case "Boolean" -> "boolean";
			case "Character" -> "char";
			default -> null;
		};
		if (!Set.of("Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character").contains(wrapper)
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), creation.getType().asString(),
						"java.lang", Set.of(wrapper))
				|| NumericSupport.typeOf(context, creation.getArgument(0), creation)
					.filter(primitive::equals)
					.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, "Convert primitive without a temporary wrapper",
				context.editor().text(creation.getType()) + ".toString("
						+ context.editor().text(creation.getArgument(0)) + ")",
				false));
	}

	private static Optional<Candidate> randomCandidate(InspectionContext context, CastExpr cast) {
		Expression castValue = unwrap(cast.getExpression());
		if (!cast.getType().isPrimitiveType() || !"int".equals(cast.getType().asString())
				|| !(castValue instanceof BinaryExpr multiply)
				|| multiply.getOperator() != BinaryExpr.Operator.MULTIPLY) {
			return Optional.empty();
		}
		MethodCallExpr call = null;
		Expression bound = null;
		if (multiply.getLeft() instanceof MethodCallExpr left) {
			call = left;
			bound = multiply.getRight();
		}
		if (multiply.getRight() instanceof MethodCallExpr right) {
			call = right;
			bound = multiply.getLeft();
		}
		if (call == null || call.getScope().isEmpty() || !"nextDouble".equals(call.getNameAsString())
				|| !call.getArguments().isEmpty() || !positiveBound(bound)
				|| NumericSupport.typeOf(context, bound, cast).filter(NumericSupport::isIntegral).isEmpty()) {
			return Optional.empty();
		}
		String receiverType = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call)
			.orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), receiverType, "java.util", Set.of("Random"))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(cast, "Use Random.nextInt(bound)",
				context.editor().text(call.getScope().orElseThrow()) + ".nextInt(" + context.editor().text(bound) + ")",
				false));
	}

	private static boolean knownString(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof StringLiteralExpr) {
			return true;
		}
		return ExpressionToolSupport.visibleSimpleType(context, expression, use)
			.filter(type -> ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.lang",
					Set.of("String")))
			.isPresent();
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression instanceof EnclosedExpr enclosed) {
			currentExpression = enclosed.getInner();
		}
		return currentExpression;
	}

	private static boolean positiveBound(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().longValue() > 0;
	}

	private static boolean streamContextAccepts(ObjectCreationExpr creation, String streamType) {
		Node parent = creation.getParentNode().orElse(null);
		if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == creation) {
			return ExpressionToolSupport.simpleName(variable.getType().asString()).equals(streamType);
		}
		if (parent instanceof ReturnStmt returned && returned.getExpression().orElse(null) == creation) {
			return AstSupport.ancestor(returned, MethodDeclaration.class)
				.map(method -> ExpressionToolSupport.simpleName(method.getType().asString()))
				.filter(streamType::equals)
				.isPresent();
		}
		return false;
	}

	private static String charLiteral(char value) {
		return switch (value) {
			case '\\' -> "'\\\\'";
			case '\'' -> "'\\''";
			case LineEndingSupport.LINE_FEED_CHAR -> "'\\n'";
			case LineEndingSupport.CARRIAGE_RETURN_CHAR -> "'\\r'";
			case '\t' -> "'\\t'";
			case '\b' -> "'\\b'";
			case '\f' -> "'\\f'";
			default -> "'" + value + "'";
		};
	}

	private record Candidate(Node node, String message, String replacement, boolean needsFiles) {
	}

}

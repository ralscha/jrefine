package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
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

	private static final Set<String> COLLECTIONS = Set.of("Collection", "List", "Set", "Queue", "Deque", "ArrayList",
			"LinkedList", "HashSet", "LinkedHashSet", "TreeSet");

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
		context.compilationUnit().findAll(MethodCallExpr.class).forEach(call -> {
			methodCandidate(context, call).ifPresent(candidates::add);
			temporaryToString(context, call).ifPresent(candidates::add);
		});

		List<Candidate> nonOverlapping = candidates.stream()
			.filter(candidate -> candidates.stream()
				.noneMatch(other -> other != candidate && other.node().isAncestorOf(candidate.node())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : nonOverlapping) {
			findings.add(Finding.at(candidate.node(), candidate.message()));
			if (applyFixes) {
				context.editor().replace(candidate.node().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> methodCandidate(InspectionContext context, MethodCallExpr call) {
		if (AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Optional<Candidate> toArray = toArrayCandidate(context, call);
		if (toArray.isPresent()) {
			return toArray;
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
		if (!context.targetJava().supports(11) || !"toArray".equals(call.getNameAsString()) || call.getScope().isEmpty()
				|| call.getArguments().size() != 1 || !(call.getArgument(0) instanceof ArrayCreationExpr array)
				|| array.getInitializer().isPresent() || array.getLevels().size() != 1
				|| array.getLevels().get(0).getDimension().isEmpty()
				|| !(array.getLevels().get(0).getDimension().orElseThrow() instanceof IntegerLiteralExpr size)
				|| size.asNumber().intValue() != 0) {
			return Optional.empty();
		}
		String receiver = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
			.orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), receiver, "java.util", COLLECTIONS)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(array, "Use array constructor reference with Collection.toArray()",
				context.editor().text(array.getElementType()) + "[]::new"));
	}

	private static Optional<Candidate> characterSearchCandidate(InspectionContext context, MethodCallExpr call) {
		if (!Set.of("indexOf", "lastIndexOf").contains(call.getNameAsString()) || call.getScope().isEmpty()
				|| call.getArguments().isEmpty() || !(call.getArgument(0) instanceof StringLiteralExpr literal)
				|| literal.asString().length() != 1 || !knownString(context, call.getScope().orElseThrow(), call)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(literal, "Use character overload for String search",
				charLiteral(literal.asString().charAt(0))));
	}

	private static Optional<Candidate> appendCandidate(InspectionContext context, MethodCallExpr call) {
		if (!"append".equals(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().size() != 1
				|| !(call.getArgument(0) instanceof BinaryExpr binary)
				|| binary.getOperator() != BinaryExpr.Operator.PLUS) {
			return Optional.empty();
		}
		String receiver = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call)
			.orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), receiver, "java.lang", Set.of("StringBuilder"))
				|| !knownString(context, binary.getLeft(), binary)) {
			return Optional.empty();
		}
		String rightType = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), binary.getRight(), binary)
			.orElse("");
		if (!ExpressionToolSupport.stable(binary.getLeft()) || !ExpressionToolSupport.stable(binary.getRight())
				|| !(Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double").contains(rightType)
						|| knownString(context, binary.getRight(), binary))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, "Append concatenation operands separately",
				context.editor().text(call.getScope().orElseThrow()) + ".append("
						+ context.editor().text(binary.getLeft()) + ").append("
						+ context.editor().text(binary.getRight()) + ")"));
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
						+ context.editor().text(creation.getArgument(0)) + ")"));
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
						+ context.editor().text(creation.getArgument(0)) + ")"));
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

	private record Candidate(Node node, String message, String replacement) {
	}

}

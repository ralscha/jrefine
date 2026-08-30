package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reports locale and Unicode API choices that require a project policy decision. */
public final class ReportInternationalizationPolicyIssuesTool implements PolicyInspectionTool {

	private static final Set<String> STRING_COMPARISONS = Set.of("equals", "equalsIgnoreCase", "compareTo",
			"compareToIgnoreCase");

	private static final Set<String> STRING_RESULT_METHODS = Set.of("concat", "indent", "repeat", "replace",
			"replaceAll", "replaceFirst", "strip", "stripIndent", "stripLeading", "stripTrailing", "substring",
			"toLowerCase", "toUpperCase", "trim");

	private static final Set<String> JAVA_LANG_NUMBERS = Set.of("Byte", "Double", "Float", "Integer", "Long", "Number",
			"Short");

	private static final Set<String> ATOMIC_NUMBERS = Set.of("AtomicInteger", "AtomicLong", "DoubleAccumulator",
			"DoubleAdder", "LongAccumulator", "LongAdder");

	@Override
	public String id() {
		return "report-internationalization-policy-issues";
	}

	@Override
	public String description() {
		return "Report locale-insensitive text comparison, formatting, and tokenization APIs";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		methodCalls(context, findings);
		stringTokenizerUses(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void methodCalls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (call.getScope().isEmpty()) {
				continue;
			}
			Expression receiver = call.getScope().orElseThrow();
			String name = call.getNameAsString();
			if (STRING_COMPARISONS.contains(name) && call.getArguments().size() == 1
					&& stringExpression(context, receiver, call)) {
				findings.add(Finding.at(call, "String comparison may require a locale-aware Collator"));
				continue;
			}
			if ("trim".equals(name) && call.getArguments().isEmpty() && stringExpression(context, receiver, call)) {
				findings.add(Finding.at(call, "String.trim() is not Unicode-aware; consider strip() when appropriate"));
				continue;
			}
			if ("toString".equals(name) && call.getArguments().isEmpty()) {
				toStringCall(context, call, receiver, findings);
			}
		}
	}

	private static void toStringCall(InspectionContext context, MethodCallExpr call, Expression receiver,
			List<Finding> findings) {
		String type = receiverType(context, receiver, call).orElse("");
		if (TypeLookup.isKnownType(context.compilationUnit(), type, "java.sql", Set.of("Time"))) {
			findings.add(Finding.at(call, "Time.toString() uses a fixed format instead of locale-aware formatting"));
		}
		else if (TypeLookup.isKnownType(context.compilationUnit(), type, "java.util", Set.of("Date"))) {
			findings.add(Finding.at(call, "Date.toString() uses a fixed format instead of locale-aware formatting"));
		}
		else if (numberType(context, type)) {
			findings.add(Finding.at(call, "Number.toString() uses a fixed format instead of locale-aware formatting"));
		}
	}

	private static void stringTokenizerUses(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceType type : context.compilationUnit().findAll(ClassOrInterfaceType.class)) {
			if (TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type.asString(), Set.of("StringTokenizer"))) {
				findings.add(Finding.at(type, "StringTokenizer is not suitable for locale-aware text boundaries"));
			}
		}
	}

	private static Optional<String> receiverType(InspectionContext context, Expression expression, MethodCallExpr use) {
		Expression current = unwrap(expression);
		if (current instanceof ObjectCreationExpr creation) {
			return Optional.of(creation.getType().asString());
		}
		if (current instanceof CastExpr cast) {
			return Optional.of(cast.getType().asString());
		}
		return TypeLookup.visibleType(context.compilationUnit(), current, use);
	}

	private static boolean numberType(InspectionContext context, String type) {
		if (TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, JAVA_LANG_NUMBERS)
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.math",
						Set.of("BigDecimal", "BigInteger"))
				|| TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent.atomic",
						ATOMIC_NUMBERS)) {
			return true;
		}
		String simple = TypeLookup.simpleName(type);
		return context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(declaration -> declaration.getNameAsString().equals(simple))
			.anyMatch(declaration -> declaration.getExtendedTypes()
				.stream()
				.anyMatch(parent -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), parent.asString(),
						Set.of("Number"))));
	}

	private static boolean stringExpression(InspectionContext context, Expression expression, MethodCallExpr use) {
		Expression current = unwrap(expression);
		if (current instanceof StringLiteralExpr || current instanceof TextBlockLiteralExpr) {
			return true;
		}
		if (current instanceof ObjectCreationExpr creation) {
			return TypeLookup.isKnownJavaLangType(context.compilationUnit(), creation.getType().asString(),
					Set.of("String"));
		}
		if (current instanceof CastExpr cast) {
			return TypeLookup.isKnownJavaLangType(context.compilationUnit(), cast.getType().asString(),
					Set.of("String"));
		}
		if (current instanceof MethodCallExpr call && STRING_RESULT_METHODS.contains(call.getNameAsString())
				&& call.getScope().filter(scope -> stringExpression(context, scope, call)).isPresent()) {
			return true;
		}
		return TypeLookup.visibleType(context.compilationUnit(), current, use)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
			.isPresent();
	}

	private static Expression unwrap(Expression expression) {
		Expression current = expression;
		while (current.isEnclosedExpr()) {
			current = current.asEnclosedExpr().getInner();
		}
		return current;
	}

}

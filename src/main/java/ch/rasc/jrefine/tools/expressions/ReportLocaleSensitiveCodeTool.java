package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reports case, date, and byte/text conversions that implicitly use process defaults. */
public final class ReportLocaleSensitiveCodeTool implements InspectionTool {

	private static final Set<String> READER_WRITER_TYPES = Set.of("InputStreamReader", "OutputStreamWriter",
			"FileReader", "FileWriter");

	@Override
	public String id() {
		return "report-locale-sensitive-code";
	}

	@Override
	public String description() {
		return "Report implicit default Locale and Charset use in common JDK APIs";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		caseConversions(context, findings);
		constructors(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void caseConversions(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (Set.of("toLowerCase", "toUpperCase").contains(call.getNameAsString()) && call.getArguments().isEmpty()
					&& call.getScope().isPresent() && stringExpression(context, call.getScope().orElseThrow(), call)) {
				findings
					.add(Finding.at(call, "String case conversion uses the default Locale; pass an explicit Locale"));
			}
			if ("getBytes".equals(call.getNameAsString()) && call.getArguments().isEmpty()
					&& call.getScope().isPresent() && stringExpression(context, call.getScope().orElseThrow(), call)) {
				findings.add(Finding.at(call, "String.getBytes() uses the default Charset; pass an explicit Charset"));
			}
		}
	}

	private static void constructors(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			String type = creation.getType().asString();
			if (TypeLookup.isKnownType(context.compilationUnit(), type, "java.text", Set.of("SimpleDateFormat"))
					&& creation.getArguments().size() < 2) {
				findings.add(Finding.at(creation, "SimpleDateFormat uses the default Locale; pass an explicit Locale"));
			}
			if (TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String"))
					&& stringBytesConstructor(context, creation)) {
				findings.add(Finding.at(creation,
						"String byte decoding uses the default Charset; pass an explicit Charset"));
			}
			if (TypeLookup.isKnownType(context.compilationUnit(), type, "java.io", READER_WRITER_TYPES)
					&& defaultCharsetConstructor(creation)) {
				findings.add(Finding.at(creation,
						TypeLookup.simpleName(type) + " uses the default Charset; pass an explicit Charset"));
			}
		}
	}

	private static boolean stringBytesConstructor(InspectionContext context, ObjectCreationExpr creation) {
		if (!Set.of(1, 3).contains(creation.getArguments().size())) {
			return false;
		}
		return byteArray(context, creation.getArgument(0), creation);
	}

	private static boolean defaultCharsetConstructor(ObjectCreationExpr creation) {
		String simple = TypeLookup.simpleName(creation.getType().asString());
		if (Set.of("InputStreamReader", "OutputStreamWriter", "FileReader").contains(simple)) {
			return creation.getArguments().size() == 1;
		}
		if (!"FileWriter".equals(simple)) {
			return false;
		}
		return creation.getArguments().size() == 1
				|| creation.getArguments().size() == 2 && creation.getArgument(1).isBooleanLiteralExpr();
	}

	private static boolean byteArray(InspectionContext context, Expression expression, ObjectCreationExpr use) {
		if (expression instanceof ArrayCreationExpr creation) {
			return creation.getElementType().isPrimitiveType() && creation.getElementType()
				.asPrimitiveType()
				.getType() == com.github.javaparser.ast.type.PrimitiveType.Primitive.BYTE;
		}
		if (expression instanceof CastExpr cast) {
			return "byte[]".equals(cast.getType().asString());
		}
		return TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), expression, use)
			.filter("byte[]"::equals)
			.isPresent();
	}

	private static boolean stringExpression(InspectionContext context, Expression expression, MethodCallExpr use) {
		if (expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr) {
			return true;
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
			.isPresent();
	}

}

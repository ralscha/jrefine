package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.ImportDeclaration;
import java.util.List;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.InspectionContext;

import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.Node;

/** Shared conservative type checks for expression inspections. */
final class ExpressionToolSupport {

	private ExpressionToolSupport() {
	}

	static boolean knownType(CompilationUnit root, String spelling, String packageName, Set<String> allowed) {
		String raw = rawType(spelling);
		String simple = simpleName(raw);
		if (!allowed.contains(simple) || root.findAll(TypeDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(simple))) {
			return false;
		}
		if (raw.contains(".")) {
			return raw.equals(packageName + "." + simple);
		}
		if ("java.lang".equals(packageName)) {
			return root.getImports()
				.stream()
				.filter(imported -> !imported.isAsterisk() && imported.getName().getIdentifier().equals(simple))
				.allMatch(imported -> imported.getNameAsString().equals(packageName + "." + simple));
		}
		List<ImportDeclaration> explicit = root.getImports()
			.stream()
			.filter(imported -> !imported.isAsterisk() && imported.getName().getIdentifier().equals(simple))
			.toList();
		if (!explicit.isEmpty()) {
			return explicit.stream()
				.allMatch(imported -> imported.getNameAsString().equals(packageName + "." + simple));
		}
		return root.getImports()
			.stream()
			.anyMatch(imported -> imported.isAsterisk() && imported.getNameAsString().equals(packageName));
	}

	static Optional<String> visibleSimpleType(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof StringLiteralExpr) {
			return Optional.of("String");
		}
		if (expression instanceof ObjectCreationExpr creation) {
			return Optional.of(simpleName(creation.getType().asString()));
		}
		if (expression instanceof CastExpr cast) {
			return Optional.of(simpleName(cast.getType().asString()));
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.map(ExpressionToolSupport::simpleName);
	}

	static String simpleName(String type) {
		String raw = rawType(type);
		int dot = raw.lastIndexOf('.');
		return dot < 0 ? raw : raw.substring(dot + 1);
	}

	static String rawType(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		return currentType;
	}

	static boolean stable(Expression expression) {
		if (expression.isLiteralExpr() || expression.isNameExpr() || expression.isThisExpr()
				|| expression.isSuperExpr()) {
			return true;
		}
		return expression instanceof FieldAccessExpr field && stable(field.getScope());
	}

	static boolean knownBigDecimalExpression(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof ObjectCreationExpr creation) {
			return knownType(context.compilationUnit(), creation.getType().asString(), "java.math",
					Set.of("BigDecimal"));
		}
		if (expression instanceof CastExpr cast) {
			return knownType(context.compilationUnit(), cast.getType().asString(), "java.math", Set.of("BigDecimal"));
		}
		if (expression instanceof MethodCallExpr call && call.getScope().isPresent()
				&& "valueOf".equals(call.getNameAsString()) && knownType(context.compilationUnit(),
						call.getScope().orElseThrow().toString(), "java.math", Set.of("BigDecimal"))) {
			return true;
		}
		return visibleSimpleType(context, expression, use).filter(type -> "BigDecimal".equals(type))
			.filter(type -> knownType(context.compilationUnit(), type, "java.math", Set.of("BigDecimal")))
			.isPresent();
	}

}

package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.SemanticEvidence;
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
 * Replaces conservative null-fallback conditionals with the corresponding Objects API.
 */
public final class UseNullFallbackMethodTool implements InspectionTool {

	private static final Set<UnaryExpr.Operator> MUTATIONS = Set.of(UnaryExpr.Operator.PREFIX_INCREMENT,
			UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
			UnaryExpr.Operator.POSTFIX_DECREMENT);

	@Override
	public String id() {
		return "use-null-fallback-method";
	}

	@Override
	public int minimumJavaVersion() {
		return 9;
	}

	@Override
	public String description() {
		return "Replace null-fallback conditionals with Objects methods";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(ConditionalExpr.class)
			.stream()
			.map(conditional -> candidate(context, conditional))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String objectsType = candidates.isEmpty() ? "Objects"
				: ImportSupport.useType(context, "java.util.Objects", applyFixes);
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Null fallback can use Objects." + candidate.method()));
			if (applyFixes) {
				String fallback = context.editor().text(candidate.fallback());
				if (candidate.lazy()) {
					fallback = "() -> " + fallback;
				}
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(),
							objectsType + "." + candidate.method() + "(" + candidate.name() + ", " + fallback + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ConditionalExpr conditional) {
		if (AstSupport.hasComment(context, conditional)
				|| !(conditional.getCondition() instanceof BinaryExpr comparison)
				|| comparison.getOperator() != BinaryExpr.Operator.EQUALS
						&& comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
			return Optional.empty();
		}
		NameExpr checked = checkedName(comparison).orElse(null);
		if (checked == null || !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(),
				checked.getNameAsString(), conditional)) {
			return Optional.empty();
		}

		boolean nullWhenTrue = comparison.getOperator() == BinaryExpr.Operator.EQUALS;
		Expression nonNullBranch = nullWhenTrue ? conditional.getElseExpr() : conditional.getThenExpr();
		Expression fallback = nullWhenTrue ? conditional.getThenExpr() : conditional.getElseExpr();
		if (!(nonNullBranch instanceof NameExpr name) || !name.getNameAsString().equals(checked.getNameAsString())) {
			return Optional.empty();
		}
		if (definitelyNonNullEager(fallback)) {
			return Optional
				.of(new Candidate(conditional, checked.getNameAsString(), fallback, "requireNonNullElse", false));
		}
		if (safeLazyFallback(context, fallback, conditional)) {
			return Optional
				.of(new Candidate(conditional, checked.getNameAsString(), fallback, "requireNonNullElseGet", true));
		}
		return Optional.empty();
	}

	private static Optional<NameExpr> checkedName(BinaryExpr comparison) {
		if (comparison.getLeft().isNullLiteralExpr() && comparison.getRight() instanceof NameExpr name) {
			return Optional.of(name);
		}
		if (comparison.getRight().isNullLiteralExpr() && comparison.getLeft() instanceof NameExpr name) {
			return Optional.of(name);
		}
		return Optional.empty();
	}

	private static boolean definitelyNonNullEager(Expression fallback) {
		return fallback instanceof StringLiteralExpr || fallback instanceof TextBlockLiteralExpr
				|| fallback instanceof ClassExpr;
	}

	private static boolean safeLazyFallback(InspectionContext context, Expression fallback, ConditionalExpr use) {
		if (!(fallback instanceof ArrayCreationExpr)
				&& (!(fallback instanceof ObjectCreationExpr creation) || !safeSourceConstructor(context, creation))) {
			return false;
		}
		if (!fallback.findAll(MethodCallExpr.class).isEmpty() || !fallback.findAll(AssignExpr.class).isEmpty()
				|| fallback.findAll(UnaryExpr.class).stream().anyMatch(unary -> MUTATIONS.contains(unary.getOperator()))
				|| fallback.findAll(ObjectCreationExpr.class).stream().anyMatch(creation -> creation != fallback)) {
			return false;
		}
		return fallback.findAll(NameExpr.class)
			.stream()
			.allMatch(name -> !TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(),
					name.getNameAsString(), use)
					|| SemanticEvidence.isEffectivelyFinalLocalOrParameter(context, name.getNameAsString(), use));
	}

	private static boolean safeSourceConstructor(InspectionContext context, ObjectCreationExpr creation) {
		if (creation.getAnonymousClassBody().isPresent() || creation.getType().getScope().isPresent()) {
			return false;
		}
		String typeName = creation.getType().getNameAsString();
		List<TypeDeclaration> types = context.compilationUnit()
			.findAll(TypeDeclaration.class)
			.stream()
			.filter(type -> type.getNameAsString().equals(typeName))
			.filter(type -> sourceTypeVisible(type, creation))
			.toList();
		if (types.size() != 1) {
			return false;
		}
		List<ConstructorDeclaration> constructors = types.getFirst().getConstructors();
		if (constructors.isEmpty()) {
			return creation.getArguments().isEmpty();
		}
		List<ConstructorDeclaration> matching = constructors.stream()
			.filter(constructor -> acceptsArity(constructor, creation.getArguments().size()))
			.toList();
		return !matching.isEmpty()
				&& matching.stream().allMatch(constructor -> constructor.getThrownExceptions().isEmpty());
	}

	private static boolean acceptsArity(ConstructorDeclaration constructor, int arity) {
		if (constructor.getParameters().isEmpty()) {
			return arity == 0;
		}
		boolean varArgs = constructor.getParameter(constructor.getParameters().size() - 1).isVarArgs();
		return varArgs ? arity >= constructor.getParameters().size() - 1 : arity == constructor.getParameters().size();
	}

	private static boolean sourceTypeVisible(TypeDeclaration type, ObjectCreationExpr use) {
		if (type.getParentNode().orElse(null) instanceof CompilationUnit) {
			return true;
		}
		return type.getParentNode()
			.filter(TypeDeclaration.class::isInstance)
			.map(TypeDeclaration.class::cast)
			.filter(owner -> owner.isAncestorOf(use))
			.isPresent();
	}

	private record Candidate(ConditionalExpr expression, String name, Expression fallback, String method,
			boolean lazy) {
	}

}

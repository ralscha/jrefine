package ch.rasc.jrefine.analysis;

import com.github.javaparser.ast.ImportDeclaration;
import java.util.List;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.expr.LambdaExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/** Conservative lexical type lookup used when a rewrite requires a known JDK API. */
public final class TypeLookup {

	private TypeLookup() {
	}

	public static Optional<String> visibleType(Node root, Expression expression, Node use) {
		return visibleTypePreservingArrays(root, expression, use).map(TypeLookup::rawType);
	}

	/** Returns a visible lexical type while retaining array dimensions. */
	public static Optional<String> visibleTypePreservingArrays(Node root, Expression expression, Node use) {
		return visibleDeclaredType(root, expression, use).map(TypeLookup::erasedType);
	}

	/**
	 * Returns the visible lexical type including generic arguments and array dimensions.
	 */
	public static Optional<String> visibleDeclaredType(Node root, Expression expression, Node use) {
		if (!(expression instanceof NameExpr name)) {
			return Optional.empty();
		}
		ArrayList<Candidate> candidates = new ArrayList<>();
		for (VariableDeclarator variable : root.findAll(VariableDeclarator.class)) {
			if (!variable.getNameAsString().equals(name.getNameAsString())) {
				continue;
			}
			Optional<Node> owner = variableOwner(variable);
			boolean field = ancestor(variable, FieldDeclaration.class).isPresent();
			if (owner.isEmpty() || !owner.orElseThrow().isAncestorOf(use)) {
				continue;
			}
			if (!field && (!before(variable, use) || !variableScopeContains(variable, use))) {
				continue;
			}
			candidates
				.add(new Candidate(variable.getType().asString(), depth(owner.orElseThrow()), position(variable)));
		}
		for (Parameter parameter : root.findAll(Parameter.class)) {
			if (!parameter.getNameAsString().equals(name.getNameAsString())) {
				continue;
			}
			Optional<Node> owner = parameter.getParentNode();
			if (owner.isPresent() && owner.orElseThrow().isAncestorOf(use)) {
				String type = parameter.getType().asString() + (parameter.isVarArgs() ? "[]" : "");
				candidates.add(new Candidate(type, depth(owner.orElseThrow()), position(parameter)));
			}
		}
		return candidates.stream()
			.max(Comparator.comparingInt(Candidate::depth).thenComparingInt(Candidate::position))
			.map(Candidate::type);
	}

	/**
	 * Returns whether a name resolves conservatively to a visible local variable or
	 * parameter.
	 */
	public static boolean isVisibleLocalOrParameter(Node root, String name, Node use) {
		return isVisibleLocalOrParameter(root, name, use, false);
	}

	/**
	 * Returns whether a name resolves to a local or parameter, including captured values.
	 */
	public static boolean isVisibleLocalOrParameterIncludingCaptured(Node root, String name, Node use) {
		return isVisibleLocalOrParameter(root, name, use, true);
	}

	private static boolean isVisibleLocalOrParameter(Node root, String name, Node use, boolean allowCapture) {
		boolean local = root.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> variable.getNameAsString().equals(name))
			.filter(variable -> ancestor(variable, FieldDeclaration.class).isEmpty())
			.filter(variable -> variableOwner(variable).filter(owner -> owner.isAncestorOf(use)).isPresent())
			.filter(variable -> before(variable, use) && variableScopeContains(variable, use))
			.anyMatch(variable -> allowCapture || sameAssignmentBoundary(variable, use));
		if (local) {
			return true;
		}
		return root.findAll(Parameter.class)
			.stream()
			.filter(parameter -> parameter.getNameAsString().equals(name))
			.filter(parameter -> parameter.getParentNode().filter(owner -> owner.isAncestorOf(use)).isPresent())
			.anyMatch(parameter -> allowCapture || sameAssignmentBoundary(parameter, use));
	}

	static String simpleType(String type) {
		String currentType = type;
		currentType = rawType(currentType);
		int dot = currentType.lastIndexOf('.');
		return dot >= 0 ? currentType.substring(dot + 1) : currentType;
	}

	public static boolean isKnownJavaUtilType(CompilationUnit root, String type, Set<String> allowed) {
		String raw = rawType(type);
		String simple = simpleType(raw);
		if (!allowed.contains(simple) || declaresType(root, simple)) {
			return false;
		}
		if (raw.contains(".")) {
			return raw.startsWith("java.util.");
		}
		List<ImportDeclaration> explicit = root.getImports()
			.stream()
			.filter(imported -> !imported.isAsterisk() && imported.getName().getIdentifier().equals(simple))
			.toList();
		if (!explicit.isEmpty()) {
			return explicit.stream().allMatch(imported -> imported.getNameAsString().startsWith("java.util."));
		}
		return root.getImports()
			.stream()
			.anyMatch(imported -> imported.isAsterisk() && ("java.util".equals(imported.getNameAsString())
					|| imported.getNameAsString().startsWith("java.util.")));
	}

	public static boolean isKnownJavaLangType(CompilationUnit root, String type, Set<String> allowed) {
		String raw = rawType(type);
		String simple = simpleType(raw);
		if (!allowed.contains(simple) || declaresType(root, simple)) {
			return false;
		}
		if (raw.contains(".")) {
			return raw.equals("java.lang." + simple);
		}
		return root.getImports()
			.stream()
			.filter(imported -> !imported.isAsterisk() && imported.getName().getIdentifier().equals(simple))
			.allMatch(imported -> imported.getNameAsString().equals("java.lang." + simple));
	}

	/**
	 * Returns whether a spelling resolves lexically to one of the allowed types in a
	 * package.
	 */
	public static boolean isKnownType(CompilationUnit root, String spelling, String packageName, Set<String> allowed) {
		String raw = rawType(spelling);
		String simple = simpleName(raw);
		if (!allowed.contains(simple) || declaresType(root, simple)) {
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

	/** Returns the unqualified raw name of a type spelling. */
	public static String simpleName(String type) {
		String raw = rawType(type);
		int dot = raw.lastIndexOf('.');
		return dot >= 0 ? raw.substring(dot + 1) : raw;
	}

	private static String rawType(String type) {
		String currentType = type;
		currentType = erasedType(currentType);
		while (currentType.endsWith("[]")) {
			currentType = currentType.substring(0, currentType.length() - 2);
		}
		return currentType;
	}

	private static String erasedType(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		return currentType;
	}

	private static boolean declaresType(CompilationUnit root, String simpleName) {
		return root.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(simpleName))
				|| root.findAll(EnumDeclaration.class)
					.stream()
					.anyMatch(type -> type.getNameAsString().equals(simpleName))
				|| root.findAll(AnnotationDeclaration.class)
					.stream()
					.anyMatch(type -> type.getNameAsString().equals(simpleName))
				|| root.findAll(RecordDeclaration.class)
					.stream()
					.anyMatch(type -> type.getNameAsString().equals(simpleName));
	}

	private static Optional<Node> variableOwner(VariableDeclarator variable) {
		Optional<FieldDeclaration> field = ancestor(variable, FieldDeclaration.class);
		if (field.isPresent()) {
			return field.orElseThrow().getParentNode();
		}
		Optional<ForEachStmt> forEach = ancestor(variable, ForEachStmt.class);
		if (forEach.isPresent() && forEach.orElseThrow().getVariable().isAncestorOf(variable)) {
			return Optional.of(forEach.orElseThrow());
		}
		Optional<ForStmt> forLoop = ancestor(variable, ForStmt.class);
		if (forLoop.isPresent() && forLoop.orElseThrow()
			.getInitialization()
			.stream()
			.anyMatch(initializer -> initializer == variable.getParentNode().orElse(null)
					|| initializer.isAncestorOf(variable))) {
			return Optional.of(forLoop.orElseThrow());
		}
		return ancestor(variable, BlockStmt.class).map(Node.class::cast);
	}

	private static boolean variableScopeContains(VariableDeclarator variable, Node use) {
		Optional<ForEachStmt> forEach = ancestor(variable, ForEachStmt.class);
		if (forEach.isPresent() && forEach.orElseThrow().getVariable().isAncestorOf(variable)) {
			return forEach.orElseThrow().getBody().isAncestorOf(use);
		}
		Optional<ForStmt> forLoop = ancestor(variable, ForStmt.class);
		if (forLoop.isPresent() && forLoop.orElseThrow()
			.getInitialization()
			.stream()
			.anyMatch(initializer -> initializer == variable.getParentNode().orElse(null)
					|| initializer.isAncestorOf(variable))) {
			return forLoop.orElseThrow().isAncestorOf(use);
		}
		Optional<TryStmt> tryStatement = ancestor(variable, TryStmt.class);
		if (tryStatement.isPresent() && tryStatement.orElseThrow()
			.getResources()
			.stream()
			.anyMatch(
					resource -> resource == variable.getParentNode().orElse(null) || resource.isAncestorOf(variable))) {
			return tryStatement.orElseThrow().getTryBlock().isAncestorOf(use);
		}
		return ancestor(variable, BlockStmt.class).filter(block -> block.isAncestorOf(use)).isPresent();
	}

	private static boolean sameAssignmentBoundary(Node declaration, Node use) {
		return assignmentBoundary(declaration).orElse(null) == assignmentBoundary(use).orElse(null);
	}

	private static Optional<Node> assignmentBoundary(Node node) {
		Optional<Node> parent = Optional.of(node);
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof CallableDeclaration<?> || value instanceof LambdaExpr
					|| value instanceof InitializerDeclaration) {
				return Optional.of(value);
			}
			parent = value.getParentNode();
		}
		return Optional.empty();
	}

	private static <T extends Node> Optional<T> ancestor(Node node, Class<T> type) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (type.isInstance(value)) {
				return Optional.of(type.cast(value));
			}
			parent = value.getParentNode();
		}
		return Optional.empty();
	}

	private static boolean before(Node declaration, Node use) {
		Position left = declaration.getBegin().orElse(Position.HOME);
		Position right = use.getBegin().orElse(Position.HOME);
		return left.line < right.line || left.line == right.line && left.column < right.column;
	}

	private static int depth(Node node) {
		int result = 0;
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			result++;
			parent = parent.orElseThrow().getParentNode();
		}
		return result;
	}

	private static int position(Node node) {
		Position position = node.getBegin().orElse(Position.HOME);
		return position.line * 100_000 + position.column;
	}

	private record Candidate(String type, int depth, int position) {
	}

}

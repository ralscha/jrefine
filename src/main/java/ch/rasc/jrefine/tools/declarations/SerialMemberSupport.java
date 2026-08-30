package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.InspectionContext;

import java.util.Optional;

/** Shared lexical rules for members recognized by {@code java.io.Serial}. */
final class SerialMemberSupport {

	private SerialMemberSupport() {
	}

	static boolean validField(InspectionContext context, FieldDeclaration field) {
		OwnerKind owner = ownerKind(context, field).orElse(null);
		if (owner == null || field.getVariables().size() != 1) {
			return false;
		}
		VariableDeclarator variable = field.getVariable(0);
		if ("serialVersionUID".equals(variable.getNameAsString())) {
			return "long".equals(variable.getType().asString()) && field.isStatic() && field.isFinal();
		}
		return owner == OwnerKind.SERIALIZABLE && "serialPersistentFields".equals(variable.getNameAsString())
				&& field.isPrivate() && field.isStatic() && field.isFinal()
				&& knownIoType(context, variable.getType().asString(), "ObjectStreamField[]");
	}

	static boolean validMethod(InspectionContext context, MethodDeclaration method) {
		OwnerKind owner = ownerKind(context, method).orElse(null);
		if (owner == null || method.isStatic()) {
			return false;
		}
		return switch (method.getNameAsString()) {
			case "writeObject" -> owner == OwnerKind.SERIALIZABLE && method.isPrivate() && method.getType().isVoidType()
					&& parameter(context, method, "ObjectOutputStream");
			case "readObject" -> owner == OwnerKind.SERIALIZABLE && method.isPrivate() && method.getType().isVoidType()
					&& parameter(context, method, "ObjectInputStream");
			case "readObjectNoData" -> owner == OwnerKind.SERIALIZABLE && method.isPrivate()
					&& method.getType().isVoidType() && method.getParameters().isEmpty();
			case "writeReplace", "readResolve" ->
				method.getParameters().isEmpty() && knownJavaLangObject(context, method.getType().asString());
			default -> false;
		};
	}

	static Optional<OwnerKind> ownerKind(InspectionContext context, Node member) {
		return AstSupport.ancestor(member, ClassOrInterfaceDeclaration.class)
			.flatMap(owner -> ownerKind(context, owner));
	}

	static Optional<OwnerKind> ownerKind(InspectionContext context, ClassOrInterfaceDeclaration owner) {
		boolean externalizable = owner.getImplementedTypes()
			.stream()
			.anyMatch(type -> knownIoType(context, type.asString(), "Externalizable"));
		if (externalizable) {
			return Optional.of(OwnerKind.EXTERNALIZABLE);
		}
		boolean serializable = owner.getImplementedTypes()
			.stream()
			.anyMatch(type -> knownIoType(context, type.asString(), "Serializable"));
		return serializable ? Optional.of(OwnerKind.SERIALIZABLE) : Optional.empty();
	}

	private static boolean parameter(InspectionContext context, MethodDeclaration method, String type) {
		return method.getParameters().size() == 1
				&& knownIoType(context, method.getParameter(0).getType().asString(), type);
	}

	private static boolean knownIoType(InspectionContext context, String spelling, String expected) {
		boolean array = expected.endsWith("[]");
		String simple = array ? expected.substring(0, expected.length() - 2) : expected;
		String actual = array && spelling.endsWith("[]") ? spelling.substring(0, spelling.length() - 2) : spelling;
		if (array != spelling.endsWith("[]")) {
			return false;
		}
		if (actual.equals("java.io." + simple)) {
			return true;
		}
		if (!actual.equals(simple)) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && (imported.getNameAsString().equals("java.io." + simple)
					|| imported.isAsterisk() && "java.io".equals(imported.getNameAsString())));
	}

	private static boolean knownJavaLangObject(InspectionContext context, String spelling) {
		if ("java.lang.Object".equals(spelling)) {
			return true;
		}
		if (!"Object".equals(spelling)) {
			return false;
		}
		return context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.noneMatch(type -> "Object".equals(type.getNameAsString()))
				&& context.compilationUnit()
					.getImports()
					.stream()
					.filter(imported -> !imported.isAsterisk() && "Object".equals(imported.getName().getIdentifier()))
					.allMatch(imported -> "java.lang.Object".equals(imported.getNameAsString()));
	}

	enum OwnerKind {

		SERIALIZABLE, EXTERNALIZABLE

	}

}

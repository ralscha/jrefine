package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reports serialization declarations that the Java runtime ignores or cannot use safely.
 */
public final class ReportSerializationContractBugsTool implements InspectionTool {

	@Override
	public String id() {
		return "report-serialization-contract-bugs";
	}

	@Override
	public String description() {
		return "Report ineffective serialization hooks, fields, constructors, and proven non-serializable writes";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (type.isInterface()) {
				continue;
			}
			classContracts(context, type, findings);
		}
		objectWrites(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void classContracts(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		SerialMemberSupport.OwnerKind ownerKind = SerialMemberSupport.ownerKind(context, type).orElse(null);
		if (ownerKind == SerialMemberSupport.OwnerKind.EXTERNALIZABLE && !type.isAbstract()
				&& !publicNoArgConstructor(type)) {
			findings.add(Finding.at(type, "Externalizable class has no public no-argument constructor"));
		}

		for (FieldDeclaration field : type.getFields()) {
			for (VariableDeclarator variable : field.getVariables()) {
				String name = variable.getNameAsString();
				if ("serialVersionUID".equals(name)) {
					if (ownerKind != null && !validSerialVersionUid(field, variable)) {
						findings
							.add(Finding.at(variable, "serialVersionUID must be declared private static final long"));
					}
					else if (ownerKind == null && definitelyNonSerializable(type)) {
						findings
							.add(Finding.at(variable, "serialVersionUID has no effect in a non-serializable class"));
					}
				}
				if ("serialPersistentFields".equals(name) && ownerKind != null
						&& !validSerialPersistentFields(context, field, variable)) {
					findings.add(Finding.at(variable,
							"serialPersistentFields must be private static final ObjectStreamField[]"));
				}
				if (field.isTransient() && ownerKind == null && definitelyNonSerializable(type)) {
					findings
						.add(Finding.at(variable, "transient has no serialization effect in a non-serializable class"));
				}
			}
		}

		for (MethodDeclaration method : type.getMethods()) {
			if (SerialMemberSupport.validMethod(context, method)
					&& ("readResolve".equals(method.getNameAsString())
							|| "writeReplace".equals(method.getNameAsString()))
					&& !method.isProtected() && !(type.isFinal() && method.isPrivate())) {
				findings.add(Finding.at(method, method.getNameAsString()
						+ "() should be protected so subclasses inherit the serialization hook"));
			}
			boolean readObject = serializationMethod(context, method, "readObject", "ObjectInputStream");
			boolean writeObject = serializationMethod(context, method, "writeObject", "ObjectOutputStream");
			if (!readObject && !writeObject) {
				continue;
			}
			if (ownerKind == SerialMemberSupport.OwnerKind.SERIALIZABLE && !method.isPrivate()) {
				findings.add(Finding.at(method,
						method.getNameAsString() + "() must be private to be used by serialization"));
			}
			else if (ownerKind == SerialMemberSupport.OwnerKind.EXTERNALIZABLE) {
				findings.add(Finding.at(method,
						"Externalizable uses readExternal()/writeExternal(); this serialization hook is ignored"));
			}
			else if (ownerKind == null && definitelyNonSerializable(type)) {
				findings.add(Finding.at(method,
						method.getNameAsString() + "() is ignored because the class is not serializable"));
			}
		}

		if (ownerKind != null && nonStaticInner(type)) {
			if (type.getFields()
				.stream()
				.flatMap(field -> field.getVariables().stream())
				.noneMatch(variable -> "serialVersionUID".equals(variable.getNameAsString()))) {
				findings.add(Finding.at(type, "Serializable non-static inner class has no serialVersionUID"));
			}
			ClassOrInterfaceDeclaration outer = type.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
			if (outer != null && SerialMemberSupport.ownerKind(context, outer).isEmpty()
					&& definitelyNonSerializable(outer)) {
				findings.add(Finding.at(type,
						"Serializable non-static inner class captures a non-serializable outer instance"));
			}
		}
	}

	private static boolean publicNoArgConstructor(ClassOrInterfaceDeclaration type) {
		List<ConstructorDeclaration> constructors = type.getConstructors();
		if (constructors.isEmpty()) {
			return type.isPublic();
		}
		return constructors.stream()
			.anyMatch(constructor -> constructor.isPublic() && constructor.getParameters().isEmpty());
	}

	private static boolean validSerialVersionUid(FieldDeclaration field, VariableDeclarator variable) {
		return field.getVariables().size() == 1 && field.isPrivate() && field.isStatic() && field.isFinal()
				&& "long".equals(variable.getType().asString());
	}

	private static boolean validSerialPersistentFields(InspectionContext context, FieldDeclaration field,
			VariableDeclarator variable) {
		return field.getVariables().size() == 1 && field.isPrivate() && field.isStatic() && field.isFinal()
				&& TypeLookup.isKnownType(context.compilationUnit(), arrayComponent(variable.getType().asString()),
						"java.io", Set.of("ObjectStreamField"))
				&& variable.getType().isArrayType();
	}

	private static String arrayComponent(String type) {
		return type.endsWith("[]") ? type.substring(0, type.length() - 2) : type;
	}

	private static boolean serializationMethod(InspectionContext context, MethodDeclaration method, String name,
			String parameterType) {
		return name.equals(method.getNameAsString()) && !method.isStatic() && method.getType().isVoidType()
				&& method.getParameters().size() == 1 && TypeLookup.isKnownType(context.compilationUnit(),
						method.getParameter(0).getType().asString(), "java.io", Set.of(parameterType));
	}

	private static boolean nonStaticInner(ClassOrInterfaceDeclaration type) {
		ClassOrInterfaceDeclaration outer = type.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		return outer != null && !outer.isInterface() && !type.isStatic();
	}

	private static boolean definitelyNonSerializable(ClassOrInterfaceDeclaration type) {
		return type.getExtendedTypes().isEmpty() && type.getImplementedTypes().isEmpty();
	}

	private static void objectWrites(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"writeObject".equals(call.getNameAsString()) || call.getArguments().size() != 1
					|| call.getScope()
						.flatMap(scope -> TypeLookup.visibleType(context.compilationUnit(), scope, call))
						.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, "java.io",
								Set.of("ObjectOutputStream")))
						.isEmpty()) {
				continue;
			}
			String type = expressionType(context, call.getArgument(0), call);
			if (type == null || !definitelyNonSerializableLocalType(context, type)) {
				continue;
			}
			findings.add(Finding.at(call.getArgument(0),
					"Object of source-local non-serializable type is passed to ObjectOutputStream"));
		}
	}

	private static String expressionType(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof ObjectCreationExpr creation) {
			return creation.getType().asString();
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use).orElse(null);
	}

	private static boolean definitelyNonSerializableLocalType(InspectionContext context, String spelling) {
		String simple = TypeLookup.simpleName(spelling);
		return context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> !type.isInterface() && type.getNameAsString().equals(simple))
			.anyMatch(ReportSerializationContractBugsTool::definitelyNonSerializable);
	}

}

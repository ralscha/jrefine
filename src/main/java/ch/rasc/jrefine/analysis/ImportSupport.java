package ch.rasc.jrefine.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.Position;
import ch.rasc.jrefine.api.InspectionContext;

/** Source-preserving import insertion for tools that introduce one JDK type. */
public final class ImportSupport {

	private ImportSupport() {
	}

	/** Returns the usable simple name and queues an import when one is needed. */
	public static String useType(InspectionContext context, String qualifiedName, boolean applyFixes) {
		String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
		CompilationUnit compilationUnit = context.compilationUnit();
		if (compilationUnit.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && (imported.getNameAsString().equals(qualifiedName)
					|| imported.isAsterisk() && qualifiedName.startsWith(imported.getNameAsString() + ".")))) {
			return simpleName;
		}
		boolean conflicting = compilationUnit.getImports()
			.stream()
			.anyMatch(imported -> !imported.isAsterisk() && imported.getName().getIdentifier().equals(simpleName)
					&& !imported.getNameAsString().equals(qualifiedName));
		if (conflicting) {
			return qualifiedName;
		}
		if (!applyFixes) {
			return simpleName;
		}

		String lineEnding = LineEndingSupport.detect(context.editor().source());
		if (!compilationUnit.getImports().isEmpty()) {
			ImportDeclaration last = compilationUnit.getImports().get(compilationUnit.getImports().size() - 1);
			context.editor()
				.insertAfter(last.getRange().orElseThrow().end, lineEnding + "import " + qualifiedName + ";");
		}
		else if (compilationUnit.getPackageDeclaration().isPresent()) {
			PackageDeclaration packageDeclaration = compilationUnit.getPackageDeclaration().orElseThrow();
			context.editor()
				.insertAfter(packageDeclaration.getRange().orElseThrow().end,
						lineEnding + lineEnding + "import " + qualifiedName + ";");
		}
		else {
			context.editor().insert(Position.HOME, "import " + qualifiedName + ";" + lineEnding + lineEnding);
		}
		return simpleName;
	}

}

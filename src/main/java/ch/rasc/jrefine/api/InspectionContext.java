package ch.rasc.jrefine.api;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;
import java.util.Objects;

/** The parsed source file and target release supplied to an {@link InspectionTool}. */
public record InspectionContext(Path path, CompilationUnit compilationUnit, SourceEditor editor,
		JavaVersion targetJava) {

	public InspectionContext {
		path = Objects.requireNonNull(path, "path");
		compilationUnit = Objects.requireNonNull(compilationUnit, "compilationUnit");
		editor = Objects.requireNonNull(editor, "editor");
		targetJava = Objects.requireNonNull(targetJava, "targetJava");
	}

	public InspectionContext(Path path, CompilationUnit compilationUnit, SourceEditor editor) {
		this(path, compilationUnit, editor, JavaVersion.latest());
	}

	public InspectionContext(Path path, CompilationUnit compilationUnit, String source) {
		this(path, compilationUnit, new SourceEditor(source), JavaVersion.latest());
	}

	public InspectionContext(Path path, CompilationUnit compilationUnit, String source, JavaVersion targetJava) {
		this(path, compilationUnit, new SourceEditor(source), targetJava);
	}
}

package ch.rasc.jrefine;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import ch.rasc.jrefine.api.InspectionContext;

import java.nio.file.Path;

public final class TestSources {

	private TestSources() {
	}

	public static InspectionContext parse(String source) {
		JavaParser parser = new JavaParser(
				new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
					.setStoreTokens(true));
		ParseResult<CompilationUnit> result = parser.parse(source);
		if (!result.isSuccessful() || result.getResult().isEmpty()) {
			throw new AssertionError("Test source did not parse: " + result.getProblems());
		}
		CompilationUnit compilationUnit = result.getResult().orElseThrow();
		return new InspectionContext(Path.of("Sample.java"), compilationUnit, source);
	}

	public static String print(InspectionContext context) {
		return context.editor().render();
	}

}

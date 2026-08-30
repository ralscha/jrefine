package ch.rasc.jrefine.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceScannerTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void skipsMavenArchetypeTemplatesThatAreNotJavaCompilationUnits() throws IOException {
		Path source = temporaryDirectory.resolve("src").resolve("main").resolve("java").resolve("Sample.java");
		Path template = temporaryDirectory.resolve("src")
			.resolve("main")
			.resolve("resources")
			.resolve("archetype-resources")
			.resolve("src")
			.resolve("main")
			.resolve("java")
			.resolve("Template.java");
		Files.createDirectories(source.getParent());
		Files.createDirectories(template.getParent());
		Files.writeString(source, "class Sample {}\n");
		Files.writeString(template, "#set($package = 'example')\nclass Template {}\n");

		List<Path> files = new SourceScanner().findJavaFiles(temporaryDirectory);

		assertEquals(List.of(source.toAbsolutePath().normalize()), files);
	}

}

package ch.rasc.jrefine.engine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class SourceScanner {

	private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", ".idea", ".mvn", ".vscode",
			"archetype-resources", "build", "node_modules", "out", "target");

	List<Path> findJavaFiles(Path input) throws IOException {
		Path normalized = input.toAbsolutePath().normalize();
		if (!Files.exists(normalized)) {
			throw new IOException("Path does not exist: " + normalized);
		}
		if (Files.isRegularFile(normalized)) {
			if (!normalized.getFileName().toString().endsWith(".java")) {
				throw new IOException("Not a Java source file: " + normalized);
			}
			return List.of(normalized);
		}
		if (!Files.isDirectory(normalized)) {
			throw new IOException("Not a file or directory: " + normalized);
		}

		ArrayList<Path> files = new ArrayList<>();
		Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
				if (!directory.equals(normalized)
						&& EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
					files.add(file.toAbsolutePath().normalize());
				}
				return FileVisitResult.CONTINUE;
			}
		});
		files.sort(Comparator.naturalOrder());
		return List.copyOf(files);
	}

}

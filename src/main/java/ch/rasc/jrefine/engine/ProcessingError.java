package ch.rasc.jrefine.engine;

import java.nio.file.Path;
import java.util.Objects;

/** A source file that could not be parsed, inspected, or written. */
public record ProcessingError(Path path, String message) {

	public ProcessingError {
		path = Objects.requireNonNull(path, "path");
		message = Objects.requireNonNull(message, "message");
	}
}

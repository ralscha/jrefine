package ch.rasc.jrefine.api;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;

import java.util.Objects;

/** A source location and message reported by an inspection. */
public record Finding(int line, int column, String message) {

	public Finding {
		if (line < 1 || column < 1) {
			throw new IllegalArgumentException("line and column must be positive");
		}
		message = Objects.requireNonNull(message, "message");
	}

	public static Finding at(Node node, String message) {
		Position position = node.getBegin()
			.orElseThrow(() -> new IllegalArgumentException("The finding node has no source position"));
		return new Finding(position.line, position.column, message);
	}
}

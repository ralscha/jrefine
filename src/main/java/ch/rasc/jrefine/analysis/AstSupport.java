package ch.rasc.jrefine.analysis;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import ch.rasc.jrefine.api.InspectionContext;

import java.util.Optional;

/** Small source-aware AST helpers shared by conservative inspection implementations. */
public final class AstSupport {

	private AstSupport() {
	}

	public static boolean hasComment(InspectionContext context, Node node) {
		String source = context.editor().text(node);
		return source.contains("//") || source.contains("/*");
	}

	public static <T extends Node> Optional<T> ancestor(Node node, Class<T> type) {
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

	public static JavaToken previousSignificant(JavaToken token) {
		JavaToken currentToken = token;
		do {
			currentToken = currentToken.getPreviousToken().orElseThrow();
		}
		while (currentToken.getText().isBlank());
		return currentToken;
	}

	public static JavaToken nextSignificant(JavaToken token) {
		JavaToken currentToken = token;
		do {
			currentToken = currentToken.getNextToken().orElseThrow();
		}
		while (currentToken.getText().isBlank());
		return currentToken;
	}

	public static Range rangeFromPreviousToken(Node node, String expectedToken) {
		JavaToken first = node.getTokenRange().orElseThrow().getBegin();
		JavaToken previous = previousSignificant(first);
		if (!previous.getText().equals(expectedToken)) {
			throw new IllegalStateException("Expected '" + expectedToken + "' before " + node);
		}
		return new Range(previous.getRange().orElseThrow().begin, node.getRange().orElseThrow().end);
	}

}

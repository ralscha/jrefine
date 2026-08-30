package ch.rasc.jrefine.analysis;

/** Shared line-ending constants and source-preserving text transformations. */
public final class LineEndingSupport {

	public static final char CARRIAGE_RETURN_CHAR = 0x0D;

	public static final char LINE_FEED_CHAR = 0x0A;

	public static final String CARRIAGE_RETURN = Character.toString(CARRIAGE_RETURN_CHAR);

	public static final String LINE_FEED = Character.toString(LINE_FEED_CHAR);

	public static final String CARRIAGE_RETURN_LINE_FEED = CARRIAGE_RETURN + LINE_FEED;

	private LineEndingSupport() {
	}

	/** Returns the first line-ending style present in source, defaulting to LF. */
	public static String detect(String source) {
		if (source.contains(CARRIAGE_RETURN_LINE_FEED)) {
			return CARRIAGE_RETURN_LINE_FEED;
		}
		if (source.contains(CARRIAGE_RETURN)) {
			return CARRIAGE_RETURN;
		}
		return LINE_FEED;
	}

	/** Converts CRLF and CR line endings to LF. */
	public static String normalize(String source) {
		return source.replace(CARRIAGE_RETURN_LINE_FEED, LINE_FEED).replace(CARRIAGE_RETURN_CHAR, LINE_FEED_CHAR);
	}

	/**
	 * Normalizes generated text, then applies the source line ending and continuation
	 * indent.
	 */
	public static String indentLikeSource(String replacement, String source, String indent) {
		return normalize(replacement).replace(LINE_FEED, detect(source) + indent);
	}

}

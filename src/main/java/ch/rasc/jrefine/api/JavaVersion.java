package ch.rasc.jrefine.api;

import java.util.Arrays;

/** Supported target Java releases for source transformations. */
public enum JavaVersion {

	JAVA_8(8), JAVA_11(11), JAVA_17(17), JAVA_21(21), JAVA_25(25);

	private final int release;

	JavaVersion(int release) {
		this.release = release;
	}

	public int release() {
		return release;
	}

	public boolean supports(int minimumRelease) {
		return this.release >= minimumRelease;
	}

	public static JavaVersion latest() {
		return JAVA_25;
	}

	public static JavaVersion parse(String value) {
		String normalized = value == null ? "" : value.strip();
		return Arrays.stream(values())
			.filter(version -> Integer.toString(version.release).equals(normalized))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Target Java must be one of 8, 11, 17, 21, or 25"));
	}

}

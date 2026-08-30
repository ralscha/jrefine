package ch.rasc.jrefine.api;

import java.util.Locale;

/** User-visible importance assigned to findings from an inspection tool. */
public enum Severity {

	INFO, WARNING, ERROR;

	public boolean atLeast(Severity minimum) {
		return this.ordinal() >= minimum.ordinal();
	}

	public static Severity parse(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Severity must not be null");
		}
		try {
			return valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Unknown severity '" + value + "'; expected info, warning, or error");
		}
	}

}

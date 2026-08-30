package ch.rasc.jrefine.cli;

import ch.rasc.jrefine.api.Severity;
import ch.rasc.jrefine.api.JavaVersion;
import ch.rasc.jrefine.engine.EngineOptions;
import ch.rasc.jrefine.engine.ToolProfile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Values loaded from an optional {@code .jrefine.properties} project file. */
record JRefineConfiguration(ToolProfile profile, Set<String> suppressedTools, Map<String, Severity> severityOverrides,
		Severity minimumSeverity, int parallelism, boolean timings, JavaVersion targetJava) {

	private static final String FILE_NAME = ".jrefine.properties";

	JRefineConfiguration {
		suppressedTools = Set.copyOf(suppressedTools);
		severityOverrides = Map.copyOf(severityOverrides);
	}

	static JRefineConfiguration load(Path input, Path explicitPath) throws IOException {
		Optional<Path> path = explicitPath == null ? discover(input)
				: Optional.of(explicitPath.toAbsolutePath().normalize());
		if (path.isEmpty()) {
			return defaults();
		}
		if (!Files.isRegularFile(path.orElseThrow())) {
			throw new IOException("Configuration file does not exist: " + path.orElseThrow());
		}

		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(path.orElseThrow(), StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		validateKeys(properties);

		JRefineConfiguration defaults = defaults();
		ToolProfile profile = properties.containsKey("profile") ? ToolProfile.parse(properties.getProperty("profile"))
				: defaults.profile();
		Severity minimum = properties.containsKey("minimum-severity")
				? Severity.parse(properties.getProperty("minimum-severity")) : defaults.minimumSeverity();
		int parallelism = properties.containsKey("threads")
				? positiveInteger("threads", properties.getProperty("threads")) : defaults.parallelism();
		boolean timings = properties.containsKey("timings")
				? strictBoolean("timings", properties.getProperty("timings")) : defaults.timings();
		JavaVersion targetJava = properties.containsKey("target-java")
				? JavaVersion.parse(properties.getProperty("target-java")) : defaults.targetJava();

		LinkedHashSet<String> suppressed = new LinkedHashSet<>();
		addCommaSeparated(suppressed, properties.getProperty("suppress", ""));
		LinkedHashMap<String, Severity> severities = new LinkedHashMap<>();
		for (String key : properties.stringPropertyNames()) {
			if (key.startsWith("severity.")) {
				String toolId = key.substring("severity.".length());
				if (toolId.isBlank()) {
					throw new IllegalArgumentException("Empty tool id in configuration key '" + key + "'");
				}
				severities.put(toolId, Severity.parse(properties.getProperty(key)));
			}
		}
		return new JRefineConfiguration(profile, suppressed, severities, minimum, parallelism, timings, targetJava);
	}

	private static JRefineConfiguration defaults() {
		EngineOptions engine = EngineOptions.defaults();
		return new JRefineConfiguration(ToolProfile.HIGH_CONFIDENCE, Set.of(), Map.of(), Severity.INFO,
				engine.parallelism(), false, JavaVersion.latest());
	}

	private static Optional<Path> discover(Path input) {
		Path current = input.toAbsolutePath().normalize();
		if (!Files.isDirectory(current)) {
			current = current.getParent();
		}
		while (current != null) {
			Path candidate = current.resolve(FILE_NAME);
			if (Files.isRegularFile(candidate)) {
				return Optional.of(candidate);
			}
			current = current.getParent();
		}
		return Optional.empty();
	}

	private static void validateKeys(Properties properties) {
		for (String key : properties.stringPropertyNames()) {
			if (!Set.of("profile", "suppress", "minimum-severity", "threads", "timings", "target-java").contains(key)
					&& !key.startsWith("severity.")) {
				throw new IllegalArgumentException("Unknown configuration key '" + key + "'");
			}
		}
	}

	private static int positiveInteger(String key, String value) {
		try {
			int parsed = Integer.parseInt(value.strip());
			if (parsed < 1) {
				throw new NumberFormatException();
			}
			return parsed;
		}
		catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Configuration key '" + key + "' must be a positive integer");
		}
	}

	private static boolean strictBoolean(String key, String value) {
		if ("true".equalsIgnoreCase(value.strip())) {
			return true;
		}
		if ("false".equalsIgnoreCase(value.strip())) {
			return false;
		}
		throw new IllegalArgumentException("Configuration key '" + key + "' must be true or false");
	}

	private static void addCommaSeparated(Set<String> destination, String value) {
		Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).forEach(destination::add);
	}
}

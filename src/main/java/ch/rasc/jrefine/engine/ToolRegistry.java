package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.InspectionTool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/** Discovers inspection tools and guarantees stable, unique tool identifiers. */
public final class ToolRegistry {

	private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

	private final Map<String, InspectionTool> tools;

	public ToolRegistry(Collection<? extends InspectionTool> tools) {
		LinkedHashMap<String, InspectionTool> byId = new LinkedHashMap<>();
		tools.stream().sorted(java.util.Comparator.comparing(InspectionTool::id)).forEach(tool -> {
			validateId(tool.id());
			if (tool.minimumJavaVersion() < 8 || tool.minimumJavaVersion() > 25) {
				throw new IllegalArgumentException(
						"Invalid minimum Java version for tool '" + tool.id() + "': " + tool.minimumJavaVersion());
			}
			InspectionTool previous = byId.putIfAbsent(tool.id(), tool);
			if (previous != null) {
				throw new IllegalArgumentException("Duplicate tool id: " + tool.id());
			}
		});
		this.tools = Map.copyOf(byId);
	}

	public static ToolRegistry load() {
		List<InspectionTool> discovered = ServiceLoader.load(InspectionTool.class)
			.stream()
			.map(ServiceLoader.Provider::get)
			.toList();
		return new ToolRegistry(discovered);
	}

	public List<InspectionTool> all() {
		return tools.values().stream().sorted(java.util.Comparator.comparing(InspectionTool::id)).toList();
	}

	public List<InspectionTool> select(Collection<String> requestedIds) {
		if (requestedIds.isEmpty()) {
			return this.all();
		}

		return requestedIds.stream().distinct().map(id -> {
			InspectionTool tool = tools.get(id);
			if (tool == null) {
				throw new IllegalArgumentException("Unknown tool '" + id + "'");
			}
			return tool;
		}).toList();
	}

	/**
	 * Selects explicit tools, or the requested confidence profile when no ids were
	 * supplied.
	 */
	public List<InspectionTool> select(Collection<String> requestedIds, ToolProfile profile) {
		if (!requestedIds.isEmpty()) {
			return select(requestedIds);
		}
		return all().stream().filter(profile::includes).toList();
	}

	/** Validates and returns a registered tool, for configuration processing. */
	public InspectionTool require(String id) {
		InspectionTool tool = tools.get(id);
		if (tool == null) {
			throw new IllegalArgumentException("Unknown tool '" + id + "'");
		}
		return tool;
	}

	private static void validateId(String id) {
		if (id == null || !VALID_ID.matcher(id).matches()) {
			throw new IllegalArgumentException("Invalid tool id: " + id);
		}
	}

}

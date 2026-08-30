package ch.rasc.jrefine.cli;

import picocli.CommandLine.IVersionProvider;

/** Supplies the CLI version from the packaged JAR manifest. */
public final class JRefineVersionProvider implements IVersionProvider {

	@Override
	public String[] getVersion() {
		String version = JRefineCommand.class.getPackage().getImplementationVersion();
		if (version == null || version.isBlank()) {
			version = "development";
		}
		return new String[] { "jrefine " + version };
	}

}

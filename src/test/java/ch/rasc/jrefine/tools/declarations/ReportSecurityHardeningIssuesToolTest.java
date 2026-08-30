package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportSecurityHardeningIssuesToolTest {

	private final ReportSecurityHardeningIssuesTool tool = new ReportSecurityHardeningIssuesTool();

	@Test
	void reportsUnfilteredDeserializationInsecureXmlAndWeakCryptography() {
		ToolResult result = inspect("""
				import java.io.ObjectInputStream;
				import java.security.MessageDigest;
				import java.security.Signature;
				import javax.crypto.Cipher;
				import javax.crypto.Mac;
				import javax.xml.parsers.DocumentBuilderFactory;
				class UnsafeInputs {
				    Object deserialize(ObjectInputStream input) throws Exception {
				        return input.readObject();
				    }
				    void parse() throws Exception {
				        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				        factory.newDocumentBuilder();
				    }
				    void crypto() throws Exception {
				        MessageDigest.getInstance("SHA-1");
				        Cipher.getInstance("AES");
				        Cipher.getInstance("AES/ECB/PKCS5Padding");
				        Mac.getInstance("HmacSHA1");
				        Signature.getInstance("MD5withRSA");
				    }
				}
				""");

		assertMessages(result, "ObjectInputFilter", "complete source-visible XML hardening", "Weak message-digest",
				"Weak or underspecified cipher", "Weak MAC", "Weak signature");
		assertEquals(7, result.findings().size(), result.findings().toString());
	}

	@Test
	void acceptsFilteredDeserializationHardenedXmlAndStrongCryptography() {
		ToolResult result = inspect("""
				import java.io.ObjectInputFilter;
				import java.io.ObjectInputStream;
				import java.security.MessageDigest;
				import javax.crypto.Cipher;
				import javax.xml.XMLConstants;
				import javax.xml.parsers.DocumentBuilderFactory;
				class SafeInputs {
				    Object deserialize(ObjectInputStream input, ObjectInputFilter filter)
				            throws Exception {
				        input.setObjectInputFilter(filter);
				        return input.readObject();
				    }
				    void parse() throws Exception {
				        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
				        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
				        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
				        factory.newDocumentBuilder();
				    }
				    void crypto() throws Exception {
				        MessageDigest.getInstance("SHA-256");
				        Cipher.getInstance("AES/GCM/NoPadding");
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsArchiveEntryWritesWithoutContainmentChecks() {
		ToolResult result = inspect("""
				import java.io.InputStream;
				import java.nio.file.Files;
				import java.nio.file.Path;
				import java.util.zip.ZipEntry;
				class Archive {
				    void unsafe(InputStream input, Path root, ZipEntry entry) throws Exception {
				        Path target = root.resolve(entry.getName()).normalize();
				        Files.copy(input, target);
				        Files.copy(input, root.resolve(entry.getName()));
				    }
				    void safe(InputStream input, Path root, ZipEntry entry) throws Exception {
				        Path target = root.resolve(entry.getName()).normalize();
				        if (!target.startsWith(root.normalize())) {
				            throw new IllegalArgumentException("outside archive root");
				        }
				        Files.copy(input, target);
				    }
				}
				""");

		assertEquals(2, result.findings().size(), result.findings().toString());
		assertMessages(result, "zip slip");
	}

	private ToolResult inspect(String source) {
		ToolResult result = tool.inspect(TestSources.parse(source), true);
		assertFalse(result.changed(), "Policy reporters must not change source");
		return result;
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

}

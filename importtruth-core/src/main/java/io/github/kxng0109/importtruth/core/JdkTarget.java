package io.github.kxng0109.importtruth.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Resolves a Maven project's target Java release from its root
 * {@code pom.xml}: {@code maven.compiler.release} (or compiler-plugin
 * {@code release}) first, {@code source} second. Test-only targets
 * fall back to the main target. Root pom only: parent POMs, profiles,
 * and CLI overrides are out of scope and documented as such.
 */
public final class JdkTarget {

	private static final Map<Path, Integer> CACHE = new ConcurrentHashMap<>();

	private JdkTarget() {
	}

	/**
	 * Resolves the target release, empty when undecidable.
	 *
	 * @param projectDir project root, never null
	 * @return target major version, or empty
	 * @throws NullPointerException if {@code projectDir} is {@code null}
	 */
	public static OptionalInt of(Path projectDir) {
		Objects.requireNonNull(projectDir, "projectDir");
		Path key = projectDir.toAbsolutePath().normalize();
		Integer cached = CACHE.get(key);
		if (cached != null) {
			return cached < 0 ? OptionalInt.empty() : OptionalInt.of(cached);
		}
		int resolved = readRootPom(key.resolve("pom.xml"));
		CACHE.put(key, resolved);
		return resolved < 0 ? OptionalInt.empty() : OptionalInt.of(resolved);
	}

	private static int readRootPom(Path pom) {
		if (!Files.isRegularFile(pom)) {
			return -1;
		}
		try (InputStream in = Files.newInputStream(pom)) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			Document document = factory.newDocumentBuilder().parse(in);
			int release = property(document, "maven.compiler.release");
			if (release <= 0) {
				release = pluginConfiguration(document, "release");
			}
			if (release > 0) {
				return release;
			}
			int source = property(document, "maven.compiler.source");
			if (source <= 0) {
				source = pluginConfiguration(document, "source");
			}
			return source;
		} catch (Exception unreadable) {
			return -1;
		}
	}

	private static int property(Document document, String name) {
		NodeList holders = document.getElementsByTagNameNS("*", "properties");
		for (int i = 0; i < holders.getLength(); i++) {
			org.w3c.dom.Node holder = holders.item(i);
			NodeList children = holder.getChildNodes();
			for (int j = 0; j < children.getLength(); j++) {
				org.w3c.dom.Node child = children.item(j);
				if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
						&& name.equals(child.getLocalName() != null ? child.getLocalName() : child.getNodeName())) {
					int parsed = parseMajor(child.getTextContent());
					if (parsed > 0) {
						return parsed;
					}
				}
			}
		}
		return -1;
	}

	private static int pluginConfiguration(Document document, String name) {
		NodeList ids = document.getElementsByTagNameNS("*", "artifactId");
		for (int i = 0; i < ids.getLength(); i++) {
			org.w3c.dom.Node id = ids.item(i);
			if (!"maven-compiler-plugin".equals(id.getTextContent().trim())) {
				continue;
			}
			org.w3c.dom.Node plugin = id.getParentNode();
			if (plugin == null) {
				continue;
			}
			NodeList children = plugin.getChildNodes();
			for (int j = 0; j < children.getLength(); j++) {
				org.w3c.dom.Node child = children.item(j);
				if (child.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
					continue;
				}
				String local = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
				if (!"configuration".equals(local)) {
					continue;
				}
				NodeList settings = child.getChildNodes();
				for (int k = 0; k < settings.getLength(); k++) {
					org.w3c.dom.Node setting = settings.item(k);
					if (setting.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
						continue;
					}
					String key = setting.getLocalName() != null
							? setting.getLocalName()
							: setting.getNodeName();
					if (name.equals(key)) {
						int parsed = parseMajor(setting.getTextContent());
						if (parsed > 0) {
							return parsed;
						}
					}
				}
			}
		}
		return -1;
	}

	static int parseMajor(String text) {
		if (text == null) {
			return -1;
		}
		String trimmed = text.trim();
		if (trimmed.startsWith("1.")) {
			trimmed = trimmed.substring(2);
		}
		int end = 0;
		while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
			end++;
		}
		if (end == 0) {
			return -1;
		}
		try {
			return Integer.parseInt(trimmed.substring(0, end));
		} catch (NumberFormatException tooBig) {
			return -1;
		}
	}
}

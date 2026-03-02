package convex.dlfs;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Builds RFC 4918 PROPFIND multi-status XML responses using only the
 * standard Java NIO {@link BasicFileAttributes} interface.
 */
public class PropfindResponse {

	private static final String DAV_NS = "DAV:";

	/**
	 * Builds a 207 Multi-Status XML response for a PROPFIND request within a drive.
	 *
	 * @param driveName The drive name (used in href prefix)
	 * @param path      The request path within the drive
	 * @param attrs     The file attributes for that path
	 * @param children  Child paths to include (empty for Depth:0 or non-directories)
	 * @return XML string
	 */
	public static String build(String driveName, Path path, BasicFileAttributes attrs, List<Path> children) {
		try {
			StringWriter sw = new StringWriter();
			XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(sw);
			xml.writeStartDocument("UTF-8", "1.0");
			xml.writeStartElement("D", "multistatus", DAV_NS);
			xml.writeNamespace("D", DAV_NS);

			writeResponse(xml, driveName, path, attrs);

			for (Path child : children) {
				BasicFileAttributes childAttrs = readAttributesSafe(child);
				if (childAttrs != null) {
					writeResponse(xml, driveName, child, childAttrs);
				}
			}

			xml.writeEndElement(); // multistatus
			xml.writeEndDocument();
			xml.flush();
			return sw.toString();
		} catch (XMLStreamException e) {
			throw new RuntimeException("Failed to build PROPFIND response", e);
		}
	}

	/**
	 * Builds a 207 Multi-Status XML response listing drives as top-level collections.
	 *
	 * @param driveNames List of drive names
	 * @param depth      PROPFIND depth (0 = just root, 1 = root + children)
	 * @return XML string
	 */
	public static String buildDriveList(List<String> driveNames, int depth) {
		try {
			StringWriter sw = new StringWriter();
			XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(sw);
			xml.writeStartDocument("UTF-8", "1.0");
			xml.writeStartElement("D", "multistatus", DAV_NS);
			xml.writeNamespace("D", DAV_NS);

			// Root collection (/dlfs/)
			writeDriveEntry(xml, "/dlfs/", "/", true);

			// Child drives (only if depth >= 1)
			if (depth >= 1) {
				for (String name : driveNames) {
					writeDriveEntry(xml, "/dlfs/" + DLFSWebDAV.encodePathComponent(name) + "/", name, true);
				}
			}

			xml.writeEndElement(); // multistatus
			xml.writeEndDocument();
			xml.flush();
			return sw.toString();
		} catch (XMLStreamException e) {
			throw new RuntimeException("Failed to build drive list response", e);
		}
	}

	private static void writeResponse(XMLStreamWriter xml, String driveName, Path path, BasicFileAttributes attrs) throws XMLStreamException {
		xml.writeStartElement("D", "response", DAV_NS);

		// href — include drive name prefix, URL-encoded
		xml.writeStartElement("D", "href", DAV_NS);
		String pathStr = path.toString().replaceFirst("^/", "");
		String href;
		if (pathStr.isEmpty()) {
			href = "/dlfs/" + DLFSWebDAV.encodePathComponent(driveName) + "/";
		} else {
			href = "/dlfs/" + DLFSWebDAV.encodePathComponent(driveName) + "/" + DLFSWebDAV.encodePath(pathStr);
			if (attrs.isDirectory() && !href.endsWith("/")) href += "/";
		}
		xml.writeCharacters(href);
		xml.writeEndElement();

		// propstat
		xml.writeStartElement("D", "propstat", DAV_NS);
		xml.writeStartElement("D", "prop", DAV_NS);

		// displayname
		Path fileName = path.getFileName();
		String displayName = (fileName != null) ? fileName.toString() : driveName;
		xml.writeStartElement("D", "displayname", DAV_NS);
		xml.writeCharacters(displayName);
		xml.writeEndElement();

		// resourcetype
		xml.writeStartElement("D", "resourcetype", DAV_NS);
		if (attrs.isDirectory()) {
			xml.writeEmptyElement("D", "collection", DAV_NS);
		}
		xml.writeEndElement();

		// getcontentlength (files only)
		if (attrs.isRegularFile()) {
			xml.writeStartElement("D", "getcontentlength", DAV_NS);
			xml.writeCharacters(String.valueOf(attrs.size()));
			xml.writeEndElement();

			xml.writeStartElement("D", "getcontenttype", DAV_NS);
			xml.writeCharacters(DLFSWebDAV.guessContentType(path.toString()));
			xml.writeEndElement();
		}

		// getlastmodified
		FileTime ft = attrs.lastModifiedTime();
		if (ft != null && ft.toMillis() > 0) {
			xml.writeStartElement("D", "getlastmodified", DAV_NS);
			xml.writeCharacters(DateTimeFormatter.RFC_1123_DATE_TIME
					.format(ft.toInstant().atZone(ZoneOffset.UTC)));
			xml.writeEndElement();
		}

		xml.writeEndElement(); // prop

		xml.writeStartElement("D", "status", DAV_NS);
		xml.writeCharacters("HTTP/1.1 200 OK");
		xml.writeEndElement();

		xml.writeEndElement(); // propstat
		xml.writeEndElement(); // response
	}

	/**
	 * Writes a response entry for a drive or the drive listing root.
	 */
	private static void writeDriveEntry(XMLStreamWriter xml, String href, String displayName, boolean isCollection) throws XMLStreamException {
		xml.writeStartElement("D", "response", DAV_NS);

		xml.writeStartElement("D", "href", DAV_NS);
		xml.writeCharacters(href);
		xml.writeEndElement();

		xml.writeStartElement("D", "propstat", DAV_NS);
		xml.writeStartElement("D", "prop", DAV_NS);

		xml.writeStartElement("D", "displayname", DAV_NS);
		xml.writeCharacters(displayName);
		xml.writeEndElement();

		xml.writeStartElement("D", "resourcetype", DAV_NS);
		if (isCollection) {
			xml.writeEmptyElement("D", "collection", DAV_NS);
		}
		xml.writeEndElement();

		xml.writeEndElement(); // prop

		xml.writeStartElement("D", "status", DAV_NS);
		xml.writeCharacters("HTTP/1.1 200 OK");
		xml.writeEndElement();

		xml.writeEndElement(); // propstat
		xml.writeEndElement(); // response
	}

	private static BasicFileAttributes readAttributesSafe(Path path) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
			if (attrs.isOther()) return null;
			return attrs;
		} catch (IOException e) {
			return null;
		}
	}
}

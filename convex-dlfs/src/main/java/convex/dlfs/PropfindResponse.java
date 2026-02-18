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
	 * Builds a 207 Multi-Status XML response for a PROPFIND request.
	 *
	 * @param path     The request path
	 * @param attrs    The file attributes for that path
	 * @param children Child paths to include (empty for Depth:0 or non-directories)
	 * @return XML string
	 */
	public static String build(Path path, BasicFileAttributes attrs, List<Path> children) {
		try {
			StringWriter sw = new StringWriter();
			XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(sw);
			xml.writeStartDocument("UTF-8", "1.0");
			xml.writeStartElement("D", "multistatus", DAV_NS);
			xml.writeNamespace("D", DAV_NS);

			writeResponse(xml, path, attrs);

			for (Path child : children) {
				BasicFileAttributes childAttrs = readAttributesSafe(child);
				if (childAttrs != null) {
					writeResponse(xml, child, childAttrs);
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

	private static void writeResponse(XMLStreamWriter xml, Path path, BasicFileAttributes attrs) throws XMLStreamException {
		xml.writeStartElement("D", "response", DAV_NS);

		// href
		xml.writeStartElement("D", "href", DAV_NS);
		String href = "/dlfs/" + path.toString().replaceFirst("^/", "");
		if (attrs.isDirectory() && !href.endsWith("/")) href += "/";
		xml.writeCharacters(href);
		xml.writeEndElement();

		// propstat
		xml.writeStartElement("D", "propstat", DAV_NS);

		// prop
		xml.writeStartElement("D", "prop", DAV_NS);

		// displayname
		Path fileName = path.getFileName();
		String displayName = (fileName != null) ? fileName.toString() : "/";
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

		// status
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

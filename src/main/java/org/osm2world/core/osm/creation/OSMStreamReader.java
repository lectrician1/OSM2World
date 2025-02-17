package org.osm2world.core.osm.creation;

import static java.lang.Double.parseDouble;
import static java.lang.Math.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.lang.NotImplementedException;
import org.osm2world.core.osm.data.OSMData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import de.topobyte.osm4j.core.access.OsmIterator;
import de.topobyte.osm4j.core.dataset.InMemoryMapDataSet;
import de.topobyte.osm4j.core.dataset.MapDataSetLoader;
import de.topobyte.osm4j.pbf.seq.PbfIterator;
import de.topobyte.osm4j.xml.dynsax.OsmXmlIterator;

/**
 * {@link OSMDataReader} providing information from a stream of OSM data (such as a {@link FileInputStream}).
 * This class internally uses osm4j to read the file.
 */
public class OSMStreamReader implements OSMDataReader {

	public static enum CompressionMethod {

		None,
		GZip,
		BZip2,
		PBF;

		public static CompressionMethod fromFileName(String fileName) {
			if (fileName.endsWith(".pbf")) {
				return PBF;
			} else if (fileName.endsWith(".gz")) {
				return GZip;
			} else if (fileName.endsWith(".bz2")) {
				return BZip2;
			} else {
				return None;
			}
		}

	}

	private final InputStream inputStream;
	private final CompressionMethod compressionMethod;
	private final boolean useJosmWorkaround;

	public OSMStreamReader(InputStream inputStream, CompressionMethod compressionMethod, boolean useJosmWorkaround) {
		this.inputStream = inputStream;
		this.compressionMethod = compressionMethod;
		this.useJosmWorkaround = useJosmWorkaround;
	}

	@Override
	public OSMData getData() throws IOException {
		if (!useJosmWorkaround) {
			return getDataFromStream(inputStream, compressionMethod);
		} else {
			File tempFile = createTempFileWithJosmWorkarounds(inputStream);
			try (InputStream tempFileInputStream = new FileInputStream(tempFile)) {
				return getDataFromStream(tempFileInputStream, compressionMethod);
			}
		}
	}

	protected static OSMData getDataFromStream(InputStream inputStream, CompressionMethod compressionMethod)
			throws IOException {

		OsmIterator iterator;

		switch (compressionMethod) {
		case PBF:
			iterator = new PbfIterator(inputStream, true);
			break;
		case None:
			iterator = new OsmXmlIterator(inputStream, true);
			break;
		default:
			// TODO: handle compression with GZip or BZip2!
			throw new NotImplementedException();
		}

		InMemoryMapDataSet data = MapDataSetLoader.read(iterator, true, true, true);
		return new OSMData(data);

	}

	/**
	 * Removes some JOSM-specific attributes present in the original data, sets fake versions for unversioned elements,
	 * and merges multiple bound elements.
	 *
	 * The result is written to a temporary file in the .osm format, which is returned.
	 * The generated file should <em>not</em> be used for anything except feeding it to OSM2World.
	 */
	protected static final File createTempFileWithJosmWorkarounds(InputStream josmDataInputStream) throws IOException {

		try {

			/* parse original file */

			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(josmDataInputStream);

			/* modify DOM */

			NodeList nodes = doc.getDocumentElement().getChildNodes();
			List<Node> nodesToDelete = new ArrayList<>();
			List<Element> boundsElements = new ArrayList<>();

			for (int i = 0; i < nodes.getLength(); i++) {
				if (nodes.item(i) instanceof Element) {
					Element element = (Element) nodes.item(i);
					if ("node".equals(element.getNodeName())
							|| "way".equals(element.getNodeName())
							|| "relation".equals(element.getNodeName())) {
						if ("delete".equals(element.getAttribute("action"))) {
							nodesToDelete.add(element);
						} else if (!element.hasAttribute("version")) {
							element.setAttribute("version", "424242");
						}
					} else if ("bounds".equals(element.getNodeName())) {
						boundsElements.add(element);
					}
				}
			}

			if (boundsElements.size() > 1) {

				double minLat = Double.POSITIVE_INFINITY;
				double minLon = Double.POSITIVE_INFINITY;
				double maxLat = Double.NEGATIVE_INFINITY;
				double maxLon = Double.NEGATIVE_INFINITY;

				for (Element bounds : boundsElements) {
					minLat = min(minLat, parseDouble(bounds.getAttribute("minlat")));
					minLon = min(minLon, parseDouble(bounds.getAttribute("minlon")));
					maxLat = max(maxLat, parseDouble(bounds.getAttribute("maxlat")));
					maxLon = max(maxLon, parseDouble(bounds.getAttribute("maxlon")));
				}

				Element firstBounds = boundsElements.remove(0);
				firstBounds.setAttribute("minlat", Double.toString(minLat));
				firstBounds.setAttribute("minlon", Double.toString(minLon));
				firstBounds.setAttribute("maxlat", Double.toString(maxLat));
				firstBounds.setAttribute("maxlon", Double.toString(maxLon));

				nodesToDelete.addAll(boundsElements);

				System.out.println("WARNING: input file contains multiple <bounds>." +
						" This can lead to wrong coastlines and other issues."); //TODO proper logging

			}

			for (Node node : nodesToDelete) {
				doc.getDocumentElement().removeChild(node);
			}

			/* write result */

			File tempFile = File.createTempFile("workaround", ".osm", null);
			tempFile.deleteOnExit();

			try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {

				TransformerFactory tFactory = TransformerFactory.newInstance();
				Transformer transformer = tFactory.newTransformer();

				StreamResult result = new StreamResult(outputStream);
				DOMSource source = new DOMSource(doc);
				transformer.transform(source, result);

				return tempFile;

			}

		} catch (ParserConfigurationException | TransformerException | SAXException e) {
			throw new IOException(e);
		}

	}

}

package net.osmand.osm.util;

import info.bliki.wiki.filter.HTMLConverter;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.osmand.PlatformUtil;
import net.osmand.data.preparation.DBDialect;
import net.osmand.impl.ConsoleProgressImplementation;
import net.osmand.osm.util.GPXUtils.GPXFile;
import net.osmand.osm.util.GPXUtils.WptPt;
import net.osmand.osm.util.WikiDatabasePreparation.LatLon;

import org.apache.commons.logging.Log;
import org.apache.tools.bzip2.CBZip2InputStream;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.xwiki.component.embed.EmbeddableComponentManager;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.rendering.converter.Converter;

public class WikiVoyagePreparation {
	private static final Log log = PlatformUtil.getLog(WikiDatabasePreparation.class);
		
	private static WikivoyageImageLinksStorage imageStorage;
	private static boolean imageLinks;
	private static boolean uncompressed;
	private static String folderPath;
		
	public enum WikivoyageTemplates {
		LOCATION("geo"),
		POI("poi"),
		PART_OF("part_of"),
		BANNER("pagebanner");
		
		private String type;
		WikivoyageTemplates(String s) {
			type = s;
		}
		
		public String getType() {
			return type;
		}
	}
	
	public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException, SQLException, ComponentLookupException {
		String lang = "";
		String folder = "";
		if(args.length == 0) {
			lang = "en";
			folder = "/home/user/osmand/wikivoyage/";
			imageLinks = true;
			uncompressed = false;
		}
		if(args.length > 0) {
			lang = args[0];
		}
		if(args.length > 1){
			folder = args[1];
		}
		if(args.length > 2){
			imageLinks = args[2].equals("fetch_image_links");
			uncompressed = args[2].equals("uncompressed");
		}
		if (args.length > 3) {
			uncompressed = args[3].equals("uncompressed");
		}
		if (imageLinks) {
			System.out.println("Processing the image links for " + lang + " articles");
			imageStorage = new WikivoyageImageLinksStorage(lang, folder);
		}
		folderPath = folder;
		final String wikiPg = folder + lang + "wikivoyage-latest-pages-articles.xml.bz2";
		final String sqliteFileName = folder + lang + (uncompressed ? "_full" : "") + "_wikivoyage.sqlite";
		processWikivoyage(wikiPg, lang, sqliteFileName);
		System.out.println("Successfully generated.");
    }

	protected static void processWikivoyage(final String wikiPg, String lang, String sqliteFileName)
			throws ParserConfigurationException, SAXException, FileNotFoundException, IOException, SQLException, ComponentLookupException {
		SAXParser sx = SAXParserFactory.newInstance().newSAXParser();
		InputStream streamFile = new BufferedInputStream(new FileInputStream(wikiPg), 8192 * 4);
		InputStream stream = streamFile;
		if (stream.read() != 'B' || stream.read() != 'Z') {
			stream.close();
			throw new RuntimeException(
					"The source stream must start with the characters BZ if it is to be read as a BZip2 stream."); //$NON-NLS-1$
		} 
		CBZip2InputStream zis = new CBZip2InputStream(stream);
		Reader reader = new InputStreamReader(zis,"UTF-8");
		InputSource is = new InputSource(reader);
		is.setEncoding("UTF-8");
		final WikiOsmHandler handler = new WikiOsmHandler(sx, streamFile, lang, new File(sqliteFileName));
		sx.parse(is, handler);
		handler.finish();
	}
	
	public static class WikiOsmHandler extends DefaultHandler {
		long id = 1;
		private final SAXParser saxParser;
		private boolean page = false;
		private boolean revision = false;
		
		private StringBuilder ctext = null;
		private long cid;
		private StringBuilder title = new StringBuilder();
		private StringBuilder text = new StringBuilder();
		private StringBuilder pageId = new StringBuilder();
		
		private boolean parseText = false;

		private final InputStream progIS;
		private ConsoleProgressImplementation progress = new ConsoleProgressImplementation();
		private DBDialect dialect = DBDialect.SQLITE;
		private Connection conn;
		private PreparedStatement prep;
		private int batch = 0;
		private final static int BATCH_SIZE = 500;
		final ByteArrayOutputStream bous = new ByteArrayOutputStream(64000);
		private String lang;
		private Converter converter;
		private Connection imageConn;
		private PreparedStatement imagePrep;
			
		WikiOsmHandler(SAXParser saxParser, InputStream progIS, String lang, File sqliteFile)
				throws IOException, SQLException, ComponentLookupException{
			this.lang = lang;
			this.saxParser = saxParser;
			this.progIS = progIS;
			dialect.removeDatabase(sqliteFile);
			conn = (Connection) dialect.getDatabaseConnection(sqliteFile.getAbsolutePath(), log);
			String dataType = uncompressed ? "text" : "blob";
			conn.createStatement().execute("CREATE TABLE " + lang + "_wikivoyage(article_id long, title text, content_gz" + 
					dataType + ", is_part_of text, lat double, lon double, image_title text, gpx_gz " + dataType + ")");
			prep = conn.prepareStatement("INSERT INTO " + lang + "_wikivoyage VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
			
			progress.startTask("Parse wiki xml", progIS.available());
			EmbeddableComponentManager cm = new EmbeddableComponentManager();
			cm.initialize(WikiDatabasePreparation.class.getClassLoader());
			converter = cm.getInstance(Converter.class);
			if (!imageLinks) {
				imageConn = (Connection) dialect.getDatabaseConnection(folderPath + "imageData.sqlite", log);
				imagePrep = imageConn.prepareStatement("SELECT image_url FROM image_links WHERE image_title = ?");
			}
		}
		
		public void addBatch() throws SQLException {
			prep.addBatch();
			if(batch++ > BATCH_SIZE) {
				prep.executeBatch();
				batch = 0;
			}
		}
		
		public void finish() throws SQLException {
			prep.executeBatch();
			if (imageLinks) {
				imageStorage.finish();
			} else {
				imageConn.close();
				imagePrep.close();
			}
			if(!conn.getAutoCommit()) {
				conn.commit();
			}
			prep.close();
			conn.close();
		}

		public int getCount() {
			return (int) (id - 1);
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			String name = saxParser.isNamespaceAware() ? localName : qName;
			if (!page) {
				page = name.equals("page");
			} else {
				if (name.equals("title")) {
					title.setLength(0);
					ctext = title;
				} else if (name.equals("text")) {
					if(parseText) {
						text.setLength(0);
						ctext = text;
					}
				} else if (name.equals("revision")) {
					revision  = true;
				} else if (name.equals("id") && !revision) {
					pageId.setLength(0);
					ctext = pageId;
				}
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			if (page) {
				if (ctext != null) {
					ctext.append(ch, start, length);
				}
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			String name = saxParser.isNamespaceAware() ? localName : qName;
			
			try {
				if (page) {
					if (name.equals("page")) {
						page = false;
						parseText = false;
						progress.remaining(progIS.available());
					} else if (name.equals("title")) {
						ctext = null;
					} else if (name.equals("revision")) {
						revision = false;
					} else if (name.equals("id") && !revision) {
						ctext = null;
						cid = Long.parseLong(pageId.toString());
						parseText = true;
					} else if (name.equals("text")) {
						if (parseText) {
							Map<String, List<String>> macroBlocks = new HashMap<>();
							String text = WikiDatabasePreparation.removeMacroBlocks(ctext.toString(), macroBlocks);
							try {
								if (!macroBlocks.isEmpty()) {
									LatLon ll = getLatLonFromGeoBlock(
											macroBlocks.get(WikivoyageTemplates.LOCATION.getType()));
									if (!ll.isZero()) {
										if (id++ % 500 == 0) {
											log.debug("Article accepted " + cid + " " + title.toString() + " " + ll.getLatitude()
													+ " " + ll.getLongitude() + " free: "
													+ (Runtime.getRuntime().freeMemory() / (1024 * 1024)));
										}
										String filename = getFileName(macroBlocks.get(WikivoyageTemplates.BANNER.getType()));
										if (imageLinks) {
											if (!filename.isEmpty()) {
												imageStorage.savePageBanner(filename);
											}
											imageStorage.saveImageLinks(title.toString());
											return;
										}
										final HTMLConverter converter = new HTMLConverter(false);
										CustomWikiModel wikiModel = new CustomWikiModel("https://upload.wikimedia.org/wikipedia/commons/${image}", 
												"https://"+lang+".wikivoyage.com/wiki/${title}", folderPath, imagePrep);
										String plainStr = wikiModel.render(converter, text);
										prep.setLong(1, cid);
										prep.setString(2, title.toString());
										if (uncompressed) {
											prep.setString(3, plainStr);
										} else {
											prep.setBytes(3, stringToCompressedByteArray(bous, plainStr));
										}
										// part_of
										prep.setString(4,
												parsePartOf(macroBlocks.get(WikivoyageTemplates.PART_OF.getType())));
										
										prep.setDouble(5, ll.getLatitude());
										prep.setDouble(6, ll.getLongitude());
										// banner
										prep.setString(7, filename);
										// gpx_gz
										if (uncompressed) {
											prep.setString(8, generateGpx(macroBlocks.get(WikivoyageTemplates.POI.getType())));
										} else {
											prep.setBytes(8, stringToCompressedByteArray(new ByteArrayOutputStream(), 
													generateGpx(macroBlocks.get(WikivoyageTemplates.POI.getType()))));
										}
										addBatch();
									}
								}
							} catch (SQLException e) {
								throw new SAXException(e);
							}
						}
						ctext = null;
					}
				}
			} catch (IOException e) {
				throw new SAXException(e);
			}
		}
		
		private String generateGpx(List<String> list) {
			if (list != null && !list.isEmpty()) {
				GPXFile f = new GPXFile();
				List<WptPt> points = new ArrayList<>(); 
				for (String s : list) {
					String[] info = s.split("\\|");
					WptPt point = new WptPt();
					point.category = info[0].replaceAll("\n", "");
					for (int i = 1; i < info.length; i++) {
						String field = info[i].trim();
						String value = "";
						if (field.indexOf("=") != -1) {
							value = field.substring(field.indexOf("=") + 1, field.length()).trim();
						}
						if (!value.isEmpty()) {
							try {
								if (field.contains("name=")) {
									point.name = value;
								} else if (field.contains("url=")) {
									point.link = value;
								} else if (field.contains("lat=")) {
									point.lat = Double.valueOf(value);
								} else if (field.contains("long=")) {
									point.lon = Double.valueOf(value);
								} else if (field.contains("content=")) {
									point.desc = point.desc = point.desc == null ? value : 
										point.desc + "\n" + value;
								} else if (field.contains("email=")) {
									point.desc = point.desc == null ? "Email: " + value : 
										point.desc + "\nEmail: " + value;
								} else if (field.contains("phone=")) {
									point.desc = point.desc == null ? "Phone: " + value : 
										point.desc + "\nPhone: " + value;
								} else if (field.contains("price=")) {
									point.desc = point.desc == null ? "Price: " + value : 
										point.desc + "\nPrice: " + value;
								} else if (field.contains("hours=")) {
									point.desc = point.desc == null ? "Working hours: " + value : 
										point.desc + "\nWorking hours: " + value;
								} else if (field.contains("directions=")) {
									point.desc = point.desc == null ? "Directions: " + value : 
										point.desc + "\nDirections: " + value;
								}
							} catch (Exception e) {}
						}
					}
					if (point.hasLocation() && point.name != null && !point.name.isEmpty()) {
						point.setColor();
						points.add(point);
					}
				}
				if (!points.isEmpty()) {
					f.addPoints(points);
					return GPXUtils.asString(f);
				}
			}
			return "";
		}
		
		private byte[] stringToCompressedByteArray(ByteArrayOutputStream baos, String toCompress) {
			baos.reset();
			try {
				GZIPOutputStream gzout = new GZIPOutputStream(baos);
				gzout.write(toCompress.getBytes("UTF-8"));
				gzout.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return baos.toByteArray();
		}

		private String getFileName(List<String> list) {
			if (list != null && !list.isEmpty()) {
				String bannerInfo = list.get(0);
				String[] infoSplit = bannerInfo.split("\\|");
				for (String s : infoSplit) {
					if (s.contains(".jpg") || s.contains(".jpeg") || s.contains(".png") || s.contains(".gif")) {
						return s.trim();
					}
				}
			}
			return "";
		}
		
		private LatLon getLatLonFromGeoBlock(List<String> list) {
			if (list != null && !list.isEmpty()) {
				String location = list.get(0);
				String[] parts = location.split("\\|");
				double lat = 0d;
				double lon = 0d;
				// skip malformed location blocks
				try {
					lat = Double.valueOf(parts[1]);
					lon = Double.valueOf(parts[2]);
				} catch (Exception e) {}
				return new LatLon(lat, lon);
			}
			return new LatLon(0, 0);
		}

		private String parsePartOf(List<String> list) {
			if (list != null && !list.isEmpty()) {
				String partOf = list.get(0);
				return partOf.substring(partOf.indexOf("|") + 1, partOf.length());
			}
			return "";
		}
	}
}

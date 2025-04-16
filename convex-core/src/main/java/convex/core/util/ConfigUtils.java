package convex.core.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

import convex.core.data.ACell;

public class ConfigUtils {

	
	public static ACell readConfig(URL url) throws IOException {	
		ACell result=readConfig(url.openStream());
		return result;
	}
	
	public static ACell readConfig(URI uri) throws IOException {	
		ACell result=readConfig(uri.toURL());
		return result;
	}

	
	public static ACell readConfig(InputStream resource) throws IOException {
		String config=FileUtils.loadFileAsString(resource);
		ACell result=JSONUtils.parse(config);
		return result;
	}
	
	/**
	 * Reads a JSON5 config file
	 * @param filename File name / path. ~ is interpreted as user home directory, e.g. '~/.foo/something.json'
	 * @return Parsed CAD3 config data
	 * @throws IOException In case of IO error, e.g. FileNotFoundException
	 */
	public static ACell readConfigFile(String filename) throws IOException {	
		File f=FileUtils.getFile(filename);
		if (!f.exists()) throw new FileNotFoundException("File: "+filename);
		return readConfig(f.toURI());
	}

}

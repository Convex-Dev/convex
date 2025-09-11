package convex.core.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.lang.RT;

public class ConfigUtils {

	
	public static AMap<AString,ACell> readConfig(URL url) throws IOException {	
		AMap<AString,ACell> result=readConfig(url.openStream());
		return result;
	}
	
	public static AMap<AString,ACell> readConfig(URI uri) throws IOException {	
		AMap<AString,ACell> result=readConfig(uri.toURL());
		return result;
	}

	
	@SuppressWarnings("unchecked")
	public static AMap<AString,ACell> readConfig(InputStream resource) throws IOException {
		String config=Utils.readString(resource);
		AMap<AString,ACell> result=(AMap<AString, ACell>) JSON.parseJSON5(config);
		return RT.ensureMap(result);
	}
	
	/**
	 * Reads a JSON5 config file
	 * @param filename File name / path. ~ is interpreted as user home directory, e.g. '~/.foo/something.json'
	 * @return Parsed CAD3 config data
	 * @throws IOException In case of IO error, e.g. FileNotFoundException
	 */
	public static AMap<AString,ACell> readConfigFile(String filename) throws IOException {	
		File f=FileUtils.getFile(filename);
		if (!f.exists()) throw new FileNotFoundException("File: "+filename);
		return readConfig(f.toURI());
	}

}

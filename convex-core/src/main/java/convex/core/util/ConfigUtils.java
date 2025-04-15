package convex.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import convex.core.data.ACell;

public class ConfigUtils {

	
	public static ACell readConfig(URL url) throws IOException {	
		ACell result=readConfig(url.openStream());
		return result;
	}
	
	public static ACell readConfig(InputStream resource) throws IOException {
		String config=FileUtils.loadFileAsString(resource);
		ACell result=JSONUtils.parse(config);
		return result;
	}
}

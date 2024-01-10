package convex.cli;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import convex.core.crypto.PFXTools;
import convex.core.util.Utils;

/**
 *
 * Helpers
 *
 *	Helper functions for the CLI classes.
 *
*/
public class Helpers {

	/**
	 * Expand a path string with a '~'. The tilde is expanded to the users home path.
	 *
	 * @param path Path string to expand.
	 *
	 * @return Expanded string if a tilde is present.
	 *
	 */
	public static String expandTilde(String path) {
		if (path==null) return null;
		
		String userHome=System.getProperty("user.home");
		String separator=File.separator;
		String regex = (separator.equals("\\")) ? "\\\\" : "/";
		userHome=userHome.replaceAll(regex, "/");
		return path.replaceFirst("^~", userHome);
	}

	/**
	 * Create a path if necessary to a File object. This is used to provide a feature to add the
	 * default `.convex` folder if it does not exist.
	 *
	 * @param file File object to see if the path part of the filename exists, if not then create it.
	 *
	 */
	public static void createPath(File file) {
		File path = file.getParentFile();
		if (!path.exists()) {
			path.mkdir();
		}
	}



	/**
	 * Split a parameter list by ','. 
	 * Handles internal separators (sublists in strings)
	 * Trims resulting Strings of whitespace
	 * @param parameterValues Array of parameter values
	 * @return List of trimmed Strings
	 */
	public static List<String> splitArrayParameter(String... parameterValues) {
		List<String> result = new ArrayList<>(parameterValues.length);
		for (int index = 0; index < parameterValues.length; index ++) {
			String value = parameterValues[index];
			
			if (value.indexOf(",") > 0) {
				String[] items  = value.split(",");
				for (int itemIndex = 0; itemIndex < items.length; itemIndex ++ ) {
					String newValue = items[itemIndex].trim();
					if (newValue.length() > 0) {
						result.add(newValue);
					}
				}
			} else {
				result.add(value.trim());
			}
		}
		return result;
	}

	public static File createTempFile(String name, String ext) {
		try {
			File temp=File.createTempFile(name,ext);
			temp.deleteOnExit();
			return temp;
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
		
	}
	
	public static File createTempKeystore(String name, String password) {
		try {
			File temp=File.createTempFile(name,".pfx");
			PFXTools.createStore(temp, password);
			return temp;
		} catch (IOException|GeneralSecurityException e) {
			throw Utils.sneakyThrow(e);
		}
		
	}
}



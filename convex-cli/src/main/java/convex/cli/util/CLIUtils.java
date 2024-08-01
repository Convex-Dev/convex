package convex.cli.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import convex.cli.CLIError;

public class CLIUtils {

	public static String loadFileAsString(String fname) {
		String result = null;
		try {
			fname = fname.trim();
			if ("-".equals(fname)) {
				byte[] bs = System.in.readAllBytes();
				result = new String(bs);
			} else {
				Path path = Paths.get(fname);
				if (!path.toFile().exists()) {
					throw new CLIError("Import file does not exist: " + path);
				}
				result = Files.readString(path, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			throw new CLIError("Unable to read import file", e);
		}
		return result;
	}

	public static void writeFileAsString(Path file, String content) throws IOException {
		byte[] bs=content.getBytes(StandardCharsets.UTF_8);
		Files.write(file, bs);
	}

}

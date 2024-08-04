package convex.core.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileUtils {

	public static String loadFileAsString(String fname) throws IOException {
		String result = null;
		fname = fname.trim();
		if ("-".equals(fname)) {
			byte[] bs = System.in.readAllBytes();
			result = new String(bs);
		} else {
			Path path = Paths.get(fname);
			if (!path.toFile().exists()) {
				throw new FileNotFoundException("File does not exist: " + path);
			}
			result = Files.readString(path, StandardCharsets.UTF_8);
		}

		return result;
	}

	public static void writeFileAsString(Path file, String content) throws IOException {
		byte[] bs=content.getBytes(StandardCharsets.UTF_8);
		Files.write(file, bs);
	}

}

package convex.core.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;

/**
 * Generic file handling utilities. Used in CLI etc.
 */
public class FileUtils {

	/**
	 * Loads a file as a String. Handles `-` for STDIN
	 * @param fileName File to load
	 * @return String contents of file
	 * @throws IOException in case of IO failure
	 */
	public static String loadFileAsString(String fileName) throws IOException {
		String result = null;
		fileName = fileName.trim();
		if ("-".equals(fileName)) {
			byte[] bs = System.in.readAllBytes();
			result = new String(bs);
		} else {
			Path path = Paths.get(fileName);
			if (!path.toFile().exists()) {
				throw new FileNotFoundException("File does not exist: " + path);
			}
			result = Files.readString(path, StandardCharsets.UTF_8);
		}
		return result;
	}
	
	public static Blob loadFileAsBlob(Path file) throws IOException {
		return Blob.wrap(Files.readAllBytes(file));
	}

	public static byte[] loadFileAsBytes(Path file) throws IOException {
		return Files.readAllBytes(file);
	}
	
	public static <T extends ACell> T loadCAD3(Path file) throws IOException, BadFormatException {
		Blob b=loadFileAsBlob(file);
		return Message.create(b).getPayload();
	}
	
	public static void writeCAD3(Path file,ACell value) throws IOException {
		Message m=Message.create(null, value);
		Blob b=m.getMessageData();
		byte[] bs=b.getInternalArray();
		if (bs.length!=b.count()) {
			bs=b.getBytes();
		}
		Files.write(file,bs);
	}

	/**
	 * Write a file as a UTF-8 String to the specified path
	 * @param file File path to write
	 * @param content String content to write as UTF-8
	 * @throws IOException If an IO error occurs
	 */
	public static void writeFileAsString(Path file, String content) throws IOException {
		byte[] bs=content.getBytes(StandardCharsets.UTF_8);
		Files.write(file, bs);
	}

	/**
	 * Create a path if necessary to a File object. Interprets leading "~" as user home directory.
	 *
	 * @param file File object to see if the path part of the filename exists, if not then create it.
	 * @return target File, as an absolute path, with parent directories created recursively if needed
	 * @throws IOException In case of IO Error
	 */
	public static File ensureFilePath(File file) throws IOException {
		// Get path of parent directory, using absolute path (may be current working directory user.dir)
		File target=FileUtils.getFile(file.getPath());
		String dirPath=target.getParent();
		Files.createDirectories(Path.of(dirPath));
		return target;
	}

	/**
	 * Gets the absolute path File for a given file name. Interprets leading "~" as user home directory.
	 * @param path Path as a string
	 * @return File representing the given path
	 */
	public static File getFile(String path) {
		if (path!=null && path.startsWith("~")) {
			path=System.getProperty("user.home")+path.substring(1);
			return new File(path);
		} else {
			path=new File(path).getAbsolutePath();
			return new File(path);
		}
	}




}

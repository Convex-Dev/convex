package convex.core.lang;

import java.io.IOException;

import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.Syntax;
import convex.core.lang.reader.AntlrReader;
import convex.core.util.Utils;

/**
 * Reader implementation which reads source code and produces a tree
 * of parsed objects.
 * 
 * Supports reading in either raw form (ACell) mode or wrapping with Syntax Objects. The
 * latter is required for source references etc.
 *
 * "Talk is cheap. Show me the code." - Linus Torvalds
 */
public class Reader {
	/**
	 * Parses an expression and returns a Syntax object
	 * 
	 * @param source Source to read as a Syntax object
	 * @return Parsed form
	 */
	public static Syntax readSyntax(String source) {
		return Syntax.create(read(source));
	}

	public static ACell readResource(String path) throws IOException  {
		String source;
		source = Utils.readResourceAsString(path);
		return read(source);
	}
	
	public static ACell readResourceAsData(String path) throws IOException {
		String source = Utils.readResourceAsString(path);
		return read(source);
	}

	/**
	 * Parses an String to returns a list of raw forms
	 * 
	 * @param source Convex data to read
	 * @return List of forms read
	 */
	public static AList<ACell> readAll(String source) {
		return AntlrReader.readAll(source);
	}

	/**
	 * Parses an expression and returns a form as an Object
	 * 
	 * @param source Reader instance to get expression from
	 * @return Parsed form (may be nil)
	 */
	public static ACell read(java.io.Reader source) throws IOException {
		return AntlrReader.read(source);
	}
	
	/**
	 * Parses an expression and returns a canonical Cell representing a form
	 * 
	 * @param source Java source string to read
	 * @return Parsed form (may be nil)
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> R read(String source) {
		return (R) AntlrReader.read(source);
	}

}

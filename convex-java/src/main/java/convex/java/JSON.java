package convex.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * Simple JSON tools for working with Convex
 */
public class JSON {

    private static String WHITESPACE = "                                                             ";
    private static int WHITESPACE_LENGTH = WHITESPACE.length();

	
    /**
     * Converts a string to JSON.
     * 
     * @param jsonString A string containing a valid JSON Object representation
     * @return A map representing the JSON object
     * @throws Error In case of JSON parsing error
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String jsonString) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject result = (JSONObject) parser.parse(jsonString);
            return new JSONObject(result);
        } catch (ParseException e) {
            throw new Error("Error in JSON parsing: " + e.getMessage(), e);
        }
    }
    
    /**
     * Converts a string to a JSON Value
     *
     * @param jsonString A string containing valid JSON
     * @param <T>        A type parameter for the type of object returned.
     * @return T A java object representing the JSON provided
     * @throws Error on JSON parsing error
     */
    @SuppressWarnings({"unchecked"})
    public static <T> T parse(String jsonString) {
        JSONParser parser = new JSONParser();
        try {
            Object result = parser.parse(jsonString);
            return (T) result;
        } catch (ParseException e) {
        	throw new Error("Error in JSON parsing: " + e.getMessage(), e);
        }
    }
    
    /**
     * Converts an object to a pretty-printed JSON string representation
     * suitable
     * for human consumption
     *
     * @param value Object to represent as a JSON String
     * @return JSON string representing the value
     */
    public static String toPrettyString(Object value) {
        StringBuilder sb = new StringBuilder();
        sb = appendPrettyString(sb, value, 0);
        return sb.toString();
    }
    
    /**
     * Converts an object to an efficient JSON string representation
     *
     * @param value Object to represent as a JSON String
     * @return JSON string representing the value
     * @throws RuntimeException on failure to create JSON from value
     */
    public static String toString(Object value) {
       return JSONValue.toJSONString(value);
    }

    @SuppressWarnings("unchecked")
    private static StringBuilder appendPrettyString(StringBuilder sb, Object o, int indent) {
        if (o instanceof Map) {
            int entryIndent = indent + 2;
            sb.append("{\n");
            Map<String, Object> m = ((Map<String, Object>) o);
            int size = m.size();
            int pos = 0;
            for (Map.Entry<String, Object> me : m.entrySet()) {
                String k = me.getKey();
                sb = appendWhitespaceString(sb, entryIndent);
                sb.append(toString(k));
                sb.append(": ");
                int vIndent = entryIndent + k.length() + 4; // indent for value
                Object v = me.getValue();
                appendPrettyString(sb, v, vIndent);
                pos++;
                if (pos == size) {
                    sb.append('\n'); // final entry
                } else {
                    sb.append(",\n"); // comma for next entry
                }
            }
            sb = appendWhitespaceString(sb, indent);
            sb.append("}");
        } else if (o instanceof List) {
            List<Object> list = (List<Object>) o;
            int size = list.size();
            int entryIndent = indent + 1;
            sb.append("[");
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    sb.append(",\n");
                    sb = appendWhitespaceString(sb, entryIndent);
                }
                Object v = list.get(i);
                sb = appendPrettyString(sb, v, entryIndent);
            }
            sb.append("]");
        } else {
            sb.append(toString(o));
        }
        return sb;
    }

    /**
     * Appends a whitespace string of the specified length.
     *
     * @param sb    StringBuilder to append the whitespace characters
     * @param count Number of whitespace characters
     * @return Updated StringBuilder
     */
    private static StringBuilder appendWhitespaceString(StringBuilder sb, int count) {
        while (count > WHITESPACE_LENGTH) {
            sb.append(WHITESPACE);
            count -= WHITESPACE_LENGTH;
        }
        sb.append(WHITESPACE, 0, count);
        return sb;
    }

    /**
     * Parses a JSON input stream
     * @param <T> Return type
     * @param content Any InputStream containing JSON conent in UTF-8
     * @return Parsed JSON Object
     */
	@SuppressWarnings("unchecked")
	public static <T> T parse(InputStream content) {
		JSONParser parser=new JSONParser();
		Reader reader=new InputStreamReader(content, StandardCharsets.UTF_8);
		Object parsed;
		try {
			parsed = parser.parse(reader);
		} catch (IOException e) {
			throw new Error("IO Error reading JSON: " + e.getMessage(), e);
		}  catch (ParseException e) {
        	throw new Error("Error in JSON parsing: " + e.getMessage(), e);
        }
		return (T) parsed;
	}
}

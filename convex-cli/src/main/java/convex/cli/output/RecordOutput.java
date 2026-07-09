package convex.cli.output;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import convex.core.text.Text;
import convex.core.util.Utils;

/*
 * Output class to show results from the CLI
 *
 */
public class RecordOutput {
	// LinkedHashMap so that fields are output in insertion order
	LinkedHashMap<String,String> results=new LinkedHashMap<>();

	public RecordOutput() {
	}

	public RecordOutput(Map<Object,Object> values) {
		this();
		addFields(values);
	}


	private void addFields(Map<Object, Object> values) {
		for (Map.Entry<Object,Object> e: values.entrySet()) {
			addField(e.getKey(),e.getValue());
		}
	}

	public void addField(Object key, Object value) {
		results.put(key.toString(), Utils.toString(value));
	}

	public void writeToStream(PrintStream out) {
		PrintWriter pw=new PrintWriter(out);
		writeToStream(pw);
		pw.flush();
	}

	public void writeToStream(PrintWriter out) {
		int fsize=0;
		for (Map.Entry<String,String> e: results.entrySet()) {
			fsize=Math.max(fsize,e.getKey().length());
		}
		
		StringBuilder sb=new StringBuilder();
		for (Map.Entry<String,String> e: results.entrySet()) {
			String key=e.getKey();
			sb.append(Text.rightPad(key,fsize));
			sb.append(" : ");
			sb.append(e.getValue());
			sb.append('\n');
		}

		out.println(sb.toString());
	}
}

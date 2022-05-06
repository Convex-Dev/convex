package convex.cli.output;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import convex.core.util.Text;
import convex.core.util.Utils;

/*
 * Output class to show results from the CLI
 *
 */
public class TableOutput {
	public TableOutput(String... fields) {
		this.fieldList=List.of(fields);
	}

	protected List<String> fieldList = new ArrayList<String>();

	protected List<List<String>> rowList = new ArrayList<List<String>>();

	public void addRow(Object... values) {
		rowList.add(Stream.of(values).map(v->Utils.toString(v)).toList());
	}
	
	public void writeToStream(PrintStream out) {
		writeToStream(new PrintWriter(out));
	}

	public void writeToStream(PrintWriter out) {
		int cc=fieldList.size();
		int n=rowList.size();
		int[] sizes=new int[cc];
		
		for (int i=0; i<cc; i++) {
			sizes[i]=fieldList.get(i).length();
		}
		
		for (int j=0; j<n; j++) {
			List<String> row=rowList.get(j);
			for (int i=0; i<cc; i++) {
				sizes[i]=Math.max(sizes[i],row.get(i).length());
			}
		}
		
		StringBuilder sb=new StringBuilder();
		for (int i=0; i<cc; i++) {
			String s= Text.rightPad(fieldList.get(i), sizes[i]);
			sb.append(' ');
			sb.append(s);	
		}
		
		for (int j=0; j<n; j++) {
			sb.append('\n');
			List<String> row=rowList.get(j);
			for (int i=0; i<cc; i++) {
				String s= Text.rightPad(row.get(i),sizes[i]);
				sb.append(' ');
				sb.append(s);	
			}
		}
		out.println(sb.toString());
	}
}

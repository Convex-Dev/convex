package convex.cli;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;


import convex.core.data.ACell;
import convex.core.Result;

/*
 * Output class to show results from the CLI
 *
 */



class Output {

	protected Map<String, String> fields = new HashMap<String, String>();

	public Output() {

	}

	public void setField(String name, long value) {
		fields.put(name, String.valueOf(value));
	}

	public void setField(String name, ACell value) {
		fields.put(name, value.toString());
	}

	public void setField(String name, String value) {
		fields.put(name, value);
	}

	public void setResult(Result result) {
		ACell value = result.getValue();
		setField("Result", value);
		if (result.isError()) {
			setField("Error code", result.getErrorCode());
			if (result.getTrace() != null) {
				setField("Trace", result.getTrace());
			}
			return;
		}
		setField("Data type", value.getType().toString());
	}

	public void writeToStream(PrintStream out) {
		for (Map.Entry<String, String> entry: fields.entrySet()) {
			out.println(String.format("%s: %s", entry.getKey(), entry.getValue()));
		}
	}
}

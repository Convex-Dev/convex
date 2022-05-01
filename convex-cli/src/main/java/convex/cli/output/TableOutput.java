package convex.cli.output;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import convex.core.Result;
import convex.core.data.ACell;

/*
 * Output class to show results from the CLI
 *
 */
public class TableOutput {

	protected List<OutputField> fieldList = new ArrayList<OutputField>();

	protected List<List<OutputField>> rowList = new ArrayList<List<OutputField>>();

	public OutputField setField(String name, long value) {
		return setField(name, String.valueOf(value));
	}

	public OutputField setField(String name, ACell value) {
		return setField(name, value.toString());
	}

	public OutputField setField(String name, String value) {
		OutputField field = OutputField.create(name, value);
		fieldList.add(field);
		return field;
	}

	public void setResult(Result result) {
        ACell value = result.getValue();
		if (value != null) {
			setField("Result", value);
			setField("Data type", value.getType().toString());
		}
		if (result.isError()) {
			setField("Error code", result.getErrorCode());
			if (result.getTrace() != null) {
				setField("Trace", result.getTrace());
			}
			return;
		}
	}

	public void addRow() {
		rowList.add(fieldList);
		fieldList = new ArrayList<OutputField>();
	}

	public void writeToStream(PrintWriter out) {
		if (rowList.size() > 0) {
			List<OutputField> firstRow = rowList.get(0);
			out.println(firstRow.stream().map(OutputField::getDescription).collect(Collectors.joining(" ")));
			for ( List<OutputField>fieldList : rowList) {
				out.println(fieldList.stream().map(OutputField::getValue).collect(Collectors.joining(" ")));
			}
		}
		else {
			for (OutputField field : fieldList) {
				out.println(String.format("%s: %s", field.getDescription(), field.getValue()));
			}
		}
	}
}

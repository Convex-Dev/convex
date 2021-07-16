package convex.cli.output;


public class OutputField {

	protected String description;
	protected String value;

	private OutputField(String description, String value) {
		this.description = description;
		this.value = value;
	}

	public static OutputField create(String description, String value) {
		return new OutputField(description, value);
	}

	public String getDescription() {
		return description;
	}

	public String getValue() {
		return value;
	}
}

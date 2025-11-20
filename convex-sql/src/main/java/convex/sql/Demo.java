package convex.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;

import convex.sql.adapater.ConvexSchema;

public class Demo {

	@SuppressWarnings("null")
	public static void main(String[] args) throws Exception {
		Class.forName("org.apache.calcite.jdbc.Driver");
		
		Properties info = new Properties();
		info.setProperty("lex", "JAVA");
		Connection connection =
		    DriverManager.getConnection("jdbc:calcite:", info);
		CalciteConnection calciteConnection =
		    connection.unwrap(CalciteConnection.class);
		SchemaPlus rootSchema = calciteConnection.getRootSchema();
		Schema schema = new ConvexSchema();
		rootSchema.add("convex", schema);
		// Schema schema = new ReflectiveSchema(new HrSchema());
		
		Statement statement = calciteConnection.createStatement();
		// ResultSet resultSet = statement.executeQuery("select * from convex.table1");
		
		//System.out.println(resultSet);
		System.out.println("Done");
	}
}

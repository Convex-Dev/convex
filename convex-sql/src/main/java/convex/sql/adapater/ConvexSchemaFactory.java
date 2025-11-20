package convex.sql.adapater;

import java.util.Map;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;

public class ConvexSchemaFactory implements SchemaFactory  {

	public static final ConvexSchemaFactory INSTANCE = new ConvexSchemaFactory();
	
	private ConvexSchemaFactory() {
		
	}
	  
	@Override
	public Schema create(SchemaPlus parentSchema, String name, Map<String, Object> operand) {
		
		
		return new ConvexSchema();
	}

	

}

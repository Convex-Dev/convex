package convex.sql.adapater;

import java.util.HashMap;
import java.util.Map;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

public class ConvexSchema extends AbstractSchema {

	  private Map<String, Table> tableMap;

	  @Override protected Map<String, Table> getTableMap() {
		  if (tableMap == null) {
		      tableMap = createTableMap();
		    }
		    return tableMap;
	  }

	  private Map<String, Table> createTableMap() {
		HashMap<String,Table> m=new HashMap<>();
		m.put("table1", new ConvexTable());
		return m;
	  }
}

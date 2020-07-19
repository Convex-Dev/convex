package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertSame;

import convex.core.lang.impl.RecordFormat;

public class RecordTest {

	public static void doRecordTests(ARecord r) {
		
		RecordFormat format=r.getFormat();

		AVector<Keyword> keys=format.getKeys();
		int n=(int) keys.count();
		
		Object[] vals=new Object[n];
		for (int i=0; i<n; i++) {
			Keyword k=keys.get(i);
			Object v=r.get(k);
			vals[i]=v;
			
			// TODO: consider this invariant?
			// assertSame(r,r.assoc(k, v));
		}
		
		assertSame(r,r.updateAll(r.getAll()));
		
		ObjectsTest.doCellTests(r);
	}
}

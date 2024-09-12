package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.etch.EtchStore;

@RunWith(Parameterized.class)
public class ParamTestRefs {
	private AStore store;

	public ParamTestRefs(String label,AStore store) {
		this.store = store;
	}

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		try {
			return Arrays
					.asList(new Object[][] { 
						    { "Memory Store", new MemoryStore() }, 
							{ "Temp Etch Store", EtchStore.createTemp() } });
		} catch (IOException e) {
			throw Utils.sneakyThrow(e); 
		}
	}
	
	@Test
	public void testStoreUsage() throws IOException {
		AStore temp=Stores.current();

		try {
			Stores.setCurrent(store);
			
			{ // single embedded value
				CVMLong n=CVMLong.create(1567565765677L);
				Ref<CVMLong> r=Ref.get(n);
				assertTrue(r.isEmbedded());
				Ref<CVMLong> r2=r.persist();
				assertTrue(r.isEmbedded());
				assertSame(n,r2.getValue());
			}
			
			
			{ // structure with embedded value
				AVector<CVMLong> v=Vectors.of(6759578996496L);
				Ref<AVector<CVMLong>> r=v.getRef();
				assertEquals(Ref.UNKNOWN,r.getStatus());
				Ref<AVector<CVMLong>> r2=r.persist();
				assertEquals(Ref.PERSISTED,r2.getStatus());
				assertEquals(v.getRef(0),r2.getValue().getRef(0));
			}
			
			{ // map with embedded structure
				AMap<CVMLong,AVector<CVMLong>> m=Maps.of(156746748L,Vectors.of(8797987L));
				Ref<AMap<CVMLong,AVector<CVMLong>>> r=m.getRef();
				assertEquals(Ref.UNKNOWN,r.getStatus());
				
				Ref<AMap<CVMLong,AVector<CVMLong>>> r2=r.persist();
				
				assertEquals(Ref.PERSISTED,r2.getStatus());
				MapEntry<CVMLong, AVector<CVMLong>> me2=r2.getValue().entryAt(0);
				assertTrue(me2.getRef(0).isEmbedded());
				assertEquals(Ref.PERSISTED,me2.getRef(1).getStatus());
			}		
			
		} finally {
			Stores.setCurrent(temp);
		}
	}


}

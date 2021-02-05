package convex.actors;
 
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;

public class TorusTest {
	static Context<Address> CONTEXT = deployTorus();
	static Address TORUS = CONTEXT.getResult();
	 

	private static Context<Address> deployTorus() {
		Context<?> INITIAL=TestState.INITIAL_CONTEXT.fork();
		try {
			return INITIAL.deployActor(Reader.readResource("actors/torus.con"));
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	@Test public void testSimpleDeploys() {
		assertNotNull(TORUS);
	}
	
}

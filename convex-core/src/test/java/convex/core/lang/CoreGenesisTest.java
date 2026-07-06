package convex.core.lang;

import java.io.IOException;

import convex.core.init.BaseTest;

/**
 * Runs the {@link CoreTest} behavioural suite against the standard genesis state.
 */
public class CoreGenesisTest extends CoreTest {

	public CoreGenesisTest() throws IOException {
		super(BaseTest.STATE);
	}
}

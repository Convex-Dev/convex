package convex.core.lang;

import static convex.core.lang.TestState.*;
import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;

public class AliasTest {
	@Test public void testInitialAlias() {
		assertEquals(Maps.of(null,Core.CORE_ADDRESS),eval("*aliases*"));
	}
	
	@Test public void testWipeAlias() {
		Context<?> ctx=step("(def *aliases* {})");
		assertUndeclaredError(step(ctx,"count"));
	}
}

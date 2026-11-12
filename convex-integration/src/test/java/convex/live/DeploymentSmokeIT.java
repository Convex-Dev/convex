package convex.live;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.lang.RT;
import convex.java.ConvexHTTP;
import convex.peer.API;

/** Explicit, read-only checks of a deployment chosen by the operator. */
@EnabledIfSystemProperty(named="convex.smoke.url",matches="https?://.+")
@Timeout(30)
public class DeploymentSmokeIT {
	@Test
	void query() throws Exception {
		try (var client=ConvexHTTP.connect(URI.create(System.getProperty("convex.smoke.url")))) {
			var result=client.query("#0").get(10,TimeUnit.SECONDS);
			assertFalse(result.isError(),result::toString);
			assertEquals(Address.ZERO,result.getValue());
		}
	}

	@Test
	void status() throws Exception {
		try (var client=ConvexHTTP.connect(URI.create(System.getProperty("convex.smoke.url")))) {
			var result=client.requestStatus().get(10,TimeUnit.SECONDS);
			assertFalse(result.isError(),result::toString);
			var status=API.ensureStatusMap(result.getValue());
			assertNotNull(status);
			assertNotNull(RT.ensureHash(status.get(Keywords.GENESIS)));
		}
	}
}

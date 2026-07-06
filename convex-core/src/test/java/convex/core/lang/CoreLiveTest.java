package convex.core.lang;

import java.io.IOException;

import org.junit.jupiter.api.condition.EnabledIf;

import convex.core.cvm.Migrations;
import convex.core.init.BaseTest;

/**
 * Runs the {@link CoreTest} behavioural suite against the LIVE state — the
 * protocol version the live network is currently running
 * ({@link Migrations#LIVE_VERSION}). This is the release gate that protects live
 * servers: until the live network has applied the latest upgrade, a release must
 * not change the semantics live peers are still executing.
 *
 * <p>Enabled only while LIVE is an intermediate protocol version: at genesis this
 * suite would duplicate {@link CoreGenesisTest} (permanent from-inception
 * coverage), and at {@link Migrations#MAX_VERSION} it would duplicate
 * {@link CoreUpgradedTest} (latest target). Bump {@code Migrations.LIVE_VERSION}
 * when the live network upgrades; this suite activates or retires itself
 * accordingly. See UPGRADE.md, "Default test state policy".</p>
 */
@EnabledIf("liveVersionIsIntermediate")
public class CoreLiveTest extends CoreTest {

	public CoreLiveTest() throws IOException {
		super(BaseTest.LIVE);
	}

	@SuppressWarnings("unused") // referenced by @EnabledIf
	private static boolean liveVersionIsIntermediate() {
		return (Migrations.LIVE_VERSION > 0) && (Migrations.LIVE_VERSION < Migrations.MAX_VERSION);
	}
}

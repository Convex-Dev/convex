package convex.core.lang;

import java.io.IOException;

import convex.core.cvm.Migrations;
import convex.core.init.BaseTest;

/**
 * Runs the entire {@link CoreTest} behavioural suite against the fully-upgraded
 * state (all network migrations applied) instead of genesis.
 *
 * <p>This is the behavioural non-interference check for network upgrades: a
 * migration must not change any behaviour outside its intended fixes. It runs the
 * exact same suite as {@link CoreGenesisTest} but against the upgraded state, so a
 * migration is the <em>only</em> difference between the two. The behaviours that
 * migrations <em>do</em> change (e.g. the 5+ arg arities of {@code update} /
 * {@code update-in}) are not exercised by CoreTest — which is precisely why those
 * bugs survived — so the whole suite is expected to pass identically here. A
 * failure is an unintended migration side effect. See UPGRADE.md and
 * {@code MigrationFixesTest} (which gates the intended diffs).</p>
 */
public class CoreUpgradedTest extends CoreTest {

	public CoreUpgradedTest() throws IOException {
		super(Migrations.applyAll(BaseTest.STATE));
	}
}

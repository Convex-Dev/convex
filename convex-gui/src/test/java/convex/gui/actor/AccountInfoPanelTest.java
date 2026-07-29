package convex.gui.actor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.init.Init;

public class AccountInfoPanelTest {

	@Test
	public void testKeyedAccountShowsDID() {
		AccountStatus account=AccountStatus.create(1000L,Init.DEFAULT_GENESIS_KEY);

		String info=AccountInfoPanel.getInfoText(Address.create(13),account);

		assertTrue(info.contains("Account Key:    "+Init.DEFAULT_GENESIS_KEY));
		assertTrue(info.contains("Key DID:        "+DID.forKey(Init.DEFAULT_GENESIS_KEY)));
	}

	@Test
	public void testActorDoesNotShowKeyDID() {
		String info=AccountInfoPanel.getInfoText(Address.create(14),AccountStatus.createActor());

		assertTrue(info.contains("Account Key:    null"));
		assertFalse(info.contains("Key DID:"));
	}
}

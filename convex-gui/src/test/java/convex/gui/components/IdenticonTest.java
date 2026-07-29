package convex.gui.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountKey;
import convex.core.data.Hash;

public class IdenticonTest {

	@Test
	public void testCopyActionsFollowCurrentKey() {
		Identicon identicon=new Identicon(null);
		assertFalse(identicon.copyPublicKeyItem.isEnabled());
		assertFalse(identicon.copyDIDKeyItem.isVisible());

		identicon.setKey(AccountKey.ZERO);
		assertTrue(identicon.copyPublicKeyItem.isEnabled());
		assertTrue(identicon.copyDIDKeyItem.isVisible());

		identicon.setKey(Hash.NULL_HASH);
		assertTrue(identicon.copyPublicKeyItem.isEnabled());
		assertFalse(identicon.copyDIDKeyItem.isVisible());

		identicon.setKey(null);
		assertFalse(identicon.copyPublicKeyItem.isEnabled());
		assertFalse(identicon.copyDIDKeyItem.isVisible());
	}
}

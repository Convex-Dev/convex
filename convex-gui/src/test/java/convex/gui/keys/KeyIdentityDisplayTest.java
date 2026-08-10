package convex.gui.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.data.AccountKey;
import convex.core.data.Blob;

public class KeyIdentityDisplayTest {

	private static final Blob SEED=Blob.fromHex("00".repeat(32));
	private static final AKeyPair KEY_PAIR=AKeyPair.create(SEED.getBytes());

	@Test
	public void testGeneratedKeyShowsDID() {
		KeyGenPanel panel=new KeyGenPanel(null);
		panel.privateKeyArea.setText(SEED.toHexString());

		panel.generatePublicKey();

		AccountKey accountKey=KEY_PAIR.getAccountKey();
		assertEquals("0x"+accountKey.toChecksumHex(),panel.publicKeyArea.getText());
		assertEquals(DID.forKey(accountKey).toString(),panel.didKeyArea.getText());
		assertTrue(panel.addWalletButton.isEnabled());
	}

	@Test
	public void testInvalidSeedClearsPublicIdentity() {
		KeyGenPanel panel=new KeyGenPanel(null);
		panel.privateKeyArea.setText(SEED.toHexString());
		panel.generatePublicKey();

		panel.privateKeyArea.setText("not a private seed");
		panel.generatePublicKey();

		assertEquals("<enter valid private key>",panel.publicKeyArea.getText());
		assertEquals("<public key not ready>",panel.didKeyArea.getText());
		assertFalse(panel.addWalletButton.isEnabled());
	}

	@Test
	public void testKeyringInfoShowsDID() {
		HotWalletEntry walletEntry=HotWalletEntry.create(KEY_PAIR,"Unit test");

		String info=WalletComponent.getInfoString(walletEntry);

		assertTrue(info.contains("Public Key: "+KEY_PAIR.getAccountKey()));
		assertTrue(info.contains("Key DID:    "+DID.forKey(KEY_PAIR.getAccountKey())));
		assertTrue(info.contains("Source:     Unit test"));
	}
}

package convex.gui.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.api.ConvexLocal;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.crypto.wallet.AWalletEntry;
import convex.core.crypto.wallet.HotWalletEntry;
import convex.core.crypto.wallet.KeystoreWalletEntry;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.data.Keyword;
import convex.core.init.Init;
import convex.core.store.MemoryStore;
import convex.gui.keys.KeyAccess.Signer;
import convex.peer.API;
import convex.peer.Server;

/**
 * Tests for the key unlock policy: using a key never changes the key ring.
 * These need no display, since the password prompt is supplied.
 */
public class KeyAccessTest {

	/** Password prompt answering with a fixed password (or declining if null), counting calls */
	private static Supplier<char[]> answer(String password, AtomicInteger asked) {
		return ()->{
			asked.incrementAndGet();
			return (password==null)?null:password.toCharArray();
		};
	}

	private static HotWalletEntry lockedHotEntry(AKeyPair kp, String password) {
		HotWalletEntry we=HotWalletEntry.create(kp, "test");
		we.lock(password.toCharArray());
		return we;
	}

	@Test
	public void testLockedHotEntry() {
		AKeyPair kp=AKeyPair.generate();
		HotWalletEntry we=lockedHotEntry(kp, "secret");
		AtomicInteger asked=new AtomicInteger();

		// Right password: the key pair, with the entry still locked
		assertSame(kp, KeyAccess.unlockForUse(we, answer("secret", asked)));
		assertTrue(we.isLocked());

		// Wrong password, or declining: nothing, and still locked
		assertNull(KeyAccess.unlockForUse(we, answer("wrong", asked)));
		assertTrue(we.isLocked());
		assertNull(KeyAccess.unlockForUse(we, answer(null, asked)));
		assertTrue(we.isLocked());
		assertEquals(3, asked.get());

		// Can still be unlocked afterwards
		assertTrue(we.tryUnlock("secret".toCharArray()));
	}

	@Test
	public void testUnlockedEntryIsLeftUnlocked() {
		AKeyPair kp=AKeyPair.generate();
		HotWalletEntry we=HotWalletEntry.create(kp, "test");
		AtomicInteger asked=new AtomicInteger();

		assertSame(kp, KeyAccess.unlockForUse(we, answer("unused", asked)));
		assertFalse(we.isLocked());
		assertEquals(0, asked.get());

		assertNull(KeyAccess.unlockForUse(null, answer("unused", asked)));
		assertEquals(0, asked.get());
	}

	@Test
	public void testLockedKeystoreEntry(@TempDir Path dir) throws Exception {
		AKeyPair kp=AKeyPair.generate();
		File file=dir.resolve("test.pfx").toFile();
		KeyStore ks=PFXTools.createStore(file, null);
		PFXTools.setKeyPair(ks, kp, "secret".toCharArray());
		AWalletEntry we=KeystoreWalletEntry.create(ks, kp.getAccountKey().toHexString(), "test");
		assertTrue(we.isLocked());
		AtomicInteger asked=new AtomicInteger();

		AKeyPair got=KeyAccess.unlockForUse(we, answer("secret", asked));
		assertNotNull(got);
		assertEquals(kp.getAccountKey(), got.getAccountKey());
		assertTrue(we.isLocked());

		assertNull(KeyAccess.unlockForUse(we, answer("wrong", asked)));
		assertTrue(we.isLocked());
	}

	/** Launches a peer on a fresh network of its own, in memory, on a random port */
	private static Server launchPeer(AKeyPair kp, MemoryStore store) throws Exception {
		State genesis=Migrations.applyAll(Init.createState(List.of(kp.getAccountKey())));
		Map<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, kp);
		config.put(Keywords.STATE, genesis);
		config.put(Keywords.STORE, store);
		config.put(Keywords.PORT, 0);
		config.put(Keywords.AUTO_MANAGE, false);
		config.put(Keywords.OUTGOING_CONNECTIONS, 0);
		return API.launchPeer(config);
	}

	@Test
	public void testSignerFor() throws Exception {
		AKeyPair kp=AKeyPair.generate();
		try (MemoryStore store=new MemoryStore(); Server server=launchPeer(kp, store);
			 ConvexLocal convex=ConvexLocal.connect(server, server.getPeerController(), null)) {

			// Not in the key ring: no signer, with a reason
			Signer missing=KeyAccess.signerFor(null, convex).get();
			assertNull(missing.keyPair());
			assertNotNull(missing.reason());

			// In the key ring and unlocked: that key pair
			HotWalletEntry we=HotWalletEntry.create(kp, "test");
			KeyRing keyRing=KeyRing.get();
			assertTrue(keyRing.add(we));
			try {
				Signer found=KeyAccess.signerFor(null, convex).get();
				assertSame(kp, found.keyPair());
				assertNull(found.reason());
			} finally {
				keyRing.remove(we);
			}
		}
	}
}

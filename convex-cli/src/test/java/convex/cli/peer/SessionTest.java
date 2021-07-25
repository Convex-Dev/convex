package convex.cli.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;


public class SessionTest {

	private static final String SESSION_FILENAME = "/tmp/session.dat";
	private static final String KEYSTORE_FILENAME = "/tmp/keystore.dat";

	public List<AKeyPair> generateSessionList(Session session, int itemCount) {
		List<AKeyPair> keyPairList = new ArrayList<AKeyPair>(itemCount);
		for (int index = 0; index < 10; index ++ ) {
			String hostname = String.format("testhostname_%d.com", index);
			AKeyPair keyPair = AKeyPair.generate();
			keyPairList.add(keyPair);
			session.addPeer(keyPair.getAccountKey(), hostname, KEYSTORE_FILENAME);
		}
		return keyPairList;
	}

	@Test
	public void sessionCreate() {
		Session session = new Session();
		int itemCount = 10;
		List<AKeyPair> keyPairList = generateSessionList(session, itemCount);
		assertEquals(session.getSize(), itemCount);
		for (int index = 0; index < itemCount; index ++ ) {
			AKeyPair keyPair = keyPairList.get(index);
			assertTrue(session.isPeer(keyPair.getAccountKey()));
			SessionItem item = session.getItemFromIndex(index);
			assertTrue(item.getAccountKey().equals(keyPair.getAccountKey()));
			item = session.getItemFromAccountKey(keyPair.getAccountKey());
			assertTrue(item.getAccountKey().equals(keyPair.getAccountKey()));
		}
	}

	@Test
	public void sessionGetHostNameList() {
		Session session = new Session();
		int itemCount = 10;
		List<AKeyPair> keyPairList = generateSessionList(session, itemCount);
		String[] hostnameList = session.getPeerHostnameList();
		for (int index = 0; index < hostnameList.length; index ++ ) {
			String expectedHostname = String.format("testhostname_%d.com", index);
			assertEquals(hostnameList[index], expectedHostname);
		}
	}

	@Test
	public void sessionStoreAndLoad() {
		File fp = new File(SESSION_FILENAME);
		if (fp.exists()) {
			fp.delete();
		}
		Session session = new Session();
		int itemCount = 10;
		List<AKeyPair> keyPairList = generateSessionList(session, itemCount);

		try {
			session.store(fp);

		} catch (IOException e) {
			fail(e);
		}
		assertTrue(fp.exists());

		Session savedSession = new Session();
		try {
			savedSession.load(fp);
		} catch (IOException e) {
			fail(e);
		}
		assertEquals(session.getSize(), savedSession.getSize());
		for (int index = 0; index < savedSession.getSize(); index ++) {
			SessionItem item = session.getItemFromIndex(index);
			SessionItem savedItem = savedSession.getItemFromIndex(index);
			assertTrue(item.getAccountKey().equals(savedItem.getAccountKey()));
		}
		fp.delete();
	}

	@Test
	public void sessionRemovePeer() {
		Session session = new Session();
		int itemCount = 10;
		List<AKeyPair> keyPairList = generateSessionList(session, itemCount);

		for (int index = 0; index < keyPairList.size(); index ++ ) {
			AKeyPair keyPair = keyPairList.get(index);
			session.removePeer(keyPair.getAccountKey());
		}
		assertEquals(session.getSize(), 0);
	}

}

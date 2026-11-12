package convex.restapi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.core.data.AccountKey;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.java.ConvexHTTP;
import convex.peer.API;
import convex.test.LocalClientNetwork;

/** The same client contract over real TCP and native Convex HTTP requests. */
@ExtendWith(LocalClientNetwork.Extension.class)
@Timeout(30)
public class RemoteBinaryClientTest {
	enum Transport { TCP, HTTP }

	private Convex connect(LocalClientNetwork network,Transport transport) throws Exception {
		Convex client=transport==Transport.TCP
				? ConvexRemote.connect(network.binaryAddress()) : ConvexHTTP.connect(network.uri());
		client.setTimeout(10_000);
		return client;
	}

	@ParameterizedTest
	@EnumSource(Transport.class)
	void queryAndErrors(Transport transport,LocalClientNetwork network) throws Exception {
		try (Convex client=connect(network,transport)) {
			Result result=client.query("#0").get(10,TimeUnit.SECONDS);
			assertFalse(result.isError(),result::toString);
			assertEquals(Address.ZERO,result.getValue());
			Result error=client.query("(fail :CLIENT_TEST \"expected\")").get(10,TimeUnit.SECONDS);
			assertEquals(Keyword.create("CLIENT_TEST"),error.getErrorCode());
		}
	}

	@ParameterizedTest
	@EnumSource(Transport.class)
	void status(Transport transport,LocalClientNetwork network) throws Exception {
		try (Convex client=connect(network,transport)) {
			Result result=client.requestStatus().get(10,TimeUnit.SECONDS);
			assertFalse(result.isError(),result::toString);
			var status=API.ensureStatusMap(result.getValue());
			assertNotNull(status);
			assertEquals(network.peerKey(),status.get(Keywords.PEER));
			assertNotNull(RT.ensureHash(status.get(Keywords.GENESIS)));
		}
	}

	@ParameterizedTest
	@EnumSource(Transport.class)
	void signedTransactionsAndAccountCreation(Transport transport,LocalClientNetwork network) throws Exception {
		var account=network.fundedClient();
		try (Convex client=connect(network,transport)) {
			client.setAddress(account.getAddress(),account.getKeyPair());
			for (int i=0;i<3;i++) {
				Result result=client.transact("(def a "+i+")").get(10,TimeUnit.SECONDS);
				assertFalse(result.isError(),result::toString);
				assertEquals(CVMLong.create(i),result.getValue());
			}
			assertEquals(CVMLong.create(2),client.query("a").get(10,TimeUnit.SECONDS).getValue());
			assertEquals(3L,account.queryAccount().get("sequence"));
			var key=AKeyPair.generate().getAccountKey();
			Address created=client.createAccount(key).get(10,TimeUnit.SECONDS);
			assertNotEquals(account.getAddress(),created);
			assertEquals(key,AccountKey.parse(account.queryAccount(created).get("key")));
			assertTrue(account.queryBalance()<1_000_000L);
		}
	}

	@ParameterizedTest
	@EnumSource(Transport.class)
	void accountCreationRequiresOrigin(Transport transport,LocalClientNetwork network) throws Exception {
		try (Convex client=connect(network,transport)) {
			assertThrows(IllegalArgumentException.class,()->client.createAccount(AKeyPair.generate().getAccountKey()));
		}
	}
}

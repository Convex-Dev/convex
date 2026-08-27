package convex.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.etch.EtchStore;
import convex.node.NodeConfig;

/** End-to-end point messaging through one public PoP and two NAT leaves. */
public class PointOfPresenceTest {

	private final AKeyPair aliceKey=AKeyPair.generate();
	private final AKeyPair bobKey=AKeyPair.generate();
	private final AKeyPair daveKey=AKeyPair.generate();

	private EtchStore aliceStore;
	private EtchStore bobStore;
	private EtchStore daveStore;
	private P2PNode alice;
	private P2PNode bob;
	private P2PNode dave;

	@AfterEach
	public void tearDown() throws Exception {
		if (alice!=null) alice.close();
		if (dave!=null) dave.close();
		if (bob!=null) bob.close();
		if (aliceStore!=null) aliceStore.close();
		if (daveStore!=null) daveStore.close();
		if (bobStore!=null) bobStore.close();
	}

	@Test
	public void testPublicAndPrivateMessagesThroughPop() throws Exception {
		aliceStore=EtchStore.createTemp("pop-alice");
		bobStore=EtchStore.createTemp("pop-bob");
		daveStore=EtchStore.createTemp("pop-dave");

		AccountKey bobNodeKey=bobKey.getAccountKey();
		alice=P2PNode.create(aliceStore,NodeConfig.port(-1),aliceKey)
			.pointsOfPresence(bobNodeKey);
		bob=P2PNode.create(bobStore,NodeConfig.localNetwork(),bobKey)
			.serveAllInbound()
			.relayMessages();
		dave=P2PNode.create(daveStore,NodeConfig.port(-1),daveKey)
			.pointsOfPresence(bobNodeKey);

		bob.launch();
		alice.launch();
		dave.launch();
		Convex aliceToBob=alice.connect(bobNodeKey,bob.getNodeServer().getHostAddress())
			.get(5,TimeUnit.SECONDS);
		dave.connect(bobNodeKey,bob.getNodeServer().getHostAddress())
			.get(5,TimeUnit.SECONDS);
		bob.whenInboundConnectionUpgraded(aliceKey.getAccountKey())
			.get(5,TimeUnit.SECONDS);
		bob.whenInboundConnectionUpgraded(daveKey.getAccountKey())
			.get(5,TimeUnit.SECONDS);

		// The leaves know only Bob; they do not require a direct route to one another.
		AHashMap<Keyword,ACell> daveInfo=nodeInfo(dave,daveKey.getAccountKey());
		AVector<?> davePops=(AVector<?>)daveInfo.get(Keywords.POPS);
		assertEquals(bobNodeKey,davePops.get(0));
		AHashMap<Keyword,ACell> bobInfo=nodeInfo(alice,bobNodeKey);
		assertEquals(CVMBool.TRUE,bobInfo.get(Keywords.RELAY));

		CompletableFuture<P2PNode.ReceivedMessage> publicDelivery=new CompletableFuture<>();
		CopyOnWriteArrayList<P2PNode.ReceivedMessage> daveDeliveries=new CopyOnWriteArrayList<>();
		dave.setMessageHandler(delivery -> {
			daveDeliveries.add(delivery);
			if (Strings.create("hello Dave").equals(delivery.value())) publicDelivery.complete(delivery);
		});

		// A claimed Alice envelope carrying an attacker's signature is processed
		// before the valid message on the same ordered route, but never relayed.
		long now=System.currentTimeMillis();
		AVector<ACell> forgedValue=Vectors.create(PointOfPresence.CONTEXT,CVMLong.ONE,
			daveKey.getAccountKey(),Blob.wrap(new byte[PointOfPresence.NONCE_LENGTH]),
			CVMLong.create(now),CVMLong.create(now+PointOfPresence.DEFAULT_TTL),
			CVMLong.create(PointOfPresence.MAX_HOPS),CVMBool.FALSE,Strings.create("forged"));
		SignedData<AVector<ACell>> attackerSigned=AKeyPair.generate().signData(forgedValue);
		SignedData<AVector<ACell>> forged=SignedData.create(aliceKey.getAccountKey(),
			attackerSigned.getSignature(),Ref.get(forgedValue));
		Message forgedMessage=Message.create(MessageType.UNKNOWN,Vectors.create(
			PointOfPresence.TAG,forged,Vectors.of(aliceKey.getAccountKey())));
		assertTrue(aliceToBob.trySend(forgedMessage));

		assertTrue(alice.sendMessage(daveKey.getAccountKey(),Strings.create("hello Dave")));
		P2PNode.ReceivedMessage publicMessage=publicDelivery.get(5,TimeUnit.SECONDS);
		assertEquals(1,daveDeliveries.size());
		assertEquals(aliceKey.getAccountKey(),publicMessage.sender());
		assertEquals(daveKey.getAccountKey(),publicMessage.destination());
		assertEquals(Strings.create("hello Dave"),publicMessage.value());
		assertFalse(publicMessage.encrypted());

		CompletableFuture<P2PNode.ReceivedMessage> privateDelivery=new CompletableFuture<>();
		alice.setMessageHandler(privateDelivery::complete);
		var privateValue=Strings.create("secret".repeat(100));
		assertTrue(dave.sendPrivateMessage(aliceKey.getAccountKey(),privateValue));
		P2PNode.ReceivedMessage privateMessage=privateDelivery.get(5,TimeUnit.SECONDS);
		assertEquals(daveKey.getAccountKey(),privateMessage.sender());
		assertEquals(privateValue,privateMessage.value());
		assertTrue(privateMessage.encrypted());
	}

	private static AHashMap<Keyword,ACell> nodeInfo(P2PNode node,AccountKey key) {
		return node.p2p(key).node().getNodeInfo();
	}
}

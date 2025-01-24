package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.exceptions.BadFormatException;
import convex.core.init.Init;
import convex.core.util.FileUtils;

import convex.core.cpos.CPoSConstants;

@TestInstance(Lifecycle.PER_CLASS)
public class PeerTest {

	public static String seed="1a44bbe097e38d2ba90e9426e9b1ab0ec12444a25e4d23b77fd121da728737f2";
	
	static AKeyPair KP=AKeyPair.create(seed);
	static AccountKey KEY=KP.getAccountKey();
	
	static State GENESIS=Init.createTestState(List.of(KEY)); 
	
	@Test
	public void testBasicPeer() throws IOException, BadFormatException {
		Peer p=Peer.create(KP, GENESIS);
		
		AMap<Keyword, ACell> data = p.toData();
		assertNotNull(data);
		
		Peer p2=Peer.fromData(KP, data);
		
		assertEquals(GENESIS,p2.getConsensusState());
		assertEquals(0,p2.getStatePosition());
		assertEquals(p.getPeerOrder(),p2.getPeerOrder());
		
		Peer p3= p2.updateState();
		assertEquals(GENESIS,p3.getConsensusState());
		
		Path temp=Files.createTempFile("peerData", "cad3");
		temp.toFile().deleteOnExit();
		
		FileUtils.writeCAD3(temp,data);
		AMap<Keyword, ACell> data2=FileUtils.loadCAD3(temp);
		
		Peer p4=Peer.fromData(KP, data2);
		assertEquals(p.getBelief(),p4.getBelief());
	}
	
	@Test
	public void testPeerStatus() {
		PeerStatus ps=PeerStatus.create(Address.ZERO, CPoSConstants.MINIMUM_EFFECTIVE_STAKE);
		
	}
}

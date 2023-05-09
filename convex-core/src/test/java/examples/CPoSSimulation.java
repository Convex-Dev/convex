package examples;

import java.util.List;
import java.util.Random;

import convex.core.Belief;
import convex.core.Block;
import convex.core.Peer;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.util.Text;
import convex.core.util.Utils;

public class CPoSSimulation {
	private static final int NUM_ROUNDS = 100000;
	private static final int NUM_PEERS = 3;
	
	private static final int D=6;
	
	State GENESIS;
	AKeyPair[] KPS=new AKeyPair[NUM_PEERS];
	AccountKey[] KEYS=new AccountKey[NUM_PEERS];
	Peer[] PEERS=new Peer[NUM_PEERS];
	
	Random r=new Random(1233);

	private long INITIAL_TS;
	
	public static void main(String... args) {
		new CPoSSimulation().run();
	}

	private void run() {
		try {
			initPeers();
			doSimulation();
		} catch (Throwable t) {
			throw Utils.sneakyThrow(t);
		}
	}

	private void doSimulation() throws InvalidDataException {
		StringBuilder sb=new StringBuilder();
		long ts=INITIAL_TS;
		for (int round=0; round<NUM_ROUNDS; round++) {
			// Propose a block from one Peer
			int proposalPeer=r.nextInt(NUM_PEERS);
			proposeBlock(proposalPeer,ts);
			
			sb.append(Text.leftPad(ts-INITIAL_TS, D+1));
			
			for (int i=0; i<NUM_PEERS; i++) {
				sb.append(" [");
				Peer p=PEERS[i];
				long cp = p.getPeerOrder().getConsensusPoint();
				long pp = p.getPeerOrder().getProposalPoint();
				long bc = p.getPeerOrder().getBlockCount();
				sb.append(Text.leftPad(cp, D));
				sb.append(Text.leftPad(pp, D));
				sb.append(Text.leftPad(bc, D));
				sb.append("]");
			}
			
			// Share a belief from one peer
			int sharePeer=r.nextInt(NUM_PEERS);
			Belief b=PEERS[sharePeer].getBelief();
			
			// Merge into random other Peer
			int receivePeer=r.nextInt(NUM_PEERS);
			Peer rp=PEERS[receivePeer];
			rp=rp.mergeBeliefs(b);
			PEERS[receivePeer]=rp;
			
			System.out.println(sb.toString());
			sb.setLength(0);
			ts+=10;
		}
	}

	private void proposeBlock(int ix, long ts) {
		Peer p=PEERS[ix];
		p=p.updateTimestamp(ts);
		Block block=Block.of(ts);
		p=p.proposeBlock(block);
		PEERS[ix]=p;
	}

	private void initPeers() {
		for (int i=0; i<NUM_PEERS; i++) {
			AKeyPair kp = AKeyPair.createSeeded(678687+i);
			KPS[i]=kp;
			KEYS[i]=kp.getAccountKey();
		}
		
		GENESIS=Init.createState(List.of(KEYS));
		INITIAL_TS=GENESIS.getTimestamp().longValue();
		
		for (int i=0; i<NUM_PEERS; i++) {
			Peer p=Peer.create(KPS[i], GENESIS);
			p=p.updateTimestamp(INITIAL_TS);
			PEERS[i]=p;
		}		
	}
}

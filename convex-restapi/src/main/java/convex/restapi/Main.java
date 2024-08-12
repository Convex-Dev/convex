package convex.restapi;

import convex.peer.API;
import convex.peer.Server;

public class Main {

	public static void main(String[] args) {
		Server s=API.launchPeer();
		System.out.println("Using Ed25519 seed: "+s.getKeyPair().getSeed());
		RESTServer rs=RESTServer.create(s);
		rs.start();
		
		System.out.println("Complete");
	}
}

package convex.restapi.test;

import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTServer;

public class Main {

	public static void main(String[] args) throws InterruptedException {
		Server s=API.launchPeer();
		System.out.println("Using Ed25519 seed: "+s.getKeyPair().getSeed());
		try (RESTServer rs=RESTServer.create(s)) {
			rs.start();
			System.out.println("Complete");
		}
	}
}

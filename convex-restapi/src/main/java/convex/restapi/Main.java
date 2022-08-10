package convex.restapi;

import convex.peer.API;
import convex.peer.Server;

public class Main {

	public static void main(String[] args) {
		Server s=API.launchPeer();
		RESTServer rs=RESTServer.create(s);
		rs.start();
		
		
		
	}
}

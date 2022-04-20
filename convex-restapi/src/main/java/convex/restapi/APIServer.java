package convex.restapi;

import convex.api.Convex;

import com.blade.Blade;

public class APIServer {

	public static int port;
	public static Convex convex;


	public static void start(int port, Convex convex) {
		APIServer.port = port;
		APIServer.convex = convex;
		Blade blade = Blade.of();
		blade.listen(port);
		blade.scanPackages("convex.restapi");
		blade.start();
	}
}

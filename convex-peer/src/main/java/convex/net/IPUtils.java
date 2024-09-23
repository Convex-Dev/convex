package convex.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import convex.core.Constants;
import convex.core.util.Utils;

public class IPUtils {

	public static InetAddress tryGetIP() throws InterruptedException {
		
		String s=tryGetWTF().trim();
		if (s!=null) {
			try {
				InetAddress i= InetAddress.getByName(s);
				return i;
			} catch (UnknownHostException e) {
				// continue
			}
		}
		return null;
	}
		
	public static String tryGetWTF() throws InterruptedException {
		HttpClient client = HttpClient.newHttpClient();
	
		HttpRequest request = HttpRequest.newBuilder(
			       URI.create("https://wtfismyip.com/text"))
			   .header("accept", "text/plain")
			   .build();

		 HttpResponse<String> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
			 String text=response.body();
			 return text;
		} catch (IOException e) {
			return null;
		}
	}
	
	/**
	 * Converts a Object to an InetSocketAddress
	 *
	 * @param o An Object to convert to a socket address. May be a String or existing InetSocketAddress
	 * @return A valid InetSocketAddress, or null if not in valid format
	 */
	public static InetSocketAddress toInetSocketAddress(Object o) {
		if (o instanceof InetSocketAddress) {
			return (InetSocketAddress) o;
		} else if (o instanceof String) {
			return toInetSocketAddress((String)o);
		} else if (o instanceof URL) {
			return toInetSocketAddress((URL)o);
		} else if (o instanceof URI) {
			return toInetSocketAddress((URI)o);
		} else {
			return null;
		}
	}

	/**
	 * Converts a String to an InetSocketAddress
	 *
	 * @param s A string in the format of a valid URL or "myhost.com:17888"
	 * @return A valid InetSocketAddress, or null if not in valid format
	 */
	public static InetSocketAddress toInetSocketAddress(String s) {
		if (s==null) return null;
		s=s.trim();
		try {
			// Try URI parsing first
			URI uri=new URI(s);
			InetSocketAddress sa= toInetSocketAddress(uri);
			return sa;
		} catch (URISyntaxException | IllegalArgumentException ex) {
			// Try to parse as host:port
			int colon = s.lastIndexOf(':');
			if (colon < 0) return null;
			try {
				String hostName = s.substring(0, colon); // up to last colon
				int port = Utils.toInt(s.substring(colon + 1)); // after last colon
				InetSocketAddress addr = new InetSocketAddress(hostName, port);
				return addr;
			} catch (SecurityException e) {
				// shouldn't happen?
				throw Utils.sneakyThrow(e);
			}
		}
	}

	/**
	 * Converts a URL to an InetSocketAddress. Will assume default port if not specified.
	 *
	 * @param url A valid URL
	 * @return A valid InetSocketAddress for the URL
	 */
	public static InetSocketAddress toInetSocketAddress(URL url) {
		String host=url.getHost();
		int port=url.getPort();
		if (port<0) port=Constants.DEFAULT_PEER_PORT;
		return new InetSocketAddress(host,port);
	}
	
	/**
	 * Converts a URI to an InetSocketAddress. Will assume default port if not specified.
	 *
	 * @param uri A valid URI
	 * @return A valid InetSocketAddress for the URI
	 */
	public static InetSocketAddress toInetSocketAddress(URI uri) {
		String host=uri.getHost();
		int port=uri.getPort();
		if (port<0) port=Constants.DEFAULT_PEER_PORT;
		return new InetSocketAddress(host,port);
	}
	
	public static void main(String[] args) throws InterruptedException {
		System.out.println(tryGetIP());
		
		System.out.println(tryGetIP());
	}
}

package convex.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
	
	public static void main(String[] args) throws InterruptedException {
		System.out.println(tryGetIP());
		
		System.out.println(tryGetIP());
	}
}

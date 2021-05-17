package convex.cli;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.Properties;



public class Session {

	private Properties values = new Properties();

	public void load(File filename) throws IOException  {
		if (filename.exists()) {
			FileInputStream stream = new FileInputStream(filename);
			values.load(stream);
		}
	}

	public void addPeer(InetSocketAddress address, String etchFilename){
		int count = Integer.parseInt(values.getProperty("count", "0"));
		count ++;
		values.setProperty("count", String.valueOf(count));
		values.setProperty("address-"+count, address.getHostName());
		values.setProperty("port-"+count, String.valueOf(address.getPort()));
		values.setProperty("etchFilename-"+count, etchFilename);
	}

	public void store(File filename) throws IOException {
		FileOutputStream stream = new FileOutputStream(filename);
		values.store(stream, "Convex Peer Session");
	}

	public int getPort(int index) {
		return Integer.parseInt(values.getProperty("port-"+index, "0"));
	}
}

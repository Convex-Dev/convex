package convex.cli;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.util.Properties;



public class Session {

	private Properties values = new Properties();

	public void load(File filename) throws IOException  {
		if (filename.exists()) {
			FileInputStream stream = new FileInputStream(filename);
			values.load(stream);
		}
	}

	public void addPeer(String address, String hostname, int port, String etchFilename){
		String[] items = new String[] {hostname, String.valueOf(port), etchFilename};
		values.setProperty(address, String.join(",", items));
	}

	public void removePeer(String address) {
		values.remove(address);
	}

	public void store(File filename) throws IOException {
		FileOutputStream stream = new FileOutputStream(filename);
		values.store(stream, "Convex Peer Session");
	}

	public int size() {
		return values.size();
	}

	public int getPort(int index) {
		int count = 1;
		for (String name: values.stringPropertyNames()) {
			if (count == index) {
				String line = values.getProperty(name, "");
				String[] items = line.split(",");
				return Integer.parseInt(items[1]);
			}
			count ++;
		}
		return 0;
	}
}

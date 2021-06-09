package convex.cli.peer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class Session {

	private Properties values = new Properties();

	private static final String PEER_HEADER_NAME = "peer.";

	private static final String PEER_HEADER_NAME_MATCH = "^peer\\..*";

	/**
	 * Load a session data from file.
	 *
	 * @param filename Filename of the session file to load.
	 * @throws IOException 
	 */
	public void load(File filename) throws IOException  {
		if (filename.exists()) {
			FileInputStream stream = new FileInputStream(filename);
			values.load(stream);
		}
	}

	/**
	 * Add a peer to the list of peers kept in the session data.
	 *
	 * @param addressKey Address sttring of the peer, this is the public key in hex without a leading 0x.
	 *
	 * @param hostname Hostname of the peer. At the moment this will be localhost.
	 *
	 * @param port Port number the peer is listening on.
	 *
	 * @param etchFilename Filename that the peer is using to store the peer's state.
	 *
	 */
	public void addPeer(String addressKey, String hostname, String etchFilename){
		String[] items = new String[] {hostname, etchFilename};
		values.setProperty(PEER_HEADER_NAME + addressKey, String.join(",", items));
	}

	/**
	 * Remove a peer from the list of peers held by this session.
	 *
	 * @param addressKey Address of the peer, this is the public key used by the peer.
	 *
	 */
	public void removePeer(String addressKey) {
		values.remove(PEER_HEADER_NAME + addressKey);
	}

	/**
	* Store the session to a file.
	*
	* @param filename Filename to save the session too.
	*
	* @throws IOException if the file data cannot be writtern.
	*
	*/
	public void store(File filename) throws IOException {
		FileOutputStream stream = new FileOutputStream(filename);
		values.store(stream, "Convex Session");
	}

	/**
	 * Return the number of peers added to this session.
	 *
	 * @return number of peers found for this session.
	 *
	 */
	public int getPeerCount() {
		int count = 0;
		for (String name: values.stringPropertyNames()) {
			if ( name.matches(PEER_HEADER_NAME_MATCH)) {
				count ++;
			}
		}
		return count;
	}

	/**
	 * Return true of false if the peer name exists in the list of peers for this session
	 *
	 * @param name Name of the peer to check to see if it exists.
	 *
	 * @return true if the peer name exists.
	 *
	 */
	public boolean isPeer(String name) {
		String line = values.getProperty(PEER_HEADER_NAME + name, "");
		if (line.length() > 0) {
			return true;
		}
		return false;
	}

	/**
	 * Return the socket address of the peer from a given index.
	 *
	 * @param index Index of the peer list to get an address.
	 *
	 * @return Hostname of the peer. If index is out of range return null.
	 *
	 */
	public String getPeerHostnameFromIndex(int index) {
		int count = 1;
		for (String name: values.stringPropertyNames()) {
			if ( name.matches(PEER_HEADER_NAME_MATCH)) {
				if (count == index) {
					String line = values.getProperty(name, "");
					if (line.length() > 0) {
						String[] items = line.split(",");
						return items[0];
					}
				}
				count ++;
			}
		}
		return null;
	}

	/**
	 * Return the socket address of the peer from a given peer name.
	 *
	 * @param name Name of the peer to get an address.
	 *
	 * @return Hostname of the peer. If the peer name cannot be found return null.
	 *
	 */
	public String getPeerHostnameFromName(String name) {
		String line = values.getProperty(PEER_HEADER_NAME + name, "");
		if (line.length() > 1) {
			String[] items = line.split(",");
			return items[0];
		}
		return null;
	}

	/**
	 * Get a list of peer hostnames.
	 *
	 * @return List<String> hostname item for each stored peer.
	 *
	 */
	public String[] getPeerHostnameList() {
		String[] result = new String[getPeerCount()];
		int index = 0;
		for (String name: values.stringPropertyNames()) {
			if ( name.matches(PEER_HEADER_NAME_MATCH)) {
				String line = values.getProperty(name, "");
				if (line.length() > 0){
					String[] items = line.split(",");
					result[index] = items[0];
				}
				index ++;
			}
		}
		return result;
	}
}

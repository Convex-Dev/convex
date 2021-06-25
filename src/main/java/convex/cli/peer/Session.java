package convex.cli.peer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import convex.core.data.AccountKey;

public class Session {


    protected List<SessionItem> items = new ArrayList<SessionItem>();
	/**
	 * Load a session data from file.
	 *
	 * @param filename Filename of the session file to load.
	 *
	 * @throws IOException
	 */
	public void load(File filename) throws IOException  {
		items.clear();
		if (filename.exists()) {
			FileInputStream stream = new FileInputStream(filename);
			Properties values = new Properties();
			values.load(stream);
			for (String name: values.stringPropertyNames()) {
				String line = values.getProperty(name, "");
				SessionItem item = SessionItem.createFromString(line);
				items.add(item);
			}
		}
	}

	/**
	 * Add a peer to the list of peers kept in the session data.
	 *
	 * @param addressKey AccountKey of the peer.
	 *
	 * @param hostname Hostname of the peer. This includes the port number.
	 *
	 * @param etchFilename Filename that the peer is using to store the peer's state.
	 *
	 */
	public void addPeer(AccountKey accountKey, String hostname, String etchFilename){
		SessionItem item = SessionItem.create(accountKey, hostname, etchFilename);
		items.add(item);
	}

	/**
	 * Remove a peer from the list of peers held by this session.
	 *
	 * @param addressKey Address of the peer, this is the public key used by the peer.
	 *
	 */
	public void removePeer(AccountKey accountKey) {
		for (SessionItem item: items) {
			if (item.getAccountKey().equals(accountKey)) {
				items.remove(item);
				return;
			}
		}
	}

	/**
	* Store the session list to a file.
	*
	* @param filename Filename to save the session too.
	*
	* @throws IOException if the file data cannot be writtern.
	*
	*/
	public void store(File filename) throws IOException {
		FileOutputStream stream = new FileOutputStream(filename);
		Properties values = new Properties();
		int index = 0;
		for (SessionItem item: items) {
			values.setProperty(String.valueOf(index), item.toString());
			index ++;
		}
		values.store(stream, "Convex Session");
	}

	/**
	 * Return the number of session items added to this session.
	 *
	 * @return number of items found for this session.
	 *
	 */
	public int getSize() {
		return items.size();
	}

	/**
	 * Return true of false if the peer name exists in the list of peers for this session
	 *
	 * @param name Name of the peer to check to see if it exists.
	 *
	 * @return true if the peer name exists.
	 *
	 */
	public boolean isPeer(AccountKey accountKey) {
		SessionItem item = getItemFromAccountKey(accountKey);
		return item != null;
	}

	/**
	 * Return a session item based on the peer index.
	 *
	 * @param index The index of the peer in the list, starting from 0.
	 *
	 * @return Session Item if the item is found at the index, if not then return null.
	 *
	 */
	public SessionItem getItemFromIndex(int index) {
		return items.get(index);
	}

	/**
	 * Get a session item based on the peers AccountKey.
	 *
	 * @param accountKey AccountKey of the peer to get the session item for.
	 *
	 * @return SessionItem object or null if the peer account key cannot be found
	 *
	 */
	public SessionItem getItemFromAccountKey(AccountKey accountKey) {
		for (SessionItem item: items) {
			if (item.getAccountKey().equals(accountKey)) {
				return item;
			}
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
		String[] result = new String[items.size()];
		int index = 0;
		for (SessionItem item: items) {
			result[index] = item.getHostname();
			index ++;
		}
		return result;
	}
}

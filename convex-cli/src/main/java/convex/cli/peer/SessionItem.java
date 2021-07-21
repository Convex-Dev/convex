package convex.cli.peer;

import java.net.InetSocketAddress;

import convex.core.data.AccountKey;
import convex.core.util.Utils;

public class SessionItem {

	protected static final String DELIMITER = ",";

	protected AccountKey accountKey;
	protected String hostname;
	protected String etchFilename;

	/**
	 * Creates a new SessionItem object
	 */
	private SessionItem(AccountKey accountKey, String hostname, String etchFilename) {
		this.accountKey = accountKey;
		this.hostname = hostname;
		this.etchFilename = etchFilename;
	}

	/**
	 * Create a new SessionItem object using the following fields:
	 *
	 * @param accountKey AccountKey of the peer.
	 *
	 * @param hostname Hostname and port of the peer.
	 *
	 * @param etchFilename Etch filename the peer is using.
	 *
	 * @return a new SessionItem object.
	 *
	 */
	public static SessionItem create(AccountKey accountKey, String hostname, String etchFilename) {
		return new SessionItem(accountKey, hostname, etchFilename);
	}

	/**
	 * Create a new SessionItem from a comma delimited string.
	 *
	 * @param value String that contain the session item data.
	 *
	 * @return a new SessionItem object.
	 *
	 */
	public static SessionItem createFromString(String value) {
		String[] values = value.split(DELIMITER);
		return create(AccountKey.fromChecksumHex(values[0]), values[1], values[2]);
	}

	/**
	 * @return the peer AccountKey.
	 *
	 */
	public AccountKey getAccountKey() {
		return accountKey;
	}

	/**
	 * @return the string name to use for this session item.
	 *
	 */
	public String getName() {
		return accountKey.toChecksumHex();
	}

	/**
	 * @return the peers hostname and port number.
	 *
	 */
	public String getHostname() {
		return hostname;
	}

	public int getPort() {
		InetSocketAddress address = Utils.toInetSocketAddress(hostname);
		return address.getPort();
	}

	public InetSocketAddress getHostAddress() {
		return Utils.toInetSocketAddress(hostname);
	}

	/**
	 * @return the used Etch Filename for this peer.
	 *
	 */
	public String getEtchFilename() {
		return etchFilename;
	}

	/**
	 * @return the encoded data for this session as a commar delimited string.
	 *
	 */
	public String toString() {
		String[] values = new String[3];
		values[0] = accountKey.toChecksumHex();
		values[1] = hostname;
		values[2] = etchFilename;
		return String.join(DELIMITER, values);
	}
}

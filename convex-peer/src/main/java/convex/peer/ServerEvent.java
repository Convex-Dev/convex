package convex.peer;

/**
 * Lightweight wrapper for server events
 */
public class ServerEvent {

    protected ServerInformation information=null;
    protected Server server;
    protected String reason;

    private ServerEvent(Server server, String reason) {
        this.server = server;
        this.reason = reason;
    }

    public static ServerEvent create(Server server, String reason) {
        return new ServerEvent(server, reason);
    }

    public ServerInformation getInformation() {
        if (information==null) {
        	information=ServerInformation.create(server);
        }
    	return information;
    }
    public String getReason() {
        return reason;
    }

    public Server getServer() {
        return server;
    }
}

package convex.peer;

public class ServerEvent {

    protected ServerInformation information;
    protected String reason;

    private ServerEvent(ServerInformation information, String reason) {
        this.information = information;
        this.reason = reason;
    }

    public static ServerEvent create(Server server, String reason) {
        return new ServerEvent(ServerInformation.create(server), reason);
    }

    public ServerInformation getInformation() {
        return information;
    }
    public String getReason() {
        return reason;
    }
}

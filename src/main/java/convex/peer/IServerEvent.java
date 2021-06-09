package convex.peer;

/**
 * Server Event Interface. The server will post events to this the callback.
 *
 */
public interface IServerEvent {

    void onServerMessage(Server server, String message);       // sent when a message needs to be displayed
    void onServerChange(ServerInformation serverInformation);                       // sent on a peer change status or connection


}

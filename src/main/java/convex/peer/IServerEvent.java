package convex.peer;

/**
 * Server Event Interface. The server will post events to this the callback.
 *
 */
public interface IServerEvent {

    void onServerChange(ServerEvent serverEvent);         // sent on server change status, connections, consensus


}

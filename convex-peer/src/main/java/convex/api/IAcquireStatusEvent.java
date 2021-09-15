package convex.api;

/**
 * Server Event Interface. The server will post events to this the callback.
 *
 */
public interface IAcquireStatusEvent {

    void onAcquireStatusChange(AcquireStatus status);         // sent on acquire status change

}

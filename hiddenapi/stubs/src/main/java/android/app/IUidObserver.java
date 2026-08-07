package android.app;

import android.os.Binder;

/**
 * The union of every method this interface has carried across API 27 to 37, rather than the four
 * the daemon happens to listen for.
 *
 * The real framework class is what a `Stub` subclass extends at runtime, so a method left out here
 * is one the subclass cannot override and the platform still finds abstract. The interface is
 * `oneway`, so AMS never waits and never sees the failure; an `AbstractMethodError` escaping the
 * callback is routed by `JavaBBinder::onTransact` to `report_java_lang_error`, which is fatal, and
 * the daemon's own uncaught handler would end the process anyway. That is a loud death for a
 * callback nobody asked for.
 *
 * Nothing dispatches the two absent from the old file today: `onUidStateChanged` and
 * `onUidProcAdjChanged` are gated on UID_OBSERVER_PROCSTATE, UID_OBSERVER_CAPABILITY and
 * UID_OBSERVER_PROC_OOM_ADJ, and `VectorService` registers for ACTIVE, GONE, IDLE and CACHED. So
 * this is insurance against a wider mask -- ours or an OEM's -- not a live crash.
 *
 * Both signatures are declared for the two methods that changed shape. Only one of each pair
 * exists in any given release, and the other is then an ordinary unused method on the subclass.
 */
public interface IUidObserver {

    /** API 27..37. */
    void onUidGone(int uid, boolean disabled);

    /** API 27..37. */
    void onUidActive(int uid);

    /** API 27..37. */
    void onUidIdle(int uid, boolean disabled);

    /** API 27..29; replaced by the capability overload in 30. */
    void onUidStateChanged(int uid, int procState, long procStateSeq);

    /** API 30..37. */
    void onUidStateChanged(int uid, int procState, long procStateSeq, int capability);

    /** API 33 only; replaced by the adj overload in 34. */
    void onUidProcAdjChanged(int uid);

    /** API 34..37. */
    void onUidProcAdjChanged(int uid, int adj);

    /** API 27..37. */
    void onUidCachedChanged(int uid, boolean cached);

    abstract class Stub extends Binder implements IUidObserver {
    }
}

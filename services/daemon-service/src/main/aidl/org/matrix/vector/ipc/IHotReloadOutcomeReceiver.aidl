package org.matrix.vector.ipc;

import org.matrix.vector.ipc.HotReloadOutcome;

/**
 * How an injected process answers a hot reload request.
 *
 * <p>Separate from the request so the request itself can be oneway. The work behind it runs
 * arbitrary module code - {@code onHotReloading} is allowed to take as long as it likes - and
 * neither a daemon thread nor a target's RELOADING state should be held for that long.</p>
 */
interface IHotReloadOutcomeReceiver {
    oneway void onOutcome(in HotReloadOutcome outcome) = 1;
}

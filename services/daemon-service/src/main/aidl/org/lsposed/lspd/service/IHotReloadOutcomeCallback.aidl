package org.lsposed.lspd.service;

import org.lsposed.lspd.models.HotReloadOutcome;

/**
 * How an injected process answers a hot reload request.
 *
 * Separate from the request so the request itself can be oneway: the work behind it runs arbitrary
 * module code - onHotReloading is allowed to take as long as it likes - and nothing in the daemon
 * should be holding a thread, or a target's RELOADING state, for the duration.
 */
interface IHotReloadOutcomeCallback {
    oneway void onHotReloadOutcome(in HotReloadOutcome outcome) = 1;
}

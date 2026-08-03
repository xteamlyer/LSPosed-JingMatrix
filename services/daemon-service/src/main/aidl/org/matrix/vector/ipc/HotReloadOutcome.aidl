package org.matrix.vector.ipc;

/**
 * What an injected process reports back after attempting a hot reload.
 *
 * <p>Three fields rather than one status, because the API's encoding is lossy in two places and
 * both losses are ones a module app cannot recover from.</p>
 */
parcelable HotReloadOutcome {
    /**
     * One of {@code IXposedService.HOT_RELOAD_*}, passed through unchanged so that nothing between
     * the module and the module app has to re-encode it.
     */
    int status;

    /**
     * The framework's diagnostic, or null when there is nothing to say - which is success, and
     * exactly one kind of failure. {@code HotReloadResult} reserves FAILED-with-a-null-message for a
     * module refusal: it means {@code onHotReloading} returned false, and no other failure may
     * present that way.
     */
    String message;

    /**
     * True only when {@code onHotReloading} returned false.
     *
     * <p>This is what lets the daemon keep the null message for a genuine refusal while supplying
     * one for every other failure - including a module exception that happened to carry no message
     * of its own, which would otherwise be indistinguishable from a refusal.</p>
     */
    boolean refused;

    /**
     * True once the process has actually swapped generations, whatever {@link #status} says.
     *
     * <p>Not the same question as whether the reload succeeded. {@code onHotReloaded} is called
     * after the swap is committed, because the API releases the old generation "after this callback
     * returns <b>or throws</b>" - so a throw from it reports FAILED while the process is running
     * the new code. The daemon has to record the new version anyway, or {@code getRunningTargets()}
     * would keep naming a generation that is no longer loaded.</p>
     */
    boolean generationChanged;
}

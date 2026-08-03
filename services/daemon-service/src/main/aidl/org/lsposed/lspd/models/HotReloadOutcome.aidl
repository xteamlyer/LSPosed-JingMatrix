package org.lsposed.lspd.models;

/**
 * Result of a hot reload performed inside a hooked process.
 */
parcelable HotReloadOutcome {
    /** One of IXposedService.HOT_RELOAD_*. */
    int status;

    /**
     * Diagnostic message. Null is reserved for a module refusal, so every other failure has to
     * carry a message even when the module's own exception had none.
     */
    String message;

    /**
     * True only when onHotReloading returned false. This is what lets the daemon keep a null
     * message for a genuine refusal while supplying one for every other failure.
     */
    boolean refused;

    /**
     * True once the process has actually swapped generations, whatever the status says.
     *
     * onHotReloaded is called after the swap is committed, because the interface releases the old
     * generation "after this callback returns or throws". So a throw from it reports FAILED while
     * the process is running the new code, and the daemon has to record the new version anyway -
     * otherwise getRunningTargets() would keep naming a generation that is no longer loaded.
     */
    boolean generationChanged;
}

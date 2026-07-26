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
}

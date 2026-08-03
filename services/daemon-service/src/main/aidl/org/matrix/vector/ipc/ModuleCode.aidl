package org.matrix.vector.ipc;

/**
 * One generation of a module's executable code, as the daemon read it out of the module APK.
 *
 * <p>Named for what it is rather than where it came from: it is no longer "an APK" in any useful
 * sense - the dex lives in shared memory, the entry lists come from {@code META-INF/xposed/}, and
 * two of the fields are policy read out of {@code module.prop}. A hot reload consists of handing an
 * injected process a second one of these for a module it has already loaded.</p>
 */
parcelable ModuleCode {
    /**
     * The module's dex files, mapped read-only. Consumed by the receiving process: it maps them
     * into a class loader and closes them, so a second generation needs a second set.
     */
    List<SharedMemory> preLoadedDexes;

    /**
     * Fully qualified names of the module's Java entry classes, from
     * {@code META-INF/xposed/java_init.list} (or {@code assets/xposed_init} for a legacy module).
     *
     * <p>The API requires at least one, and specifies hot reload only for modules that declare
     * <b>exactly</b> one - a module with several has no single entry to hand the reload to, and
     * must be answered {@code UNSUPPORTED}.</p>
     */
    List<String> moduleClassNames;

    /** Native libraries the module wants {@code native_init} called on, from native_init.list. */
    List<String> moduleLibraryNames;

    /** True for a module selected by {@code assets/xposed_init} rather than by targetApiVersion. */
    boolean legacy;

    /**
     * module.prop {@code targetApiVersion}, verbatim, or 0 when the key is absent or unparseable.
     * A legacy module usually has none, but one that declares a value below 101 keeps it - {@link
     * #legacy} is what says how the module is loaded, not this.
     *
     * <p>Carried into the process because one of API 102's rules is only enforceable there: a
     * module targeting 102 or higher must not be able to resolve the legacy
     * {@code de.robv.android.xposed} API, and {@link #legacy} is a boolean that cannot tell 101
     * from 102.</p>
     *
     * <p>module.prop's {@code minApiVersion} is deliberately absent. The API puts that check on the
     * module, through {@code XposedInterface#getApiVersion()}, and the manager reads module.prop
     * itself for what it displays - a copy here would have no reader.</p>
     */
    int targetApiVersion;

    /**
     * module.prop {@code autoHotReload}: whether reinstalling the module app should offer a hot
     * reload to the processes already running it, rather than leaving them on the old code until
     * they restart.
     *
     * <p>An offer, not a command - the running module still has the last word through
     * {@code onHotReloading}.</p>
     */
    boolean autoHotReload;

    /**
     * module.prop {@code exceptionMode}, normalised by the daemon. false, the value an absent key
     * parses to, is PROTECTIVE - what {@code ExceptionMode.DEFAULT} is specified to fall back to.
     */
    boolean exceptionPassthrough;

    /**
     * Where the daemon staged this module's native libraries, for the one process that cannot map
     * them out of the APK: /data/app is apk_data_file, which system_server may read and map but
     * never execute.
     *
     * <p>Null when the module ships none for this ABI, when staging failed, or when the module was
     * never destined for system_server in the first place.</p>
     */
    @nullable String nativeLibraryDir;
}

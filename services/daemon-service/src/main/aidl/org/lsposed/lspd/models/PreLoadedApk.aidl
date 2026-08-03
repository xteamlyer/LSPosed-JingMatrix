package org.lsposed.lspd.models;

parcelable PreLoadedApk {
    List<SharedMemory> preLoadedDexes;
    List<String> moduleClassNames;
    List<String> moduleLibraryNames;
    boolean legacy;
    // module.prop 'targetApiVersion'. Carried into the process because two of API 102's rules are
    // only enforceable there: a module targeting 102 must not be able to resolve the legacy API,
    // and `legacy` is a boolean that cannot tell 101 from 102. 0 for a legacy module, which
    // declares no target at all.
    //
    // minApiVersion is deliberately not here. The API puts that check on the module, through
    // getApiVersion(), and the manager reads module.prop itself for what it displays - a copy in
    // here would have no reader.
    int targetApiVersion;
    // module.prop 'autoHotReload'. Whether reinstalling the module app should offer a hot reload to
    // the processes already running it, rather than leaving them on the old code until they
    // restart. The module still has the last word, through onHotReloading.
    boolean autoHotReload;
    // module.prop 'exceptionMode', normalised by the daemon. false, the value an absent key
    // parses to, is PROTECTIVE - what ExceptionMode.DEFAULT is specified to fall back to.
    boolean exceptionPassthrough;
    // Where the daemon staged this module's native libraries, for the one process that cannot map
    // them out of the APK. Null when the module ships none for this ABI, when staging failed, or
    // when the module was never destined for system_server in the first place.
    @nullable String nativeLibraryDir;
}

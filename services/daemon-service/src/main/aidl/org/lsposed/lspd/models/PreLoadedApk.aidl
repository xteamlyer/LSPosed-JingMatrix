package org.lsposed.lspd.models;

parcelable PreLoadedApk {
    List<SharedMemory> preLoadedDexes;
    List<String> moduleClassNames;
    List<String> moduleLibraryNames;
    boolean legacy;
    int targetApiVersion;
    int minApiVersion;
    boolean autoHotReload;
    // module.prop 'exceptionMode', normalised by the daemon. false, the value an absent key
    // parses to, is PROTECTIVE - what ExceptionMode.DEFAULT is specified to fall back to.
    boolean exceptionPassthrough;
}

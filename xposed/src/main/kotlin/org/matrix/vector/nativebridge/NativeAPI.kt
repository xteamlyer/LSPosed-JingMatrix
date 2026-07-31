package org.matrix.vector.nativebridge

object NativeAPI {
    @JvmStatic external fun recordNativeEntrypoint(library_name: String)

    @JvmStatic
    external fun initializeNativeEntrypoint(library_name: String, library_path: String): Boolean
}

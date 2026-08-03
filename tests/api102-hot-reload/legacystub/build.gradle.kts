// Compile-only stubs of the legacy Xposed API.
//
// Never packaged. Their whole purpose is to put a *type reference* to
// de.robv.android.xposed.XposedBridge in the module's dex with no definition behind it, so that
// resolving it at runtime has to go through the module class loader - which is where API 102's
// "modules targeting 102 cannot call legacy APIs" rule is enforced.
//
// This is the only form of the test that survives dex obfuscation. A Class.forName with a literal
// name proves nothing there: the framework rewrites those package names per boot, so the literal
// resolves to nothing whether or not the rule is enforced. A type reference, by contrast, is
// rewritten along with everything else, so the loader is asked for the name the module would
// really use.
plugins { id("java-library") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

package org.matrix.hrtarget;

/** Method the test module hooks. Unhooked it always returns "ORIGINAL". */
public class Probe {

    public static String value() {
        return "ORIGINAL";
    }

    /** Throws so we can observe how exceptions travel through the hook chain. */
    public static String boom() {
        throw new IllegalArgumentException("boom-from-original");
    }
}

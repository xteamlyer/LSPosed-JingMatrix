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

    /**
     * Hooked by a hooker that blocks, so a reload can be driven while a call is still in flight.
     *
     * The interface says the chain is snapshot based and "replacing a hook while a call is running
     * does not affect that in-flight call"; the only way to see that is to have a call running
     * across the swap and record which generation answered it.
     */
    public static String slow() {
        return "ORIGINAL-SLOW";
    }
}

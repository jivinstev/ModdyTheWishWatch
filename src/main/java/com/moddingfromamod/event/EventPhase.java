package com.moddingfromamod.event;

/** Where a staged event is in its life (E1). */
public enum EventPhase {
    /** Its motion is playing. */
    RUNNING,
    /** Giving the world back, a budget's worth a tick, newest first. */
    RESTORING,
    /** Finished and left in place (a crater, a crack) until the child undoes it. */
    KEPT,
    /** Nothing left to give back. */
    DONE
}

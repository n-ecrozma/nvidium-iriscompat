package me.cortex.nvidium.renderers;

import me.cortex.nvidium.util.GPUTiming;

public abstract class Phase {
    GPUTiming timing = new GPUTiming();

    public void delete() {
        timing.free();
    }

    public GPUTiming getTiming() {
        return timing;
    }

    /** Runs a draw that replaces this phase's own (e.g. with a shader pack program), keeping the GPU timing. */
    public void timed(Runnable draw) {
        timing.marker();
        draw.run();
        timing.marker();
        timing.tick();
    }
}

package me.cortex.nvidium.sodiumCompat;

import me.cortex.nvidium.Nvidium;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;

/**
 * Single point of contact with Iris. Every method is safe to call when Iris is not installed:
 * {@link IrisApi} is only touched behind {@link #IRIS_LOADED}.
 */
public final class IrisCompat {
    public static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

    private IrisCompat() {}

    public static boolean isShaderPackInUse() {
        return IRIS_LOADED && IrisApi.getInstance().isShaderPackInUse();
    }

    public static boolean isRenderingShadowPass() {
        return IRIS_LOADED && IrisApi.getInstance().isRenderingShadowPass();
    }

    /** Whether Iris renders with reverse-Z right now (it doesn't in its shadow pass). */
    public static boolean isReverseZDuringShaders() {
        return !IRIS_LOADED || IrisApi.getInstance().isReverseZDuringShaders();
    }

    /**
     * Whether Nvidium should keep rendering while a shader pack is active.
     * Without the experimental option Nvidium hands rendering back to Sodium, like upstream does.
     */
    public static boolean shouldRenderWithShaderPack() {
        return Nvidium.config.iris_support;
    }

    /**
     * Whether Nvidium has to turn itself off because of Iris.
     */
    public static boolean shouldDisableNvidium() {
        return isShaderPackInUse() && !shouldRenderWithShaderPack();
    }
}

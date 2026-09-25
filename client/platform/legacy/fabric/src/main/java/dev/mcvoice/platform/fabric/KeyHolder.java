//#if !LEGACYFABRIC_API
package dev.mcvoice.platform.fabric;

/** Implemented on GameOptions by {@link dev.mcvoice.platform.fabric.mixin.GameOptionsMixin}. */
public interface KeyHolder {
    /** Appends the MCVoice key bindings to GameOptions#allKeys once; {@code reload} re-reads options.txt. */
    void mcvoice$addKeys(boolean reload);
}
//#endif

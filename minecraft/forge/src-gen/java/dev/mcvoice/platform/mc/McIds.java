package dev.mcvoice.platform.mc;




import net.minecraft.util.ResourceLocation;


/** Resource identifiers across the ResourceLocation -> Identifier rename (1.21.11). */
public final class McIds {
    private McIds() {
    }


















    public static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    public static ResourceLocation parse(String id) {
        return new ResourceLocation(id);
    }

}

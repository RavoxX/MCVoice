package dev.mcvoice.platform.mc;

//#if MC >= 1.21.11
import net.minecraft.resources.Identifier;
//#else
import net.minecraft.resources.ResourceLocation;
//#endif

/** Resource identifiers across the ResourceLocation -> Identifier rename (1.21.11). */
public final class McIds {
    private McIds() {
    }

    //#if MC >= 1.21.11
    public static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static Identifier parse(String id) {
        return Identifier.parse(id);
    }
    //#elif MC >= 1.21
    public static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    public static ResourceLocation parse(String id) {
        return ResourceLocation.parse(id);
    }
    //#else
    public static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    public static ResourceLocation parse(String id) {
        return new ResourceLocation(id);
    }
    //#endif
}

package dev.mcvoice.platform.mc;


import net.minecraft.resources.Identifier;




/** Resource identifiers across the ResourceLocation -> Identifier rename (1.21.11). */
public final class McIds {
    private McIds() {
    }


    public static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static Identifier parse(String id) {
        return Identifier.parse(id);
    }

















}

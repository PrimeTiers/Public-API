package net.primetiers.api.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The gamemodes PrimeTiers currently tests, with the slugs the API uses.
 *
 * <p>Gamemodes in API responses are plain strings, so a gamemode added after this
 * library was released still comes through. Use {@link #fromSlug(String)} to map a slug
 * to a constant, and {@link net.primetiers.api.PrimeTiersClient#getGamemodes()} for the
 * live list.
 */
public enum Gamemode {
    CRYSTAL("crystal"),
    AXE("axe"),
    SWORD("sword"),
    MACE("mace"),
    MACE_SPEAR("mace-spear"),
    NETHERITE_OP("nethertite"),
    POT("pot"),
    SMP("smp"),
    UHC("uhc"),
    CART("cart");

    private final String slug;

    Gamemode(String slug) {
        this.slug = slug;
    }

    /** The name the API uses, e.g. {@code "mace-spear"}. */
    public String slug() {
        return slug;
    }

    /**
     * Finds a gamemode by slug, ignoring case. Also accepts the old ids {@code macespear}
     * and {@code nethop}.
     *
     * @return the gamemode, or empty if the slug is unknown to this version of the library
     */
    public static Optional<Gamemode> fromSlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        String s = slug.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "macespear" -> s = MACE_SPEAR.slug;
            case "nethop" -> s = NETHERITE_OP.slug;
            default -> { }
        }
        for (Gamemode g : values()) {
            if (g.slug.equals(s)) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return slug;
    }
}

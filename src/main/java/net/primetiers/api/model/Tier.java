package net.primetiers.api.model;

import java.util.Locale;
import java.util.Optional;

/**
 * PrimeTiers tiers, from best ({@link #HT1}) to worst ({@link #LT5}).
 *
 * <p>Declaration order is rank order, so {@code a.compareTo(b) < 0} means {@code a} is the
 * better tier, and {@link #isBetterThan(Tier)} says the same thing more readably.
 */
public enum Tier {
    HT1, LT1, HT2, LT2, HT3, LT3, HT4, LT4, HT5, LT5;

    /** Position from 1 (HT1) to 10 (LT5). Lower is better. */
    public int rank() {
        return ordinal() + 1;
    }

    /** The tier number, 1 to 5. HT3 and LT3 both return 3. */
    public int level() {
        return ordinal() / 2 + 1;
    }

    /** {@code true} for High tiers (HT1 to HT5). */
    public boolean isHigh() {
        return ordinal() % 2 == 0;
    }

    /** {@code true} if this tier ranks above {@code other}. */
    public boolean isBetterThan(Tier other) {
        return compareTo(other) < 0;
    }

    /**
     * Parses a tier name, ignoring case and surrounding spaces ({@code "ht3"} gives HT3).
     *
     * @return the tier, or empty if {@code value} is null or not a tier
     */
    public static Optional<Tier> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

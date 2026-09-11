package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Rolls a guard's name, gender and personality from its UUID.
 *
 * <p><b>Why a bundled list at all.</b> A bridged guard's real name now comes from the hidden MCA
 * proxy villager, rolled by MCA itself. These lists are the seed and the fallback: they name a
 * guard before its proxy exists and whenever MCA is unreachable, so an identity is never nameless
 * and never depends on MCA's own resources being where we last saw them.
 *
 * <p>Everything here is derived from the UUID alone, so a guard whose identity was never stored --
 * one created before this feature existed, or after a data file was lost -- regenerates exactly the
 * same name, gender and personality. The rolled values are still persisted, so a later edit to
 * these lists cannot rename a guard somebody already knows.
 *
 * <p>No Minecraft types are touched, deliberately: this class and {@link GuardIdentityData} are
 * unit-testable without bootstrapping the game.
 */
final class GuardNames {

    /** Salts, so the three rolls are independent draws rather than the same stream three times. */
    private static final long GENDER_SALT = 0x9E3779B97F4A7C15L;
    private static final long NAME_SALT = 0xC2B2AE3D27D4EB4FL;
    private static final long PERSONALITY_SALT = 0x165667B19E3779F9L;

    private static final List<String> MALE_NAMES = List.of(
            "Alden", "Bertram", "Cedric", "Dorian", "Edmund", "Fenwick", "Godfrey", "Hollis",
            "Ivor", "Jarrod", "Kellan", "Lowell", "Merrick", "Norwood", "Osric", "Percival",
            "Quillon", "Roderick", "Selwyn", "Tobias", "Ulric", "Verner", "Wystan", "Yorick");

    private static final List<String> FEMALE_NAMES = List.of(
            "Adelie", "Beatrix", "Clarimond", "Dervla", "Elspeth", "Fenella", "Gisela", "Hesper",
            "Isolde", "Jocelyn", "Katriona", "Linnet", "Maribel", "Nerissa", "Ottoline", "Perrine",
            "Quenilda", "Rowena", "Sabine", "Thomasin", "Ursel", "Verity", "Winnifred", "Ysolda");

    /**
     * MCA's rollable personalities, lowercase and unnamespaced, in MCA's own registration order.
     *
     * <p>Matched against what the sibling addons expect -- this is the set MCAConversations treats
     * as canonical -- because the rolled value is persisted, so disagreeing here would leave guards
     * carrying a personality no dialogue pack has lines for. {@code unassigned} is excluded: it is
     * MCA's parse-failure sentinel, never rolled onto anybody.
     *
     * <p>Guards rolled before this roster was corrected keep whatever id they were given: the value
     * is a free string in {@link GuardIdentityData} and nothing here migrates it. Display falls back
     * for an unknown id rather than failing, so an old guard still reads correctly.
     */
    private static final List<String> PERSONALITIES = List.of(
            "athletic", "confident", "friendly", "flirty", "witty", "shy", "gloomy", "sensitive",
            "greedy", "odd", "lazy", "grumpy", "peppy");

    private GuardNames() {
    }

    /** A 50/50 gender roll for this UUID. */
    static GuardGender gender(UUID uuid) {
        return rollFor(uuid, GENDER_SALT).nextBoolean() ? GuardGender.MALE : GuardGender.FEMALE;
    }

    /** A name from the list for this gender. */
    static String name(UUID uuid, GuardGender gender) {
        List<String> pool = gender == GuardGender.FEMALE ? FEMALE_NAMES : MALE_NAMES;
        return pool.get(rollFor(uuid, NAME_SALT).nextInt(pool.size()));
    }

    /** A personality id for this UUID. */
    static String personality(UUID uuid) {
        return PERSONALITIES.get(rollFor(uuid, PERSONALITY_SALT).nextInt(PERSONALITIES.size()));
    }

    /**
     * A name drawn from a weighted pool, deterministically for this UUID.
     *
     * <p>Exists for a caller holding a weighted name pool read from data. The walk is over
     * cumulative weights, so a name weighted 3600 really is thirty-six times as likely as one
     * weighted 100, and it is driven by the same UUID-seeded stream the bundled
     * roll uses, so a guard's name is still a pure function of its UUID and the pool it was drawn
     * from. Empty pool, mismatched lists or a total weight of zero all return empty rather than
     * guessing; the caller falls back to the bundled list.
     */
    static Optional<String> weightedName(UUID uuid, List<String> names, List<Integer> weights) {
        if (names == null || weights == null || names.isEmpty() || names.size() != weights.size()) {
            return Optional.empty();
        }
        long total = 0L;
        for (Integer weight : weights) {
            if (weight != null && weight > 0) {
                total += weight;
            }
        }
        if (total <= 0L) {
            return Optional.empty();
        }

        long roll = Math.floorMod(rollFor(uuid, NAME_SALT).nextLong(), total);
        for (int i = 0; i < names.size(); i++) {
            Integer weight = weights.get(i);
            if (weight == null || weight <= 0) {
                continue;
            }
            roll -= weight;
            if (roll < 0L) {
                return Optional.ofNullable(names.get(i));
            }
        }
        return Optional.ofNullable(names.get(names.size() - 1));
    }

    /** The personality roster, for tests and command output. */
    static List<String> personalities() {
        return PERSONALITIES;
    }

    private static Random rollFor(UUID uuid, long salt) {
        return new Random(uuid.getMostSignificantBits() * 31L + uuid.getLeastSignificantBits() + salt);
    }
}

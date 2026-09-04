package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Rolls a guard's name, gender and personality from its UUID.
 *
 * <p><b>Why a bundled list and not MCA's own name files.</b> This mod may not name an MCA class or
 * read an MCA resource path; the path its name lists live at is not part of any contract and has
 * moved before. A local list costs a little variety and buys an identity that cannot break when MCA
 * reshuffles its resources.
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
     */
    private static final List<String> PERSONALITIES = List.of(
            "confident", "peppy", "friendly", "flirty", "playful", "gloomy", "sensitive", "greedy",
            "odd", "crabby", "extroverted", "introverted", "relaxed", "anxious", "peaceful", "upbeat");

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

    /** The personality roster, for tests and command output. */
    static List<String> personalities() {
        return PERSONALITIES;
    }

    private static Random rollFor(UUID uuid, long salt) {
        return new Random(uuid.getMostSignificantBits() * 31L + uuid.getLeastSignificantBits() + salt);
    }
}

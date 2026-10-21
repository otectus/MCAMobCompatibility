package dev.otectus.mcamobcompat.character;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Decides what an MCA editor save may change on a bridged character, before MCA applies it.
 *
 * <p>MCA's editor sends the whole villager it previewed: on MCA 7.6 a full save of the preview, which
 * MCA reads straight back into the villager -- inventory, memories, residency, every flag; on 7.7 a
 * patch that MCA merges through its own allowlist. For a bridged character neither is enough: the
 * person is also employed, so its age, its profession and its proxy state are not the editor's to set,
 * and who may change what depends on this server's policy. So the payload is rebuilt here, on both
 * versions, as <em>the identity's current saved state, with only the permitted fields taken from the
 * client</em>. MCA then applies that, through its own code, and anything else it carried is simply
 * the value the person already had.
 *
 * <p>Two tiers. <b>Cosmetic</b> fields -- name, gender, genes, skin, hair, clothes, colours and dyes --
 * are open to anyone the editor permission admits. <b>Administrative</b> fields -- age, traits,
 * personality, mood, memories, infection and family links -- need the administrative permission
 * level, because owning a soldier is not a licence to rewrite who its parents are.
 *
 * <p>Pure NBT, so it is unit-tested without Minecraft.
 */
public final class EditorPatchPolicy {

    /** The largest editor payload accepted for a bridged character: four times a full MCA villager. */
    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    /** The longest text value accepted for a cosmetic field: resource ids, names, skin owners. */
    static final int MAX_TEXT = 256;

    /** Cosmetic keys, matched case-insensitively: MCA 7.6 writes lower-case keys, 7.7 capitalised ones. */
    private static final Set<String> COSMETIC = Set.of(
            "villagername", "customname", "customnamevisible", "custom_skin",
            "gender", "clothes", "clothinglocked", "hair", "skin", "hairstyle", "hairbase", "hairbangs",
            "hairback", "hairfront", "hairextra", "skincolor", "haircolor", "eyecolor", "eyecolorleft",
            "playermodel", "hairdye", "skindye", "eyedye", "eyeleftdye",
            "hair_color_red", "hair_color_green", "hair_color_blue");

    /** Administrative keys, matched case-insensitively, plus the family-link keys of both versions. */
    private static final Set<String> ADMINISTRATIVE = Set.of(
            "age", "agestate", "growthamount", "traits", "personality", "mood", "memories",
            "infectionprogress", "villagerdatafinalized",
            "familytreenewfathername", "familytreenewmothername", "familytreenewspousename");

    /** Keys that only parameterise a command, never data: kept as the client sent them. */
    private static final Set<String> PARAMETERS = Set.of("offset");

    /** Keys that decide whether the person is a child. */
    private static final Set<String> AGE_KEYS = Set.of("age", "agestate", "growthamount");

    private EditorPatchPolicy() {
    }

    /** The outcome: apply {@code payload} as rebuilt, or refuse with a translatable reason. */
    public record Decision(boolean allowed, String reasonKey, int changedCosmetic, int changedAdministrative) {

        static Decision refuse(String reasonKey) {
            return new Decision(false, reasonKey, 0, 0);
        }
    }

    /** Whether a key is one the editor may change at all, at the given tier. */
    public static boolean permitted(String key, boolean administrator) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (COSMETIC.contains(lower) || isGene(lower)) {
            return true;
        }
        return administrator && isAdministrative(lower);
    }

    /** MCA 7.6 names genes {@code gene_size}; 7.7 names them {@code GeneSize}. "gender" is not a gene. */
    private static boolean isGene(String lower) {
        return lower.startsWith("gene_") || (lower.startsWith("gene") && !lower.equals("gender"));
    }

    private static boolean isAdministrative(String lower) {
        return ADMINISTRATIVE.contains(lower) || (lower.startsWith("tree_") && lower.endsWith("_new"));
    }

    /**
     * Rebuilds {@code payload} in place as {@code current} plus the permitted, valid client values, and
     * says whether the save may proceed.
     *
     * @param command        the editor command: {@code sync}, {@code hair}, {@code clothing}, ...
     * @param payload        the request's live tag; rewritten in place when the save is allowed
     * @param current        the identity's own saved state now ({@code addAdditionalSaveData})
     * @param administrator  whether the player holds the administrative permission level
     */
    public static Decision rebuild(String command, CompoundTag payload, CompoundTag current, boolean administrator) {
        if (command == null || payload == null || current == null) {
            return Decision.refuse("message.mcamobcompat.editor.invalid");
        }
        if ("profession".equals(command)) {
            return Decision.refuse("message.mcamobcompat.editor.profession");
        }
        if (payload.sizeInBytes() > MAX_PAYLOAD_BYTES) {
            return Decision.refuse("message.mcamobcompat.editor.too_large");
        }
        CompoundTag rebuilt = current.copy();
        int cosmetic = 0;
        int administrative = 0;
        for (String key : payload.getAllKeys()) {
            Tag value = payload.get(key);
            String lower = key.toLowerCase(Locale.ROOT);
            if (PARAMETERS.contains(lower)) {
                rebuilt.put(key, value.copy());
                continue;
            }
            if (!permitted(key, administrator) || Objects.equals(value, current.get(key))) {
                continue;
            }
            if (!valid(lower, value)) {
                return Decision.refuse("message.mcamobcompat.editor.invalid");
            }
            if (AGE_KEYS.contains(lower) && makesChild(lower, value)) {
                // An employed person may not be made a child: the native role is a paid adult's, and
                // the person goes back to civilian life first.
                return Decision.refuse("message.mcamobcompat.editor.age");
            }
            rebuilt.put(key, value.copy());
            if (isAdministrative(lower)) {
                administrative++;
            } else {
                cosmetic++;
            }
        }
        for (String key : Set.copyOf(payload.getAllKeys())) {
            payload.remove(key);
        }
        for (String key : rebuilt.getAllKeys()) {
            payload.put(key, rebuilt.get(key).copy());
        }
        return new Decision(true, "", cosmetic, administrative);
    }

    /**
     * Whether a changed age value would make the person a child: a negative vanilla age, any change of
     * MCA's age state (an employed person is an adult, and MCA's own states below adult are children
     * of some age), or any change of MCA's growth counter, which only children use.
     */
    static boolean makesChild(String lower, Tag value) {
        if (lower.equals("age")) {
            return !(value instanceof NumericTag number) || number.getAsInt() < 0;
        }
        return true;
    }

    /** Bounds for one permitted value: finite numbers, genes between zero and one, short text. */
    static boolean valid(String lower, Tag value) {
        if (value instanceof NumericTag number) {
            double v = number.getAsDouble();
            if (!Double.isFinite(v)) {
                return false;
            }
            if (isGene(lower) || lower.startsWith("hair_color_")) {
                return v >= 0.0D && v <= 1.0D;
            }
            return true;
        }
        if (value instanceof StringTag text) {
            return text.getAsString().length() <= MAX_TEXT;
        }
        // Compound values (traits, memories) are administrative and MCA reads them itself.
        return value.getId() == Tag.TAG_COMPOUND || value.getId() == Tag.TAG_LIST;
    }
}

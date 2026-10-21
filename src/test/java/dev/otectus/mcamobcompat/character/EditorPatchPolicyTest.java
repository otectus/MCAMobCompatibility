package dev.otectus.mcamobcompat.character;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what MCA's editor may change on a bridged character, on both MCA payload shapes: the full save
 * of MCA 7.6 (lower-case keys) and the patch of 7.7 (capitalised keys). The payload is always rebuilt
 * as the identity's own state plus permitted fields, so nothing else can be written through it.
 */
class EditorPatchPolicyTest {

    private static CompoundTag current() {
        CompoundTag tag = new CompoundTag();
        tag.putString("villagerName", "Ada");
        tag.putString("clothes", "mca:skins/clothing/normal/female/farmer/0");
        tag.putFloat("gene_size", 0.5F);
        tag.putInt("Age", 0);
        tag.putInt("ageState", 5);
        tag.put("Inventory", new ListTag());
        tag.putBoolean("NoAI", true);
        CompoundTag memories = new CompoundTag();
        memories.putInt("hearts", 10);
        tag.put("memories", memories);
        tag.putString("personality", "friendly");
        return tag;
    }

    @Test
    void cosmeticFieldsPassAndEverythingElseIsTheIdentitysOwn() {
        CompoundTag payload = current();
        payload.putString("clothes", "mca:skins/clothing/normal/female/guard/1");
        payload.putFloat("gene_size", 0.8F);
        payload.putBoolean("NoAI", false);
        ListTag inventory = new ListTag();
        inventory.add(new CompoundTag());
        payload.put("Inventory", inventory);
        payload.putString("personality", "grumpy");
        EditorPatchPolicy.Decision decision = EditorPatchPolicy.rebuild("sync", payload, current(), false);
        assertTrue(decision.allowed());
        assertEquals("mca:skins/clothing/normal/female/guard/1", payload.getString("clothes"));
        assertEquals(0.8F, payload.getFloat("gene_size"));
        assertTrue(payload.getBoolean("NoAI"), "The proxy flag was writable through the editor.");
        assertTrue(payload.getList("Inventory", 10).isEmpty(), "The inventory was writable through the editor.");
        assertEquals("friendly", payload.getString("personality"), "A non-operator changed the personality.");
        assertEquals(2, decision.changedCosmetic());
        assertEquals(0, decision.changedAdministrative());
    }

    @Test
    void administrativeFieldsNeedTheAdministrativeLevel() {
        CompoundTag payload = current();
        payload.putString("personality", "grumpy");
        CompoundTag memories = new CompoundTag();
        memories.putInt("hearts", 9999);
        payload.put("memories", memories);
        assertTrue(EditorPatchPolicy.rebuild("sync", payload, current(), true).allowed());
        assertEquals("grumpy", payload.getString("personality"));
        assertEquals(9999, payload.getCompound("memories").getInt("hearts"));
    }

    @Test
    void anEmployedPersonCannotBeMadeAChild() {
        CompoundTag age = current();
        age.putInt("Age", -24000);
        assertEquals("message.mcamobcompat.editor.age",
                EditorPatchPolicy.rebuild("sync", age, current(), true).reasonKey());
        CompoundTag state = current();
        state.putInt("ageState", 3);
        assertFalse(EditorPatchPolicy.rebuild("sync", state, current(), true).allowed());
        CompoundTag cooldown = current();
        cooldown.putInt("Age", 1200);
        assertTrue(EditorPatchPolicy.rebuild("sync", cooldown, current(), true).allowed(),
                "An adult's breeding cooldown is not a change of age.");
    }

    @Test
    void professionEditsWaitForCivilianLife() {
        CompoundTag payload = new CompoundTag();
        payload.putString("profession", "minecraft:farmer");
        assertEquals("message.mcamobcompat.editor.profession",
                EditorPatchPolicy.rebuild("profession", payload, current(), true).reasonKey());
    }

    @Test
    void valuesAreBounded() {
        CompoundTag gene = current();
        gene.putFloat("gene_size", Float.NaN);
        assertFalse(EditorPatchPolicy.rebuild("sync", gene, current(), false).allowed());
        CompoundTag huge = current();
        huge.putFloat("gene_size", 4.0F);
        assertFalse(EditorPatchPolicy.rebuild("sync", huge, current(), false).allowed());
        CompoundTag text = current();
        text.putString("clothes", "x".repeat(EditorPatchPolicy.MAX_TEXT + 1));
        assertFalse(EditorPatchPolicy.rebuild("sync", text, current(), false).allowed());
    }

    @Test
    void theSevenSevenShapeIsCoveredToo() {
        assertTrue(EditorPatchPolicy.permitted("Clothes", false));
        assertTrue(EditorPatchPolicy.permitted("GeneSize", false));
        assertTrue(EditorPatchPolicy.permitted("Gender", false));
        assertTrue(EditorPatchPolicy.permitted("HairDye", false));
        assertFalse(EditorPatchPolicy.permitted("Memories", false));
        assertTrue(EditorPatchPolicy.permitted("Memories", true));
        assertFalse(EditorPatchPolicy.permitted("FamilyTreeNewFatherName", false));
        assertTrue(EditorPatchPolicy.permitted("tree_father_new", true));
        assertFalse(EditorPatchPolicy.permitted("UUID", true), "An entity UUID is never editable.");
        assertFalse(EditorPatchPolicy.permitted("HandItems", true), "Equipment is never editable.");
    }

    @Test
    void aCommandParameterIsKept() {
        CompoundTag payload = new CompoundTag();
        payload.putInt("offset", 1);
        assertTrue(EditorPatchPolicy.rebuild("hair", payload, current(), false).allowed());
        assertEquals(1, payload.getInt("offset"));
        assertEquals("Ada", payload.getString("villagerName"), "The rebuilt payload lost the identity's own state.");
    }
}

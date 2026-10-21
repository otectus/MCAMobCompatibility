package dev.otectus.mcamobcompat.hook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injection that found nothing must never pass for support: a hook is armed only when the plugin
 * counted its handlers' call sites in the transformed class, every handler it needs at least once.
 */
class HookPluginTest {

    @AfterEach
    void forget() {
        Hooks.resetForTest();
    }

    /** A target class as Mixin leaves it: its own method, now calling the merged handlers. */
    private static ClassNode transformed(String owner, String... handlers) {
        ClassNode node = new ClassNode();
        node.name = owner;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "receive", "()V", null, null);
        for (String handler : handlers) {
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "handler$zza000$" + handler, "()V", false));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return node;
    }

    @Test
    void handlersAreCountedOnlyWhereTheyLanded() {
        Map<String, Integer> counts = HookPlugin.countHandlers(transformed("a/Target",
                "mcamobcompat$editorHead", "mcamobcompat$editorReturn", "mcamobcompat$editorReturn", "other$handler"));
        assertEquals(1, counts.get("mcamobcompat$editorHead"));
        assertEquals(2, counts.get("mcamobcompat$editorReturn"));
        assertEquals(2, counts.size(), "A foreign handler was counted as this mod's.");
    }

    @Test
    void aHookIsArmedOnlyWithEveryHandlerItNeeds() {
        assertFalse(Hooks.armed(Hooks.MCA_EDITOR_SAVE), "Nothing has landed yet.");
        Hooks.recordInjections("mcamobcompat$editorHead", "a.Target", 1);
        assertFalse(Hooks.armed(Hooks.MCA_EDITOR_SAVE), "The head alone would apply saves it could not finish.");
        Hooks.recordInjections("mcamobcompat$editorReturn", "a.Target", 1);
        assertTrue(Hooks.armed(Hooks.MCA_EDITOR_SAVE));
        assertFalse(Hooks.armed(Hooks.RECRUITS_HIRE_FROM_VILLAGER), "One hook armed another.");
    }

    @Test
    void zeroCallSitesNeverArm() {
        Hooks.recordInjections("mcamobcompat$hireFromVillager", "b.Target", 0);
        assertFalse(Hooks.armed(Hooks.RECRUITS_HIRE_FROM_VILLAGER));
    }

    @Test
    void anAbsentTargetIsSkippedOnlyWhenTheOwnerSaysSo() {
        assertTrue(HookPlugin.applies(null), "An undecidable target must apply, as without the plugin.");
        assertTrue(HookPlugin.applies(Boolean.TRUE));
        assertFalse(HookPlugin.applies(Boolean.FALSE));
    }
}

package dev.otectus.mcamobcompat.hook;

import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Mixin config plugin for this mod's optional hooks.
 *
 * <p>Two jobs. <b>Before</b>: a hook names its targets as strings, some of which cannot all exist at
 * once (MCA's two package roots) and some of which belong to mods that are not installed. Mixin would
 * look each up and log a warning per miss, indistinguishable in a report from a broken hook; so a
 * target the owning mod's jar does not contain is skipped here, read from the jar's file listing
 * through {@link LoadingModList} without loading anything. The owning mod is known from which hook
 * class asks, so no foreign package name is written here. Anything this plugin cannot decide applies,
 * as it would without a plugin.
 *
 * <p><b>After</b>: an injection that found nothing applies silently, and that must never pass for
 * support. Once a hook is applied to a class, the injected call sites are counted in the transformed
 * bytecode and recorded in {@link Hooks}, which the features that depend on them consult.
 *
 * <p>Deliberately outside the mixin package: a config plugin is constructed before its own
 * configuration's package is processed, and a mixin package may hold nothing but mixins.
 */
public final class HookPlugin implements IMixinConfigPlugin {

    /** Hook class simple name to the mod whose jar must contain its target. */
    private static final Map<String, String> OWNERS = Map.of(
            "McaEditorSaveHook", "mca",
            "RecruitsConversionHook", "recruits");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String owner = OWNERS.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
        return owner == null || applies(present(owner, targetClassName));
    }

    /** Only a definite "not here" skips a target; "cannot tell" applies it. */
    static boolean applies(Boolean present) {
        return present == null || present;
    }

    /** Whether the owning mod's jar contains the class; null when that cannot be determined. */
    static Boolean present(String ownerModId, String targetClassName) {
        try {
            LoadingModList mods = LoadingModList.get();
            if (mods == null) {
                return null;
            }
            ModFileInfo file = mods.getModFileById(ownerModId);
            if (file == null) {
                return Boolean.FALSE;
            }
            return Files.exists(file.getFile().findResource(targetClassName.replace('.', '/') + ".class"));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        for (Map.Entry<String, Integer> found : countHandlers(targetClass).entrySet()) {
            Hooks.recordInjections(found.getKey(), targetClassName, found.getValue());
        }
    }

    /**
     * How many calls to each of this mod's handlers the transformed class contains. Mixin renames a
     * merged handler but keeps its original name inside the new one, so a fragment match is exact
     * enough: the fragments are this mod's own and prefixed with its id.
     */
    static Map<String, Integer> countHandlers(ClassNode targetClass) {
        Map<String, Integer> counts = new HashMap<>();
        if (targetClass == null || targetClass.methods == null) {
            return counts;
        }
        for (MethodNode method : targetClass.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(targetClass.name)) {
                    for (String handler : Hooks.HANDLERS.keySet()) {
                        if (call.name.contains(handler)) {
                            counts.merge(handler, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return counts;
    }
}

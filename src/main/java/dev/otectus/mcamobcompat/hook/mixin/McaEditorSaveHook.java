package dev.otectus.mcamobcompat.hook.mixin;

import dev.otectus.mcamobcompat.character.McaEditorBridge;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The editor-save hook: MCA's own editor save, bracketed so a bridged character's edits are checked
 * and rebuilt before MCA applies them (see {@code character.McaEditorBridge}).
 *
 * <p><b>Why a hook at all.</b> MCA applies an editor save to whatever entity the client names, with no
 * check of who sent it, and it posts no event first. Refusing afterwards would be too late -- the save
 * would already have rewritten the person. Every other request MCA receives passes straight through:
 * {@code beforeSave} answers "go ahead" for anything that is not a bridged character.
 *
 * <p><b>Three targets, one class on both loaders.</b> MCA's Forge classes moved from
 * {@code forge.net.mca} to {@code forge.net.conczin.mca} in 7.7.1-alpha.1, and its NeoForge builds use
 * {@code net.conczin.mca}, where the request is a record handled by {@code handleServer} rather than
 * {@code receive}. Exactly one target exists in any MCA jar, and {@code hook.HookPlugin} skips the
 * others before Mixin looks them up; the method selector that names nothing in the class found simply
 * matches nothing. {@code remap = false}: these are MCA's own names. {@code require = 0} (the config
 * default) means a reshaped MCA simply gets no injection -- which {@code HookPlugin} counts, so the
 * editor is then refused on bridged characters rather than left unchecked.
 *
 * <p>This class holds MCA's dotted package names and is the exact entry the build's foreign-name
 * scans allow it in; it names no MCA type.
 */
@Pseudo
@Mixin(targets = {
        "forge.net.mca.network.c2s.VillagerEditorSyncRequest",
        "forge.net.conczin.mca.network.c2s.VillagerEditorSyncRequest",
        "net.conczin.mca.network.c2s.VillagerEditorSyncRequest"
}, remap = false)
public abstract class McaEditorSaveHook {

    @Inject(method = {"receive(Lnet/minecraft/server/level/ServerPlayer;)V",
            "handleServer(Lnet/minecraft/server/level/ServerPlayer;)V"}, at = @At("HEAD"), cancellable = true,
            remap = false, require = 0)
    private void mcamobcompat$editorHead(ServerPlayer player, CallbackInfo callback) {
        if (!McaEditorBridge.beforeSave(this, player)) {
            callback.cancel();
        }
    }

    @Inject(method = {"receive(Lnet/minecraft/server/level/ServerPlayer;)V",
            "handleServer(Lnet/minecraft/server/level/ServerPlayer;)V"}, at = @At("RETURN"), remap = false,
            require = 0)
    private void mcamobcompat$editorReturn(ServerPlayer player, CallbackInfo callback) {
        McaEditorBridge.afterSave(this, player);
    }
}

package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * Drops the classification cache whenever the tags behind it change.
 *
 * <p>{@code TagsUpdatedEvent} is the right hook rather than {@code AddReloadListenerEvent}: tags are
 * precisely what is cached, and this fires after they are bound, on world load and on every
 * {@code /reload}. It also means the empty answers computed before any tags existed are thrown away
 * rather than becoming permanent.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ClassificationReloadHandler {

    private ClassificationReloadHandler() {
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        int dropped = ClassificationCache.size();
        ClassificationCache.invalidate();
        McaMobCompat.LOGGER.debug("Tags updated; dropped {} cached entity classification(s).", dropped);
    }
}

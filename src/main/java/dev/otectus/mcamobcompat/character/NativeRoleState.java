package dev.otectus.mcamobcompat.character;

import java.util.Optional;
import java.util.UUID;

/**
 * What the native mod says an actor is doing, read from its own state and never simulated: the role
 * (the entity type's path, such as {@code bowman}), the owner when the native mod has one, and an
 * activity label key for the role line.
 *
 * @param role        the native role id, the entity type path
 * @param owner       the native owner, or null for an unowned actor
 * @param activityKey a translation key for what the actor is doing, or empty when unknown
 * @param working     whether the native mod reports the actor at work
 */
public record NativeRoleState(String role, UUID owner, String activityKey, boolean working) {

    public static NativeRoleState of(String role) {
        return new NativeRoleState(role, null, "", false);
    }

    public Optional<UUID> ownerUuid() {
        return Optional.ofNullable(owner);
    }
}

# Changes requested upstream

This file records what other mods would need to change for MCA: Mob Compatibility to do more than
it can from outside them. Each entry states the evidence, the smallest change that would work, and
what this mod does until then. Nothing here has been sent to the other mod's authors yet. The Forge
1.20.1 build keeps the same record for SWEM 1.6.6.

## SWEM: let a non-player rider steer

**Mod:** SWEM (Star Worm Equestrian Mod), `swem-1.21.1-1.6.12.jar` (CurseForge file 8557159),
NeoForge 1.21.1.

**Symptom.** An MCA villager told to ride a saddled SWEM horse mounts it, and the horse ignores it:
MCA's orders never reach the horse, which goes on under its own AI as if nobody were on it. The
same villager on a vanilla horse rides it wherever MCA sends it.

**Why, from the 1.6.12 bytecode.** `com.alaharranhonor.swem.entity.horse.AbstractSwemHorse` answers
three vanilla questions for a player rider only:

```java
// getControllingPassenger(): only a Player can control the horse.
List<Player> players = getPassengers().stream()
        .filter(e -> e instanceof Player).map(Player.class::cast).toList();
return players.isEmpty() ? null : players.get(0);

// isVehicle(): only a Player first passenger counts as a rider at all.
return super.isVehicle() && getFirstPassenger() instanceof Player;

// isControlledByLocalInstance(): a player rider steers; anything else falls through.
if ((hasBridle() || !ServerConfig.NEEDS_BRIDLE_TO_STEER.get()) && hasSaddle()
        && getControllingPassenger() instanceof Player player) {
    return player.isLocalPlayer();
}
return !isVehicle() && isEffectiveAi();
```

With a villager aboard, the horse has no controlling passenger and does not count as ridden, so
`isControlledByLocalInstance()` falls through to `isEffectiveAi()`: the horse runs its own AI exactly
as if it were unridden, and carries the villager wherever that takes it. A mount is steered by its
controlling passenger, and a vanilla horse names an AI-driven mob on it as that:

```java
// AbstractHorse.getControllingPassenger(), Minecraft 1.21.1
if (isSaddled() && getFirstPassenger() instanceof Player player) return player;
return super.getControllingPassenger();   // Mob: an AI-driven Mob first passenger that may control a vehicle
```

MCA 7.7 steers through that: it leaves vanilla's hand-off in place, and `Mob.getNavigation()` returns
the mount's navigation, rather than the rider's own, when the mount names the rider its controlling
passenger. A SWEM horse never names the villager, so the villager's walk orders never reach it.

**Observed.** The GameTest `aVillagerCannotSteerASwemHorseButCanAVanillaOne` seats the same MCA
villager on a vanilla horse and on a SWEM 1.6.12 horse. The vanilla horse names the villager its
controlling passenger and, sent somewhere, walks more than two blocks with it aboard. The SWEM horse
names no controlling passenger. An earlier form of the test drove the SWEM horse's own navigation
and checked that it stood still, which it usually did; with every optional mod loaded it sometimes
walked, rider aboard, because with a villager on it the horse is under its own control.

**The smallest change that would work.** In `AbstractSwemHorse`, give the vanilla answer when the
first passenger is a non-player `Mob`, and keep every player rule as it is:

```java
@Override
public LivingEntity getControllingPassenger() {
    if (getFirstPassenger() instanceof Mob mob) {
        return mob;                       // an NPC rider steers, as on a vanilla horse
    }
    // ... the existing player-only logic, unchanged
}

@Override
public boolean isVehicle() {
    if (getFirstPassenger() instanceof Mob) {
        return true;                      // a mob rider is a rider, so SWEM's own AI stands down
    }
    // ... the existing logic, unchanged
}

@Override
public boolean isControlledByLocalInstance() {
    if (getFirstPassenger() instanceof Mob) {
        return isEffectiveAi();           // server-side, as for any AI-driven entity
    }
    // ... the existing logic, unchanged
}
```

SWEM might prefer to gate this, for example on an owner's permission, on a server config option, or
on a tag listing the rider types it accepts. Any of those works for MCA, as long as an accepted
non-player rider gets the three answers above.

**What MCA: Mob Compatibility does until then.** `swem:swem_horse` is in the
`mcamobcompat:player_only_mounts` tag, so with `[mounts] npcRidingPolicy = REFUSE` (the default) the
horse refuses an MCA villager and the player who ordered the ride is told why. `ALLOW_INERT` seats the
villager anyway, which is the behaviour before 0.2.1. If SWEM ships the change, removing the entry from
that tag is the only change needed here.

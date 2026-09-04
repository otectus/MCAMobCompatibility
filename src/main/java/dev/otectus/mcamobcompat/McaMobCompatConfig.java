package dev.otectus.mcamobcompat;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Configuration for MCA: Mob Compatibility.
 *
 * <p>Two specs. <b>COMMON</b> holds what has to be known before a world exists -- which integration
 * modules are live at all, and debug logging. <b>SERVER</b> holds gameplay, because all of it is
 * server-authoritative and it belongs to the world rather than to the installation: a server owner
 * decides whether guards defend MCA villagers, not each connecting client.
 *
 * <p>There is no client spec. Nothing in 0.1.0 runs client-side.
 *
 * <p>The option list is kept deliberately short. Classification itself is extended with datapack
 * tags, not with config lists, so there is no per-entity toggle here and there should never be one.
 */
public final class McaMobCompatConfig {

    public static final Common COMMON;
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Server SERVER;
    public static final ForgeConfigSpec SERVER_SPEC;

    static {
        final Pair<Common, ForgeConfigSpec> common = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        COMMON_SPEC = common.getRight();

        final Pair<Server, ForgeConfigSpec> server = new ForgeConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();
    }

    private McaMobCompatConfig() {
    }

    /** Startup-scoped settings: which modules exist, and how loudly. */
    public static final class Common {

        public final ForgeConfigSpec.BooleanValue debugLogging;

        public final ForgeConfigSpec.BooleanValue guardVillagersModule;
        public final ForgeConfigSpec.BooleanValue swemModule;
        public final ForgeConfigSpec.BooleanValue geneticAnimalsModule;
        public final ForgeConfigSpec.BooleanValue dragnLivestockModule;

        Common(ForgeConfigSpec.Builder builder) {
            builder.comment("General settings.").push("general");
            debugLogging = builder
                    .comment("Log every classification decision and integration detail.",
                            "Verbose by design -- for diagnosing why a mob is not being recognised.",
                            "The /mcamobcompat command gives the same information without the log spam.")
                    .define("debugLogging", false);
            builder.pop();

            builder.comment("Per-mod integration modules.",
                            "Turning one off makes this mod completely blind to that mod's entities:",
                            "they stop being classified, so nothing here treats them as livestock,",
                            "mounts or guards. Use it when another mod already handles the same",
                            "integration and the two are fighting.",
                            "A module for a mod that is not installed is skipped regardless.")
                    .push("modules");
            guardVillagersModule = builder
                    .comment("Guard Villagers. NOTE: turning this off also disables the block on the",
                            "villager-to-guard conversion, which permanently destroys an MCA",
                            "villager's family, marriage and genetics data. Leave it on.")
                    .define("guardVillagers", true);
            swemModule = builder.define("swem", true);
            geneticAnimalsModule = builder
                    .comment("Genetic Animals (mod id 'eanimod').")
                    .define("geneticAnimals", true);
            dragnLivestockModule = builder
                    .comment("DragN's Livestock Overhaul.")
                    .define("dragnLivestock", true);
            builder.pop();
        }
    }

    /** World-scoped gameplay settings. */
    public static final class Server {

        public final ForgeConfigSpec.BooleanValue blockGuardConversion;
        public final ForgeConfigSpec.BooleanValue protectMcaVillagers;
        public final ForgeConfigSpec.BooleanValue preventGuardFriendlyFire;
        public final ForgeConfigSpec.BooleanValue retaliateAgainstPlayers;
        public final ForgeConfigSpec.IntValue guardHelpRange;
        public final ForgeConfigSpec.IntValue maxGuardsAlerted;
        public final ForgeConfigSpec.BooleanValue treatGuardsAsMcaGuards;
        public final ForgeConfigSpec.IntValue guardHeartsLostPerHit;

        public final ForgeConfigSpec.BooleanValue protectOwnedAnimals;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> protectedRoles;
        public final ForgeConfigSpec.BooleanValue protectNamedAnimals;
        public final ForgeConfigSpec.BooleanValue protectLeashedAnimals;
        public final ForgeConfigSpec.BooleanValue abandonHuntOnProtectedTarget;

        public final ForgeConfigSpec.BooleanValue swemRecognizeAsMounts;
        public final ForgeConfigSpec.BooleanValue geneticAnimalsRecognizeLivestock;
        public final ForgeConfigSpec.BooleanValue dragnLivestockRecognizeLivestock;

        Server(ForgeConfigSpec.Builder builder) {
            builder.comment("Guard Villagers interaction.").push("guard_villagers");
            blockGuardConversion = builder
                    .comment("Block the crouch-and-right-click conversion of an MCA villager into a guard.",
                            "",
                            "SAFETY CRITICAL, and the main reason this mod exists. Guard Villagers gates",
                            "its conversion on 'instanceof Villager', which MCA villagers pass, and its",
                            "default convertible professions include 'none' -- so any jobless MCA villager",
                            "is convertible out of the box. The conversion copies across only position,",
                            "custom name and held item, then discards the villager: family tree, marriage,",
                            "children, genetics, personality and inventory are gone, and cannot be restored",
                            "because none of it is recoverable from the resulting guard.",
                            "",
                            "While this is on, crouch-right-clicking an MCA villager WHILE HOLDING a sword",
                            "or crossbow does nothing at all -- MCA's own interaction screen will not open",
                            "for that one gesture either. Every other way of interacting is untouched.")
                    .define("blockGuardConversion", true);
            protectMcaVillagers = builder
                    .comment("When a hostile mob attacks or targets an MCA villager, alert nearby guards.",
                            "Guard Villagers matches the mobs it protects by entity id, and its default",
                            "list names 'minecraft:villager' -- which MCA villagers are not -- so without",
                            "this they simply do not defend them.")
                    .define("protectMcaVillagers", true);
            preventGuardFriendlyFire = builder
                    .comment("Stop guards from targeting MCA villagers.",
                            "The do-not-attack list in Guard Villagers is also matched by entity id and",
                            "also names only 'minecraft:villager', so an MCA villager is not on it.")
                    .define("preventGuardFriendlyFire", true);
            retaliateAgainstPlayers = builder
                    .comment("Also alert guards when a PLAYER attacks an MCA villager.",
                            "Off by default: MCA has its own reputation and guard response for player",
                            "misbehaviour, and turning a village hostile is a large consequence to add",
                            "silently. Turn it on for a harsher server.")
                    .define("retaliateAgainstPlayers", false);
            guardHelpRange = builder
                    .comment("How far, in blocks, to look for guards to alert.")
                    .defineInRange("guardHelpRange", 32, 1, 128);
            maxGuardsAlerted = builder
                    .comment("Cap on how many guards a single attack alerts, so a large village does not",
                            "turn one zombie into a full-village pathfinding storm.")
                    .defineInRange("maxGuardsAlerted", 8, 1, 64);
            treatGuardsAsMcaGuards = builder
                    .comment("Give guards an MCA-style identity: a name, a gender, a personality and",
                            "per-player hearts, all persisted with the world and regenerated from the",
                            "guard's UUID so the same guard always gets the same identity.",
                            "This is what lets the other MCA addons treat a guard as an MCA NPC.",
                            "Applies to every entity tagged 'mcamobcompat:guard', not only Guard",
                            "Villagers' own guard -- but only while Guard Villagers is installed.",
                            "Turning this off leaves existing identities on disk untouched; turning it",
                            "back on resumes them.")
                    .define("treatGuardsAsMcaGuards", true);
            guardHeartsLostPerHit = builder
                    .comment("Hearts added to a guard when a player hits it. Negative costs hearts;",
                            "0 disables the hearts response entirely.")
                    .defineInRange("guardHeartsLostPerHit", -5, -100, 100);
            builder.pop();

            builder.comment("Which animals an MCA villager's hunting chore must leave alone.",
                            "",
                            "MCA's hunting task types its target as vanilla 'Animal' and excludes only",
                            "TamableAnimal. Genetic Animals entities extend Animal, and SWEM and DragN's",
                            "equines extend AbstractHorse, which in 1.20.1 extends Animal without being",
                            "TamableAnimal -- so a tamed, player-owned modded horse is already a legal",
                            "hunting target. These options take it back off the menu.")
                    .push("chores");
            protectOwnedAnimals = builder
                    .comment("Protect any animal that reports an owner through vanilla -- tamed equines,",
                            "cats, wolves, parrots, and every modded animal built on those. Ownership is",
                            "read, never written.")
                    .define("protectOwnedAnimals", true);
            protectedRoles = builder
                    .comment("Roles that are never a valid hunting target, by MobRole name; the same names",
                            "as the tag files under data/mcamobcompat/tags/entity_types/. Matched",
                            "case-insensitively; a name that is not a role is logged once at startup and",
                            "ignored.",
                            "",
                            "'livestock' is deliberately NOT here by default. The hunting chore exists to",
                            "produce meat, and an untamed, unnamed cow in the wild -- vanilla or from an",
                            "animal mod -- is exactly what it is meant to hunt. Protecting livestock by",
                            "default would neuter the chore for every pack that replaces vanilla farm",
                            "animals. A pack that keeps its herds elsewhere adds one entry here.")
                    .defineList("protectedRoles", List.of("mount", "pet"), entry -> entry instanceof String);
            protectNamedAnimals = builder
                    .comment("Protect any animal with a custom name. A name tag is the vanilla signal that",
                            "a player cares about this particular animal.")
                    .define("protectNamedAnimals", true);
            protectLeashedAnimals = builder
                    .comment("Protect any animal on a lead. A leashed animal is being taken somewhere by",
                            "somebody.")
                    .define("protectLeashedAnimals", true);
            abandonHuntOnProtectedTarget = builder
                    .comment("When a hunting villager is stopped from hitting a protected animal, also",
                            "abandon the chore.",
                            "Without this the hunting task keeps its own target field pointed at an animal",
                            "it can never hurt, and the villager stands there swinging forever. Needs the",
                            "reflective MCA binding; where that is unavailable the attack is still blocked,",
                            "this part is simply skipped.")
                    .define("abandonHuntOnProtectedTarget", true);
            builder.pop();

            builder.comment("SWEM (Star Worm Equestrian Mod).").push("swem");
            swemRecognizeAsMounts = builder
                    .comment("Treat SWEM horses as mounts. Breeding, needs, skills and tack stay entirely",
                            "with SWEM -- this mod only recognises them, and never writes to them.")
                    .define("recognizeAsMounts", true);
            builder.pop();

            builder.comment("Genetic Animals (mod id 'eanimod').").push("genetic_animals");
            geneticAnimalsRecognizeLivestock = builder
                    .comment("Treat the animals from Genetic Animals as livestock. Genetics, gender,",
                            "pregnancy and breeding all remain owned by Genetic Animals itself.")
                    .define("recognizeLivestock", true);
            builder.pop();

            builder.comment("DragN's Livestock Overhaul.").push("dragn_livestock");
            dragnLivestockRecognizeLivestock = builder
                    .comment("Treat the O-Animals as livestock. Genders, breeding and genetics all remain",
                            "owned by DragN's Livestock Overhaul itself.")
                    .define("recognizeLivestock", true);
            builder.pop();
        }
    }
}

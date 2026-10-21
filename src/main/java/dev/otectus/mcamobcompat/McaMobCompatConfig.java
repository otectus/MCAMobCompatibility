package dev.otectus.mcamobcompat;

import net.minecraftforge.common.ForgeConfigSpec;
import dev.otectus.mcamobcompat.character.AppearanceMode;
import dev.otectus.mcamobcompat.character.EditorPermission;
import dev.otectus.mcamobcompat.character.GuardRecruitmentMode;
import dev.otectus.mcamobcompat.character.RecruitsConversionPolicy;
import dev.otectus.mcamobcompat.mount.NpcRidingPolicy;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Configuration for MCA: Mob Compatibility.
 *
 * <p>Three specs. <b>COMMON</b> holds what has to be known before a world exists -- which integration
 * modules are live at all, and debug logging. <b>SERVER</b> holds gameplay, because all of it is
 * server-authoritative and it belongs to the world rather than to the installation: a server owner
 * decides whether guards defend MCA villagers, not each connecting client. <b>CLIENT</b> holds one
 * rendering preference, which can only ever fall back to a native renderer: it changes nothing about
 * identities, collision, permissions or who controls an NPC.
 *
 * <p>The option list is kept deliberately short. Classification itself is extended with datapack
 * tags, not with config lists, so there is no per-entity toggle here and there should never be one.
 */
public final class McaMobCompatConfig {

    public static final Common COMMON;
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Server SERVER;
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Client CLIENT;
    public static final ForgeConfigSpec CLIENT_SPEC;

    static {
        final Pair<Common, ForgeConfigSpec> common = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        COMMON_SPEC = common.getRight();

        final Pair<Server, ForgeConfigSpec> server = new ForgeConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();

        final Pair<Client, ForgeConfigSpec> client = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
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
        public final ForgeConfigSpec.BooleanValue icysBetterHorsesModule;
        public final ForgeConfigSpec.BooleanValue alexsMobsModule;
        public final ForgeConfigSpec.BooleanValue recruitsModule;
        public final ForgeConfigSpec.BooleanValue workersModule;

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
            icysBetterHorsesModule = builder
                    .comment("Icy's Better Horses (mod id 'icys_better_horses'): its horse owners and",
                            "commands, for chore protection and for which horses a villager may ride.")
                    .define("icysBetterHorses", true);
            alexsMobsModule = builder
                    .comment("Alex's Mobs (mod id 'alexsmobs').")
                    .define("alexsMobs", true);
            recruitsModule = builder
                    .comment("Villager Recruits (mod id 'recruits'). NOTE: turning this off also turns off",
                            "the protection of MCA villagers from Recruits' own conversions, which discard",
                            "the villager. Leave it on.")
                    .define("recruits", true);
            workersModule = builder
                    .comment("Villager Workers (mod id 'workers'), which needs Villager Recruits.")
                    .define("workers", true);
            builder.pop();
        }
    }

    /** Client-side rendering preference: may only ever fall back to a native renderer. */
    public static final class Client {

        public final ForgeConfigSpec.BooleanValue mcaAppearance;

        Client(ForgeConfigSpec.Builder builder) {
            builder.comment("Rendering.").push("rendering");
            mcaAppearance = builder
                    .comment("Draw guards, recruits and workers as their MCA persons where the server allows it.",
                            "Off: always use each mod's own renderer. Affects only what this client sees.")
                    .define("mcaAppearance", true);
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
        public final ForgeConfigSpec.BooleanValue guardMcaInteraction;
        public final ForgeConfigSpec.BooleanValue recruitMcaVillagersAsGuards;
        public final ForgeConfigSpec.BooleanValue guardShowNameTag;
        public final ForgeConfigSpec.BooleanValue followAddonTargets;
        public final ForgeConfigSpec.EnumValue<GuardRecruitmentMode> guardRecruitmentMode;
        public final ForgeConfigSpec.EnumValue<AppearanceMode> guardAppearance;

        public final ForgeConfigSpec.BooleanValue recruitsCharacters;
        public final ForgeConfigSpec.BooleanValue recruitsMcaInteraction;
        public final ForgeConfigSpec.EnumValue<AppearanceMode> recruitsAppearance;
        public final ForgeConfigSpec.EnumValue<RecruitsConversionPolicy> recruitsVillagerConversion;
        public final ForgeConfigSpec.BooleanValue recruitsLawEnforcement;

        public final ForgeConfigSpec.BooleanValue workersCharacters;
        public final ForgeConfigSpec.BooleanValue workersMcaInteraction;
        public final ForgeConfigSpec.EnumValue<AppearanceMode> workersAppearance;

        public final ForgeConfigSpec.EnumValue<EditorPermission> editorPermission;
        public final ForgeConfigSpec.IntValue editorAdminPermissionLevel;
        public final ForgeConfigSpec.IntValue editorSessionMinutes;
        public final ForgeConfigSpec.BooleanValue playerEnlistment;
        public final ForgeConfigSpec.BooleanValue playerCivilianReturn;
        public final ForgeConfigSpec.BooleanValue forwardMcaCommands;
        public final ForgeConfigSpec.BooleanValue socialPause;

        public final ForgeConfigSpec.BooleanValue protectOwnedAnimals;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> protectedRoles;
        public final ForgeConfigSpec.BooleanValue protectNamedAnimals;
        public final ForgeConfigSpec.BooleanValue protectLeashedAnimals;
        public final ForgeConfigSpec.BooleanValue abandonHuntOnProtectedTarget;

        public final ForgeConfigSpec.BooleanValue creeperAwareness;
        public final ForgeConfigSpec.IntValue creeperAlertRange;
        public final ForgeConfigSpec.IntValue swellingFleeRange;
        public final ForgeConfigSpec.BooleanValue guardsEngageCreepers;
        public final ForgeConfigSpec.BooleanValue dismountToFlee;

        public final ForgeConfigSpec.EnumValue<NpcRidingPolicy> npcRidingPolicy;

        public final ForgeConfigSpec.BooleanValue swemRecognizeAsMounts;
        public final ForgeConfigSpec.BooleanValue geneticAnimalsRecognizeLivestock;
        public final ForgeConfigSpec.BooleanValue dragnLivestockRecognizeLivestock;
        public final ForgeConfigSpec.BooleanValue icyRecognizeOwnership;
        public final ForgeConfigSpec.BooleanValue alexsRecognizeAnimals;

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
            guardMcaInteraction = builder
                    .comment("Give bridged guards MCA's own right-click behaviour.",
                            "On: a plain right-click opens MCA's real interaction screen, with every",
                            "MCA option working, through a hidden MCA villager that stands in for the",
                            "guard and carries its name, personality, hearts and family; crouch-right-",
                            "click still opens Guard Villagers' own inventory GUI.",
                            "Off: both gestures behave exactly as Guard Villagers ships them, and no",
                            "hidden villager is created at all.",
                            "Needs 'treatGuardsAsMcaGuards' on, because the stand-in is seeded from the",
                            "bridged identity.")
                    .define("guardMcaInteraction", true);
            recruitMcaVillagersAsGuards = builder
                    .comment("Let crouch-and-right-click with a weapon RECRUIT an MCA villager as a guard",
                            "instead of blocking the gesture. How it is recruited is 'guardRecruitmentMode'.",
                            "Either way nothing is destroyed, which is the whole objection to the Guard",
                            "Villagers conversion. Turning this off restores the plain block and its",
                            "message, whatever 'guardRecruitmentMode' says.",
                            "Needs 'blockGuardConversion' on: the recruit replaces the block, and with",
                            "the block off the destructive conversion runs instead.")
                    .define("recruitMcaVillagersAsGuards", true);
            guardRecruitmentMode = builder
                    .comment("How the recruit gesture recruits an MCA villager. Two different controllers:",
                            "NATIVE_GUARD: the villager becomes a real Guard Villagers guard -- Guard",
                            "  Villagers' own combat, patrol, food and inventory -- and stays the same MCA",
                            "  person behind it (same UUID, face, family, hearts and possessions). It can",
                            "  return to civilian life with /mcamobcompat character release. Needs",
                            "  'treatGuardsAsMcaGuards' and 'guardMcaInteraction' on; otherwise the",
                            "  gesture falls back to MCA_PROFESSION. The weapon goes to the guard.",
                            "MCA_PROFESSION: the villager takes MCA's own 'mca:guard' profession in place",
                            "  and keeps MCA's guard AI, holding the weapon. The behaviour of 0.2.x.",
                            "Existing MCA guards are never converted by this option; it only decides",
                            "what the next recruit gesture does.")
                    .defineEnum("guardRecruitmentMode", GuardRecruitmentMode.NATIVE_GUARD);
            guardAppearance = builder
                    .comment("How clients draw a bridged guard. MCA: as its MCA person -- face, skin, hair,",
                            "clothes and genetics -- in Guard Villagers' own pose and equipment. NATIVE:",
                            "Guard Villagers' own model, as without this mod. Collision, reach and combat",
                            "always stay Guard Villagers'.")
                    .defineEnum("guardAppearance", AppearanceMode.MCA);
            guardShowNameTag = builder
                    .comment("Show a bridged guard's rolled name permanently above its head, the way",
                            "MCA shows a villager's. A nameplate on every guard in the village is a loud",
                            "change, so it is switchable; turning it off only stops NEW guards gaining",
                            "one, because a nameplate already set is a player-visible fact we do not",
                            "quietly take away.")
                    .define("guardShowNameTag", true);
            followAddonTargets = builder
                    .comment("Push an attack target that another MCA addon set on a guard's hidden",
                            "stand-in up to the guard itself. MCA: Crime makes a guard pursue a wanted",
                            "player by setting a target on the villager it selected as a responder,",
                            "and for a bridged guard that villager is the stand-in, which cannot move.",
                            "On, the real guard takes the order; off, a target set on the stand-in is",
                            "reverted to the guard's own on the next tick and bridged guards never",
                            "enforce Crime's law. Applies to player targets only; any other target is",
                            "always pushed up, except an MCA villager while 'preventGuardFriendlyFire'",
                            "is on.")
                    .define("followAddonTargets", true);
            builder.pop();

            builder.comment("Villager Recruits (mod id 'recruits'): recruits, shieldmen, bowmen, crossbowmen,",
                            "horsemen, nomads, leaders, specialists and nobles as MCA characters.",
                            "Combat, formations, groups, hiring, upkeep, morale and promotion all stay",
                            "Recruits'. Nothing here makes a recruit friendly to anybody: Recruits'",
                            "own diplomacy and ownership decide that.")
                    .push("recruits");
            recruitsCharacters = builder
                    .comment("Give recruits a persistent MCA person: an MCA identity, created once and kept",
                            "through promotions, restarts and portals. Off, recruits stay as Recruits ships",
                            "them; identities already made are kept, hidden, and resumed when turned back on.")
                    .define("mcaCharacters", true);
            recruitsMcaInteraction = builder
                    .comment("A plain right-click on a recruit opens MCA's own interaction screen for its",
                            "person; crouch-right-click opens Recruits' own screen (its inventory and orders",
                            "for the owner, the hire screen for an unhired recruit). Off: Recruits' own",
                            "gestures only.")
                    .define("mcaInteraction", true);
            recruitsAppearance = builder
                    .comment("How clients draw a recruit: MCA (its MCA person, in Recruits' pose and",
                            "equipment, with its team's colour on the name) or NATIVE (Recruits' own model).")
                    .defineEnum("appearance", AppearanceMode.MCA);
            recruitsVillagerConversion = builder
                    .comment("What happens when Recruits' own hiring -- a noble's hire screen -- picks an MCA",
                            "villager to turn into a recruit or a worker. Recruits would discard the villager,",
                            "family and genes included, so that never happens to an MCA villager.",
                            "ENLIST: the same person is hired through Recruits' own payment and ownership and",
                            "  becomes the new recruit's MCA identity. BLOCK: the hire is refused, unpaid.",
                            "Recruits' automatic conversions -- a villager that takes a recruit profession, a",
                            "jobless villager becoming a noble -- are always refused for MCA villagers.")
                    .defineEnum("villagerConversion", RecruitsConversionPolicy.ENLIST);
            recruitsLawEnforcement = builder
                    .comment("Give recruits' MCA persons MCA's 'mca:guard' profession, which MCA: Crime and",
                            "MCA: Reputation treat as law enforcement. Off by default: a soldier is not a",
                            "constable, and an enemy faction's recruits must not police your village.")
                    .define("lawEnforcement", false);
            builder.pop();

            builder.comment("Villager Workers (mod id 'workers'): farmers, lumberjacks, miners, builders,",
                            "merchants, fishermen, animal farmers, couriers and cooks as MCA characters.",
                            "Their jobs, work areas, storage, hiring and chunk loading stay Workers'.")
                    .push("workers");
            workersCharacters = builder
                    .comment("Give workers a persistent MCA person, as for recruits.")
                    .define("mcaCharacters", true);
            workersMcaInteraction = builder
                    .comment("A plain right-click on a worker opens MCA's screen for its person; crouch opens",
                            "Workers' own screen.")
                    .define("mcaInteraction", true);
            workersAppearance = builder
                    .comment("How clients draw a worker: MCA or NATIVE, as for recruits.")
                    .defineEnum("appearance", AppearanceMode.MCA);
            builder.pop();

            builder.comment("MCA characters behind native NPCs, whatever mod the NPC comes from.").push("characters");
            editorPermission = builder
                    .comment("Who may open MCA's editor (its 'villager editor' item) on a bridged NPC, for its",
                            "appearance, genes and name. ANYONE: anyone holding the item, MCA's own rule.",
                            "OWNER_OR_UNOWNED: the native owner of an owned NPC, anyone for an unowned one,",
                            "and operators. OPERATORS: operators only. Age, traits, personality, memories",
                            "and family links always need 'editorAdminPermissionLevel'.")
                    .defineEnum("editorPermission", EditorPermission.OWNER_OR_UNOWNED);
            editorAdminPermissionLevel = builder
                    .comment("The permission level that may change a bridged character's age, traits,",
                            "personality, mood, memories and family links in MCA's editor.")
                    .defineInRange("editorAdminPermissionLevel", 2, 0, 4);
            editorSessionMinutes = builder
                    .comment("How long an editor opened on a bridged NPC may keep saving. A save after this, or",
                            "after another player saved the same character, is refused and the editor is",
                            "reopened with the latest version.")
                    .defineInRange("editorSessionMinutes", 10, 1, 120);
            playerEnlistment = builder
                    .comment("Let players give an MCA adult a native role with /mcamobcompat character enlist,",
                            "through the native mod's own hiring, payment and ownership.")
                    .define("playerEnlistment", true);
            playerCivilianReturn = builder
                    .comment("Let a native NPC's owner (or an operator) send an enlisted MCA person back to",
                            "civilian life with /mcamobcompat character release.")
                    .define("playerCivilianReturn", true);
            forwardMcaCommands = builder
                    .comment("Carry out MCA's Follow, Stay and Move buttons through the native mod's own",
                            "orders, with its own ownership rules. Off: those buttons do nothing to the NPC.")
                    .define("forwardMcaCommands", true);
            socialPause = builder
                    .comment("Hold a recruit or worker still for up to 30 seconds while a player talks to it",
                            "through MCA's screen. Released by distance, damage or the time limit; its",
                            "native order resumes afterwards.")
                    .define("socialPause", false);
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

            builder.comment("How MCA villagers react to a creeper before it explodes.",
                            "",
                            "MCA on its own only notices a creeper lit with flint and steel; one that walks",
                            "up and swells is unseen until its explosion lands. This gives MCA villagers the",
                            "same warning through MCA's own panic and guard behaviour -- no creeper, golem or",
                            "gamerule is changed. Which mobs count is the 'mcamobcompat:npc_threats' entity",
                            "tag, shipped with minecraft:creeper.")
                    .push("threats");
            creeperAwareness = builder
                    .comment("Let MCA villagers notice and react to threats in 'npc_threats'.")
                    .define("creeperAwareness", true);
            creeperAlertRange = builder
                    .comment("How far, in blocks, a villager notices a threat it can see.")
                    .defineInRange("creeperAlertRange", 8, 2, 32);
            swellingFleeRange = builder
                    .comment("Within this many blocks of a creeper whose fuse is burning, everyone runs --",
                            "guards included. Doubled for a charged creeper, as its explosion is.")
                    .defineInRange("swellingFleeRange", 4, 1, 16);
            guardsEngageCreepers = builder
                    .comment("Let MCA guards and archers fight a creeper that is not about to explode,",
                            "instead of running from it. Uses MCA's own guard combat.")
                    .define("guardsEngageCreepers", true);
            dismountToFlee = builder
                    .comment("A mounted villager dismounts to run from a threat. Nothing here can steer a",
                            "villager's mount, so staying seated means staying put.")
                    .define("dismountToFlee", true);
            builder.pop();

            builder.comment("Which mounts an MCA villager may be put on.",
                            "",
                            "MCA seats a villager on a horse when a player asks it to, and on a vanilla horse",
                            "MCA then steers it. Some mounts accept a villager but only ever let a player",
                            "steer -- SWEM's horses are the known case -- so the villager rides along with no",
                            "say in where it goes. Those are listed in the 'mcamobcompat:player_only_mounts'",
                            "entity tag, and integrations can refuse others (an Icy's Better Horses horse",
                            "that belongs to a player, for example).")
                    .push("mounts");
            npcRidingPolicy = builder
                    .comment("REFUSE: such a mount refuses the villager, and the player who gave the order",
                            "is told why. ALLOW_INERT: the villager mounts anyway, as a passenger that",
                            "cannot steer -- the behaviour before 0.2.1.")
                    .defineEnum("npcRidingPolicy", NpcRidingPolicy.REFUSE);
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

            builder.comment("Icy's Better Horses (mod id 'icys_better_horses').").push("icys_better_horses");
            icyRecognizeOwnership = builder
                    .comment("Treat a horse Icy's Better Horses says belongs to a player as owned, so the",
                            "hunting chore leaves it alone and no villager rides off on it. Icy's keeps",
                            "other players off an owned horse but does not check villagers. Read only.")
                    .define("recognizeOwnership", true);
            builder.pop();

            builder.comment("Alex's Mobs (mod id 'alexsmobs').").push("alexs_mobs");
            alexsRecognizeAnimals = builder
                    .comment("Classify Alex's Mobs' animals: ground-rideable ones as mounts, tamed ones as",
                            "pets. Off, they get no roles at all, though a tamed one is still spared by the",
                            "hunting chore because it has an owner. Taming, riding and every creature's",
                            "abilities stay entirely Alex's Mobs'.")
                    .define("recognizeAnimals", true);
            builder.pop();
        }
    }
}

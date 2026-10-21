# Playing with MCA characters behind native NPCs

With MCA: Mob Compatibility 0.3.0, Guard Villagers' guards, Villager Recruits' recruits and Villager
Workers' workers are MCA people. Each one is still its own mod's NPC: it fights, works, follows and
obeys exactly as that mod made it. Behind it stands a real MCA person with a name, a face, a family,
memories and your hearts with them. This guide is for players; server owners will find the options in
the [README](../README.md#configuration).

(On the NeoForge 1.21.1 build only Guard Villagers' guards are characters: Villager Recruits and
Villager Workers have no NeoForge 1.21.1 build.)

## What you will see

- **They look like MCA people.** A guard, recruit or worker is drawn as its MCA person: MCA's skin,
  face, hair, clothes and body shape. It keeps its own armour, weapons, pose and mount, and its own
  size: a tall person looks tall but walks and fights like the guard it is. If you prefer each mod's
  own models, turn off `mcaAppearance` in your client config (`mcamobcompat-client.toml`); a server can
  also turn the look off per mod.
- **Click to talk.** A plain right-click opens MCA's own screen for that person: chat, gifts,
  greetings, their family tree, and MCA: Quests, MCA: Conversations and the other MCA addons. Hearts
  you earn are kept with the person.
- **Crouch-click for the native controls.** A crouching right-click opens the NPC's own mod's screen:
  a guard's inventory, a recruit's commands and inventory, or the hire screen for a recruit nobody has
  hired yet. If crouching is awkward, look at the NPC and run `/mcamobcompat character controls`.
  Whether the screen opens for you is that mod's own rule: Guard Villagers, for example, opens a
  guard's inventory to a player with enough reputation with it, Hero of the Village, or the guard's
  owner, and a guard you enlisted gives you that reputation, as Guard Villagers' own conversion does.

## Giving orders

MCA's **Follow**, **Stay** and **Move** buttons are carried out by the NPC's own mod, under that mod's
rules:

- A **guard** follows you (Guard Villagers lets a guard follow a Hero of the Village), patrols where
  it stands, or goes back to its own business.
- A **recruit** follows you, holds its position, or wanders. Only the recruit's owner can give it
  orders, exactly as with Recruits' own command screen.
- A **worker** follows you, holds still, or, on Move, **goes back to work**, keeping its work area,
  home and routes.

If the mod refuses an order (for example, you do not own that recruit), MCA's screen goes back to Move
and you are told why. An order you later give through the mod's own screen is never overwritten by an
old MCA order.

## Editing someone's appearance

Hold MCA's **Villager Editor** and right-click the guard, recruit or worker. MCA's own editor opens on
their person. Change their name, skin, clothes, hair, colours or genes, then save: everyone around sees
the change, on the NPC itself.

- Who may edit is the server's choice. By default the owner of an owned NPC may, anyone may edit an
  unowned one, and operators always may.
- Age, traits, personality, mood, memories and family links can only be changed by an operator.
  Changes to them by anyone else are simply not applied.
- If two players edit the same person, the first save wins. The second is refused and their editor
  reopens showing what was saved.
- The editor stays valid for ten minutes by default. Save before walking away.

## Hiring an MCA villager into a native role (enlisting)

An adult MCA villager can take a native job and stay exactly the same person: same name, face, family,
memories and hearts. Nobody is replaced. While serving, the person is hidden behind their new body, and
they come back when their service ends.

- **As a guard:** crouch and right-click the villager while holding a sword or crossbow, the Guard
  Villagers gesture. The villager becomes a real Guard Villagers guard holding that weapon. (A server
  can choose the 0.2 behaviour instead, where the villager just takes MCA's guard job; see
  `guardRecruitmentMode`.)
- **As a recruit or worker:** use Recruits' own hiring at a noble. When the noble's hire picks an MCA
  villager, that same person is hired through Recruits' own payment and becomes your recruit or worker.
  (A server can block that instead; then the hire is refused and nothing is paid.)
- **By command:** look at the villager and run `/mcamobcompat character enlist <role>`, for example
  `guardvillagers:guard`, `recruits:bowman` or `workers:farmer`. Tab completion lists the roles. The
  native mod's own costs and rules apply.

Children cannot enlist, and neither can someone already serving.

Recruits' own conversions never take an MCA villager: a villager who takes a recruit profession at a
Recruits block keeps being themselves (the profession is put back), and jobless MCA villagers never
turn into nobles.

## Promotions

Promote a recruit with Recruits' own promotion screen as usual. Recruits replaces the old recruit with
a new captain, messenger or scout, and the MCA person moves to the new body: same name, face, family,
memories and hearts.

## Coming home: returning to civilian life

Look at an enlisted guard, recruit or worker and run `/mcamobcompat character release`. The person
comes back where the NPC stood: visible again, with their old job, the MCA order they had before (Stay
or Move; a Follow comes back as Move), and everything the NPC carried, moved into their own inventory. Recruits and Workers are told the service
ended through their own dismissal, so your unit counts and work areas are freed.

- The player who enlisted them can send them home, and so can an operator; for a recruit or worker,
  so can its owner. (A guard has no owner in Guard Villagers, only a player it follows.)
- If their inventory cannot hold what the NPC carries, nothing happens, and you are told how many
  stacks the NPC is carrying. Take some out through the NPC's own screen first.
- Going home is not a death: there is no loot, no experience and no grief in their family.

An NPC that was always a guard or recruit (one you did not enlist) has no civilian life to return to.
`release` says so.

## Checking on someone

`/mcamobcompat character info`, while looking at a guard, recruit or worker, shows their name, their
native role, who owns them and what their own mod says they are doing.

## Messages you may see

| Message | Meaning |
|---|---|
| "… takes orders only from its owner." | You do not own that recruit or worker, so MCA's buttons cannot order it. |
| "… is owned by another player." | Only the player who enlisted them, the NPC's owner, or an operator can send them home. |
| "Waiting for this character's MCA identity to load." | The person behind the NPC is not loaded yet; try again in a moment. |
| "Return … to civilian life before changing their age." | Age changes are refused while serving. |
| "Someone else saved … since you opened the editor. …" | Another player saved first; your editor has reopened on what they saved. |
| "Native recruitment is unavailable for this mod version." | The installed build of that mod could not be checked; the server log says why. |
| "That is not a native NPC with an MCA character." | You are not looking at a guard, recruit or worker with an MCA person. |

These are the English texts. Every refusal changes nothing.

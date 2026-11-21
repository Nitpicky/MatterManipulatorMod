# Changelog

Versions are MAJOR.FEATURE.PATCH. A version that isn't marked as a release rolls into the
next release's notes.

## 1.0.2

### Changes
- Mods list: new mod icon.
- Commands: `/ethermod set_module`, `set_build`, `export_build` (shareable build codes like `1GABA2HAAA`) and `set_color`.

### Fixes
- Lock-On: the beam now visibly stays on the locked creature.
- Growth: no longer bone-meals grass, ferns and tall flowers (it broke them and spilled items).
- Shots: jumping as you fire no longer catches your own shot (Water stopped the jump, Frost lost its bridge).

### Tuning
- Gravity Shift: no fall damage while gravity is shifted.
- Zipline: description says how to string (sneak) and ride (hold right click) a rope.
- Bank Shot: each ricochet resets the shot's range.
- Steady Hand: held blocks turn see-through while a throw charges.

## 1.0.1 (release)

### Changes
- Ziplines: no more chat-bar text when you place the first end or finish the rope (the sounds
  still tell you).
- Photonic: the light that follows you (whenever the Photonic line is equipped, summoned or not)
  is now a smooth moving glow, like dynamic-lights mods. It
  fades with distance from your exact position instead of jumping from block to block, other
  players see it too, and it places no blocks (so it never touches claims). It works with Sodium
  and Iris. Client setting `photonic_dynamic_light` turns it off. Since it is purely visual, it
  no longer melts snow or ice.

### Fixes
- Ziplines: riding no longer swings you from side to side. You now build up speed along the
  rope, turn around smoothly, keep the momentum you jumped on with, and stop cleanly at the ends.
- Ziplines: a half-strung rope still finishes after you rejoin.
- The Ether Manipulator can no longer be put into, or taken by, any display: item frames, armor
  stands, allays, decorated pots, and modded pedestals, display cases and cutting boards. Blocks
  and creatures act exactly as if your hand were empty while it is summoned: chests, doors,
  buttons and machine screens work as usual.
- Items that a display or creature hands back while the Manipulator is out now go into your
  inventory instead of replacing the item in your selected hotbar slot.
- The Ether Manipulator can no longer be duplicated or lost through other mods' interactions: it
  always stays a single item, and stray dropped copies vanish.
- Ordinary soul fire again follows the vanilla rule (soul sand and soul soil only). Soul fire from
  Soulwake and Soul Blazelings still burns on any ground until it fades.
- Collector: items being pulled toward you no longer stay floating if the server stops, the chunk
  unloads or they pass through a portal mid-pull.
- Mass Driver: lifting a container could empty it if the lifted block failed to appear.
- A jump held while logging out no longer counts as held on the next join.
- Tether: putting the Ether Manipulator away now lets go of the hook.
- Gem guarantee: the gem promised in the first chest of each structure is now only used up when
  a player opens the chest. Hopper minecarts, machines and commands no longer take it.
- Orbs in flight are no longer saved half set up when the world closes. A block held or thrown by
  the Mass Driver is still kept.
- Gale Step now only works while you are actually in the air.
- Light beams no longer place light inside claims or protected areas you can't build in.
- Mass Driver: the glow around held blocks now turns with them instead of staying square to the
  world.
- Frost bridges no longer leave leftover data behind on long-running servers.

### Sounds and effects
- Tether: the hook now sounds as designed. The shot, the latch, the start of the reel and the
  hook letting go each get a faint End layer, and the latch and release give off small purple
  sparks.
- Water orb: a little slosh in flight and a lighter, cup-sized splash on impact.
- Growth pollen cloud: a soft pop as plants grow under it, once a second at most.

### Compatibility
- Mobs you fight no longer keep leftover Ether Manipulator data in their saves, and old leftover
  data is cleaned up when they load. Mob-capture and mob-copy mods see clean mobs.
- Camera mods that replace the third-person camera no longer switch off the gravity camera turn.
- The Stasis tint on held creatures now shows properly with shader packs.
- Resource pack reloads (F3+T) now update coloured Manipulator bodies and the K screen.
- The mod list now shows the mod's logo, author and CurseForge page.
- Claim and protection checks no longer post extra fake block-break events while the beam rests
  on a block, while the Mass Driver lifts, or while a sweep or harvest is planned. Grief-logger,
  quest and stats mods see far fewer phantom breaks. The `respect_protection` server config
  comment explains this.

### Under the hood
- Less network traffic from Stasis Grenades, less work per frame for the HUD, the K screen
  and effects, and a hard limit on how many effects can be drawn at once.
- Client-only code is fully separated from server code, and the largest files were split up.
- Large parts of the code were reorganised into smaller pieces for stability and easier fixes.
  No gameplay changes.

## 1.0.0 (release)

First release.

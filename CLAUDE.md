# GEVisualAidPlugin

RuneLite plugin (Java) that publishes OSRS game state for the wider automation
suite. Written to `ge_visual_aid.txt` and served on `127.0.0.1:8081/state`.

Josh is the sole operator and developer. **He is not a coder** — explain in plain
terms, never assume he'll read code to understand an answer.

---

## ⚠️ The full rules live in the other repo

This plugin is one half of a system. The authoritative instructions —
architecture, working style, delivery rules, network layout, the accumulated
traps and learnings — are in:

```
C:\Users\gump1\IdeaProjects\osrs-suite\CLAUDE.md
```

**Preferred:** start sessions in `osrs-suite` and pull this repo in with
`/add-dir C:\Users\gump1\IdeaProjects\example-plugin`. That way the full rules
load and cross-cutting changes (a new plugin field plus the AHK that reads it)
happen in one session.

**If you're already in a session started here**, read
`../osrs-suite/CLAUDE.md` and `../osrs-suite/docs/LEARNINGS.md` before making any
non-trivial change.

---

## Rules that always apply here

- **Diagnose before changing.** Read the actual file. Never assume.
- **Surgical fixes over refactors.** Modify only what's needed. Never reformat
  untouched sections.
- **Scene data is computed on the CLIENT THREAD** in `onGameTick()` and published
  to a volatile cached string. Never touch Perspective or scene off the client
  thread. HTTP writes are queued by the HTTP thread and applied on the client
  thread in `onGameTick`.
- **No lookup tables** — animation IDs→skills, widget IDs→orbs, spell→rune costs,
  object-ID constants. They rot silently and **mislabel rather than fail loudly**.
  Discovery via the `*` wildcard filter and live observation is the approach.
- **Verify IDs and names with `*` rather than trusting the wiki.** Object-ID
  constants absent from this RuneLite API version will compile and match nothing.
- **Interface constants and widget child indices move between RuneLite versions.**
  Prefer varbits, retry loops, or observation.
- **`localToCanvas()` returns null only for tiles behind the camera or off-scene**,
  not off the side of the screen — clip explicitly to the viewport.
- **An isometric tile is a diamond filling half its bounding box.** Uniform
  sampling of the bounds puts ~50% of clicks on a neighbouring tile.
- **Multi-tile GameObjects are returned by every tile they cover.**
- **Per-activity filters go in an ENTITY SET, not the global box.** 2.65 added
  ten named sets (Enabled / Name / Scenery / NPCs / Items), merged with the
  always-on boxes rather than replacing them. Use the always-on boxes only for
  what is genuinely common. `/filter?entityset=<name>` switches one on and all
  others off; an unknown name changes nothing rather than blanking everything.
  Overwriting the global with `/filter?scenery=` still works but destroys
  whatever another activity had, and it persists to the RuneLite profile.
- **A duplicate filter label is silently DROPPED, not merged.**
  `selectResults()` skips a label it has already emitted, so the second entry
  matches nothing forever while looking configured. 2.65 reports these in
  `entity_set_conflicts` — check it before believing an area is empty.
- **Same scenery name can be several different objects, only one clickable.**
  At the Blast Furnace `Conveyor belt` is 9101 twice with NO actions plus 9100
  with `Put-ore-on`, and the dead ones rank NEARER, so `go_<label>_0` is
  unclickable. Prefer `#Action` over a name wherever an action exists — it also
  self-corrects, e.g. a depleted rock loses `Mine`.
- **Don't cache derived config at all unless the rebuild is genuinely
  expensive.** 2.65 guarded the merged entity-set filters on a "spec" string
  and left the sets' own filter text out of it, so edits and deletions silently
  never took effect. The guard was near-worthless anyway: it read every config
  field to build the spec, so it skipped nothing. 2.70 assigns the merged
  strings unconditionally. Guard only things whose staleness is harmless — a
  log line, a warning — never the data itself.
- **Every entity family has a MASTER TOGGLE separate from its filter**
  (`sceneryon` / `npcson` / `itemson` / `carriedon`). A filter with its toggle
  off produces `<fam>_enabled=false` + `<fam>_count=0` — two honest readings
  that combine to look exactly like "matched nothing". 2.67 names the case in
  `filters_configured_but_off`; check it before debugging a filter.
- **A scrolled-out bank item widget still has a RECTANGLE.** It is not hidden
  and its bounds are not empty — they are just elsewhere, possibly over the
  game world, so clicking it walks the player. 2.66 requires a child's bounds
  to intersect its container before emitting a box. Never trust `getBounds()`
  on a container child without that test.
- **For carried items, presence and position come from different places.**
  Counts (`ib_*_inv_qty` / `_bank_qty` / `_worn`) come from the ItemContainer
  and stay right when the bank scrolls or shuts; the click box comes from the
  widget and exists only when the item is on screen. Do not merge them —
  `state` separates `scrolled_out` (scroll or change tab) from `bank_closed`
  (a remembered count; the container stays populated after the bank closes).
- **An object's ACTION LIST changes with its state, and that is a free
  readiness signal.** The Blast Furnace bar dispenser reads `Check` while
  smelting and `Take|Check` once bars are ready (observed live, both states).
  Prefer `#Take` over polling a varbit or a lookup table.
- **Bank placeholders are real entries in the bank container.** An emptied slot
  keeps a placeholder: a DISTINCT item id carrying the real item's name, so an
  exact-name match counts it and it contributes a quantity of 1. 2.63 skips
  them via `ItemComposition.getPlaceholderTemplateId()` (-1 on a real item).
  Before that, every `bank_<name>` was off by one for any emptied slot.
- **A bank filter entry with no `=` is used as BOTH the output key and the name
  to match.** `Gold_ore` matches nothing forever while still publishing
  `bank_Gold_ore=0`. Write the ITEM name — `sanitiseKey` turns spaces into
  underscores, so the key is unchanged.
- **Confirm an API method exists with `javap` against the resolved jar** in the
  Gradle cache before using it. `build.gradle` pins `latest.release`, so the
  cached `runelite-api-*.jar` is the authority, not the wiki.
- **A frozen state file is indistinguishable from a dead plugin** to the AHK
  script. If an exception fires before the `.txt` write in `onGameTick`, the file
  silently holds its last value forever.
- **A bare `javac` parse does not catch duplicate annotations** when the
  annotation classes aren't on the classpath. Check annotation stacking
  explicitly when splicing above an annotated method.
- **Don't inherit a diagnosis from a previous changelog.** The v2.18 changelog's
  stated cause for the offer-screen hang was wrong on both counts.

## Versioning and deploy

- Filename stays `GEVisualAidPlugin.java` — Java requires it to match the class.
  Only the internal version string bumps.
- The VMs pull from GitHub via `git pull --ff-only` in launch.bat at RuneLite
  start. **Commit and push directly** — never hand Josh a file to paste in.
- After pushing, restart RuneLite on s1 and confirm the change appears in
  `127.0.0.1:8081/state` before touching s2 or s3.
- HTTP is the live source; `.txt` mtime lags badly. Freshness signal is
  `pluginLastUpdateTick <= 5s`.

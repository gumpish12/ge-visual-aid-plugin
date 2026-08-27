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
- **`PluginManager.startPlugin` / `stopPlugin` assert they are on the SWING EDT.**
  Both open with `assert SwingUtilities.isEventDispatchThread()` (checked with
  `javap -c` against `client-1.12.36.jar`), and RuneLite runs with assertions
  on — so from the client thread, an HTTP thread, or your own executor they
  throw `AssertionError`. Use `SwingUtilities.invokeAndWait`, and keep any
  pause between a stop and a start OFF the EDT.
- **`setPluginEnabled` has NO such assertion**, so it succeeds while the
  start/stop beside it throws. That combination flips the config flag and
  changes nothing else: the plugin ends up marked disabled and never stopped
  or started. Two versions of `/plugin` shipped broken on exactly this,
  reporting "queued" both times. **An action that half-succeeds is worse than
  one that fails** — check the OUTCOME (`isPluginActive`), never the intent.
- **A RESTARTED PLUGIN KEEPS ITS `Plugin` OBJECT BUT REBUILDS WHAT IS INSIDE.**
  `startUp()` made Rooftop a NEW `coursesManager`; we had cached the old one at
  link time and asked it forever. Overlays drew fine, `rooftop_course` stayed
  empty, nothing errored. Any identity check on the `Plugin` itself passes, so
  **re-read reflected sub-objects every tick** (2.75) rather than caching them,
  and drop a plugin’s links before cycling it. Josh’s manual off-and-on had
  been silently orphaning the link the same way for weeks.
- **Restarting a plugin STEALS THE KEYBOARD.** `startPlugin` rebuilds RuneLite’s
  config panel on the Swing thread and the search field takes focus, so the next
  keys sent land in that box — Josh got `agi,2` and `6,2,2226` typed into it.
  Reclaim focus with a click on the client BEFORE sending anything, and note the
  general form: after any action that touches RuneLite’s own UI, assume focus is
  gone.
- **A tile polygon is FLOOR-level geometry.** A ground item on a table renders
  far above its own tile, so a tile-derived box points at the floor beneath it
  (observed: reported click 2140,1059, sprite ~200px higher). `ItemLayer` extends
  `TileObject`, so **`getClickbox()` gives the real shape the client tests the
  mouse against** — use it for anything LYING on a tile (2.77). The lifted-tile
  fallback reports `ok_tile` rather than `ok`, because a wrong click in those two
  cases has different causes.
- **The item clickbox covers the whole PILE on that tile**, not one item. For a
  lone mark of grace that is the mark; on a busy tile it is still what a click
  would hit, which is the honest answer either way.
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

- **THE PLUGIN PUBLISHES ITS OWN SETTINGS PANEL** at `/config` (2.79), built
  by REFLECTION over the `@ConfigItem` and `@ConfigSection` annotations that
  already draw the RuneLite side panel — all 178 keys with name, description,
  section, type, enum options and effective value, as JSON. A hand-written
  mirror would list keys that do not exist and miss ones that do; this cannot.
  The value comes from INVOKING the config method, not from `ConfigManager`,
  so an unset key reports its real default rather than null.
- **`/filter` writes the entity-set slots by PATTERN** (2.78):
  `set<N><field>` maps onto the config key names, so `set10carried` becomes
  `set10Boxes`. No seventy-row table — that would only be a second spelling of
  something `GEVisualAidConfig` already spells once.
- **`/filter`'s per-set SUMMARY LINE IS NOT PARSEABLE.** It uses `|` and `[`
  as punctuation and an ordinary filter value contains both
  (`Bank booth|#Bank,Bank chest[1]`). 2.78 emits one line per field beside it:
  the summary is for a person reading `/filter` in a browser, the fields are
  for machines. Parse the fields.
- **THE HTTP SERVER BINDS TO 127.0.0.1 DELIBERATELY.** `/filter` and `/config`
  write RuneLite config and start and stop plugins with **no authentication**.
  Do not "fix" this by binding `0.0.0.0`. The skilling script forwards from its
  own already-LAN-reachable port instead — see `GET /plugin/config` there.
  Josh chose this knowingly on 2026-08-26.

- **WIDGET COORDINATES ARE THE THIRD COORDINATE SOURCE** (2.80-2.84), beside
  the suite's 4K and measured-1080p literals. `widgetList` maps a label to
  one or more ids; `appendWidgets` resolves them every tick and publishes
  `wg_<name>_click_x/_y`, `_state`, `_resolved_by`, the rect and the sprite.
  A widget id is durable across RESOLUTIONS and fragile across CLIENT
  VERSIONS, so the suite falls back to its literals per lookup and logs it.
- **ACCEPT THE SPELLING THE SOURCE TOOL PRINTS.** The setting documented
  `160:38`; RuneLite's Widget Inspector, the only place these ids can be
  read, shows `160.38`. `split(":")` dropped the dotted form silently and
  the log read `parsed 0 widget(s): []` **on every startup from 2026-08-10 to
  2026-08-26** — configured, publishing nothing, never saying why. 2.80 takes
  `[:.]` and NAMES every rejected token, including duplicate labels. A count
  alone cannot tell "24 of 50 parsed" from "24 were written".
- **THE S / D PREFIX DOES NOT MATTER, THE BRACKET DOES.** Two numbers is
  `getWidget(group, child)`; three is that widget's child at the index, with
  `getDynamicChildren()` first and `getChild()` as fallback, so a 3-part id
  works either way. `S 160.25` is `160.25`, `D 15.3[0]` is `15.3.0`.
- **THE SAME ELEMENT LIVES IN A DIFFERENT WIDGET DEPENDING ON WHAT IS OPEN**,
  so one label takes a CHAIN separated by `|`, first with a real rectangle
  wins (2.82). Inventory slot 1 is `149.0.N` normally, `15.3.N` with the bank
  open, `467.0.N` at the GE — and **only the bank one has a rectangle for an
  EMPTY slot**. `_resolved_by` says which link answered, which is the only
  thing separating "the bank is open" from "the id has moved".
- **`not_found` IS NOT A DIAGNOSIS THIS PLUGIN CAN MAKE.** A widget whose
  interface is not loaded returns null from `getWidget()` exactly as a wrong
  id does. 2.81's `/widgets` said "the id has moved" and sent Josh checking
  four correct ids. 2.84 says "not loaded - its interface is closed, OR the id
  is wrong" and names the action that separates them. **Only `hidden` proves
  an id is right**, because the interface had to load for it to be there.
- **`@N` IS A CLICK INSET** — keep N% of the rectangle about its CENTRE
  (2.81). It shrinks the target and never moves it, for a glyph whose outer
  pixels are border (`164.34@80`) or a line of text with dead space either
  side (`182.8@50`). `click_x/click_y` is the post-inset point;
  `screen_x/screen_y` stays the raw centre so existing readers do not change
  meaning underneath them.
- **THE CAP IS A RUNAWAY STOP, NOT A BUDGET.** It was 24, which the first real
  list (50) blew straight past — silently, keeping the first 24 while the rest
  read as "not found", which is the wrong diagnosis entirely. 128 now, and it
  says so in the log when it bites. The real cost is ~16 lines of state feed
  each, so a long list is a choice, not an accident.
- **WIDGETS ARE THE SIXTH FIELD ON THE TEN ENTITY SETS** (2.83), not a
  parallel system: `set<N>Widgets` beside `set<N>Scenery`, merged the same
  way — the always-on box plus every enabled set. So `/filter?entityset=`
  already switches them, `/filter` reports and writes them by the existing
  `set<N><field>` pattern, and the tracker's settings tab picks the keys up
  by reflection with no work at all.
- **`/widgets` IS THE HUMAN VIEW** (2.81), one line per widget with the click
  point first. `/state` has the same data as 16 lines each, which at 50
  widgets is 800 lines nobody can test against. Every way of having nothing to
  show names WHICH way it is — off, logged out, list empty, nothing parsed,
  none resolving are five different repairs and an identical blank page.
- **NO WIDGET GEOMETRY IS PUBLISHED WHILE LOGGED OUT** — `appendWidgets` is
  behind `if (online && wantWidgets)`. There is no client interface to
  measure, so login-screen coordinates can never be widgets.

## Versioning and deploy

- Filename stays `GEVisualAidPlugin.java` — Java requires it to match the class.
  Only the internal version string bumps.
- The VMs pull from GitHub via `git pull --ff-only` in launch.bat at RuneLite
  start. **Commit and push directly** — never hand Josh a file to paste in.
- After pushing, restart RuneLite on s1 and confirm the change appears in
  `127.0.0.1:8081/state` before touching s2 or s3.
- HTTP is the live source; `.txt` mtime lags badly. Freshness signal is
  `pluginLastUpdateTick <= 5s`.

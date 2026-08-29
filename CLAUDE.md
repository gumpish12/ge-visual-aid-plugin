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

- **THE GAME CLOCK IS PUBLISHED AT LAST** (2.85). `client.getTickCount()` had
  been called since 2.27 for despawn timing and never emitted, while
  `Emergency_Screenshot.ahk` read game ticks off the colour of one pixel.
  `game_tick`, `game_tick_ms` and `game_tick_interval_ms` are stamped as the
  FIRST statement in `onGameTick`, before `updateSceneState` — everything
  after that point is latency this plugin added.
- **`game_tick_ms` IS THE FIELD THAT MATTERS, NOT `game_tick`.** A colour
  change carries no timestamp, so a consumer that notices it 18ms late cannot
  know it was late. A consumer reading `game_tick_ms` knows exactly when the
  tick began and can act at a fixed offset from the TICK rather than from the
  moment it looked.
- **NO TOGGLE ON THE TICK, DELIBERATELY**, against the master-toggle rule
  every entity family follows. That rule exists because a filter with its
  family off reads like a filter matching nothing; there is no filter here and
  nothing to gate — two field writes a tick. A switch whose only power is to
  make a free field absent is a way to break this silently, and with no toggle
  an absent `game_tick` has exactly one meaning: a plugin older than 2.85.
- **AN AGE BAKED INTO A CACHED STRING IS NOT AN AGE.** `/state` is built once
  per tick, cached, and handed to every reader, so `game_tick_age_ms` in it
  would freeze at build time and read as fresh for ever. `/state` therefore
  carries only absolute facts (`game_tick_ms`); the age exists ONLY on
  `/tick`, computed when the request is handled. Same trap as `scene_age_ms`,
  which is honest at write time and frozen thereafter.
- **`/tick` IS A SEPARATE DOOR FOR A REASON** (2.85). `/state` has the same
  numbers and is tens of kilobytes; a prayer flick reads this every 20ms, so
  it gets ~300 bytes, two volatile reads and one short lock. Same reasoning as
  `/widgets`: the data was already in `/state` and unusable at the rate its
  consumer needed it.
- **`tick_state` NAMES WHICH KIND OF NOTHING IT IS**, because they need
  different repairs and all look identical as a missing number: `no_tick_yet`
  (the plugin has not seen one since it started), `offline` (not LOGGED_IN, so
  GameTick has stopped firing — established in 2.3/2.4, nothing is wrong),
  `stale` (logged in and ticks are NOT arriving — lag or loading), `live`.
- **THE RAW INTERVAL HISTORY IS PUBLISHED, not just min/max/mean.** The last
  64 gaps are what let a consumer measure the SERVER's own jitter — the floor
  nothing can beat — separately from the jitter its own polling adds. Without
  the first number the second cannot be judged, and the pixel-versus-plugin
  question cannot be settled by measurement at all. The first tick after a
  login is skipped: its "gap" is however long the last session ended ago.

- **THE MOTHERLODE SACK IS A VARBIT, NOT A TIMER AND NOT AN ITEM COUNT**
  (2.86). 5558 is the sack's contents, 5556 says whether the bigger sack was
  bought, and RuneLite's own Motherlode plugin reads exactly those two, so
  `mlm_sack_space` needs no other plugin and cannot drift from the game. The
  pay-dirt is in NO item container while it is inside the machine, so there
  was nothing to count; a timer would have been a guess, and a broken strut
  makes every such guess wrong in the one situation that matters.
- **THE SACK COUNT KEEPS RISING FOR SEVERAL TICKS AFTER A DEPOSIT**, because
  the machine delivers over time. That lag is not noise to smooth away - it IS
  the answer to "has my deposit landed yet". `mlm_sack_still_ticks` says how
  long it has been steady and `mlm_sack_state` turns that into
  `settling` / `settled`. **`mlm_sack_witnessed` says whether this session has
  ever seen the number move at all** - never watch a number you have not
  observed changing, and now the feed says which kind you are looking at.
- **IT IS THE WATER WHEEL, NOT THE STRUT, THAT ONLY AN ID CAN READ.** 2.86
  claimed a broken strut was indistinguishable by name; Josh read a real one
  and it is not. Live, 2026-08-28, stood at the wheel:

  | id | name | actions |
  |---|---|---|
  | 26669 | `Strut` | none |
  | 26670 | `Broken strut` | `Hammer` |
  | 26671 | `Water wheel` | none |
  | 26672 | `Water wheel` | none |

  A strut changes its NAME and gains an ACTION when it breaks, so `#Hammer`
  finds one. **The wheel does neither** - same name, no actions on either,
  and 26672 is the stopped one. For the wheel the object id is the only
  thing in the client that separates turning from stopped.
- **`mlm_flow_state` IS READ OFF THE WHEELS, WITH THE STRUT COUNTS BESIDE IT.**
  The wheel is the thing that TURNS: a strut is the cause and the thing you
  would hammer, a stopped wheel is the effect, and the effect is what decides
  whether pay-dirt reaches the sack. Josh: one broken strut and its wheel
  stopped while the other kept going.
- **IDS ARE STILL USED FOR THE STRUTS, AND COUNTING IS THE REASON.** A
  `#Hammer` filter can only ever find BROKEN ones, and 0 from a filter is the
  ambiguous zero this suite keeps getting bitten by - it cannot separate "none
  broken" from "the filter is dead". `3 ok beside 1 broken` is a reading no
  dead filter can fake. Every id is a SYMBOL from `ObjectID`, so a rename in a
  future runelite-api fails the BUILD rather than matching nothing for ever -
  proved by renaming one and watching it fail.
- **`mlm_flow_state` HAS FOUR VALUES BECAUSE A WHEEL NOBODY CAN SEE IS NOT A
  TURNING WHEEL.** `unknown` (none tracked - not in the mine, or the scene has
  not been walked) is a different repair from `flowing`. `degraded` is some
  wheels stopped, `stopped` is all of them.
- **A STOPPED WHEEL IS NOT A REASON TO STOP DEPOSITING** (2.91, and 2.86-2.90
  had this backwards). Josh, watching one break rather than describing one:
  *"it can still deposit, and carry on mining, its just it sits in the hopper
  until its repaired then moves down to the sack."* **NOTHING IS LOST.** The
  hopper holds it and the machine works through it once the struts are fixed.
  `mlm_flow_state` says WHY the sack is not growing; it is not permission to
  put things in it.
- **`queued` WAS CALLED `flight_lost`, AND THE WORD WAS THE WHOLE BEHAVIOUR.**
  Neither number changed when it was renamed in 2.91. "Lost" reads as a reason
  to stop depositing and the skiller did exactly that, sitting on a full
  inventory in front of a hopper that would have taken it. "Queued" reads as a
  reason to carry on. **A field name is an instruction to every consumer that
  ever reads it** - this is the mislabel-rather-than-fail-loudly trap wearing a
  different hat, and it was self-inflicted.
- **AN EARLIER DESCRIPTION LOSES TO A LIVE OBSERVATION, EVERY TIME.** Josh
  described the broken-strut behaviour twice and the two accounts disagreed;
  the second came from watching it and is the one in the code. Ask what was
  SEEN, not what is believed - and when a correction arrives, change the field
  NAME as well as the logic, or the old belief keeps giving orders.
- **`127.0.0.1:8081/motherlode` IS THE HUMAN VIEW** (2.87). Josh, having
  ticked the setting: *"how do i watch the mlm sack count or flow state etc.
  its not in the skilling state"*. It never was - the skilling panel renders
  the families it was written for, and a new plugin family does not appear
  there by itself. The numbers are in `/state` as sixty-odd `mlm_` lines
  inside tens of kilobytes, which is not a thing anyone can watch a sack fill
  in. Same reasoning as `/widgets` and `/tick`. Switched off, Scene & Tiles
  off, logged out, no reading yet and not-in-the-mine are five different
  repairs and it names which one.
- **THERE IS NO VEIN COUNTDOWN AND THERE CANNOT BE ONE.** How much pay-dirt a
  vein has left is server-side: no varbit, no widget, no timer, and RuneLite's
  own Motherlode plugin publishes none either - it only highlights the veins
  that are active now. Anything printed as one would be invented, and an
  invented number mislabels rather than failing loudly. `mlm_vein_life_source`
  = `none` says it in the feed so a consumer never infers it from silence.
- **WHAT IS KNOWABLE IS MEASURED INSTEAD.** A depleting vein is replaced by a
  depleted object (26665-26668) on the same tile and replaced back on respawn,
  and both swaps arrive as spawn events. The OBSERVED gap gives a real eta for
  every depleted vein. `mlm_eta_source` says whether enough samples exist for
  it to mean anything; below three it is -1, not a plausible-looking guess.
  A tile that was ALREADY depleted when the scene was seeded is timed from
  first sight, not from depletion, so it is never sampled and its eta is
  withheld - `mlm_dep_N_timed_from` says which kind of number you have.
- **THE MOTHERLODE SCENE TRACKING IS EVENT DRIVEN, NOT A TILE WALK.** The
  struts sit ~25 tiles from the ore faces, so a radius that saw them from the
  veins would scan most of the scene every tick. Spawn events cost nothing
  when nothing changes. The price: a scene already loaded when the toggle went
  on has fired no events, so ONE full-scene walk seeds the maps on enable and
  on any change of `getMapRegions()` - which covers login, hop and teleport
  with no region table.
- **`mlm_sack_delta` IS THE LAST CHANGE, NOT THE DEPOSIT.** Josh, live:
  `+3` then `+5`. From one number there is no telling "5 went in and landed
  at once" from "27 went in and 5 have arrived". `MLM_SETTLE_TICKS` rests on
  exactly that difference, so 2.88 publishes `mlm_sack_history` - the last 16
  changes as `tick:+delta`, newest first, RAW. **No mean**: the 207-tick gap
  between two test deposits is not an arrival interval, and averaging it in
  produces a confident number describing nothing. Same call as the game
  clock publishing its intervals rather than a summary of them.
- **`MLM_SETTLE_TICKS = 4` IS STILL A GUESS AND THE FEED SAYS SO.** It is
  published as `mlm_sack_settle_ticks` and labelled UNMEASURED on
  `/motherlode`. A settle that fires in a GAP BETWEEN ARRIVALS reports room
  in the sack that is about to be taken - so the threshold has to clear the
  biggest gap in a real full-inventory deposit, and that number comes from
  `mlm_sack_history`, not from me.
- **`motherlode_log.txt` LOGS CHANGES, NEVER STATE** (2.89, toggle
  `motherlodeLog`, read it at `/motherlode?log`, wipe with
  `/motherlode?log=clear`). A tick-by-tick dump buries the handful of
  transitions that answer anything. **The INVENTORY half is why it exists**:
  a line reading `Pay-dirt 27 -> 0` immediately before the SACK lines says
  how much went in, which no sack reading alone can. Item names come from
  `ItemComposition`, so there is no item table to rot - whatever is carried
  names itself.
- **THE LOG RESOLVES ITS FILE BEFORE DRAINING ITS BUFFER.** Draining first
  and then finding nowhere to write throws the lines away silently, which
  looks exactly like a logger that is switched on and recording nothing. The
  no-folder case warns once and says which it is.
- **AN OFF-TEST THAT RUNS ON THE FIRST FILL CANNOT FAIL.** `mlmLogMachinery`
  is silent on its first fill by design, so asserting "switched off writes
  nothing" there passes with the on/off gate deleted. It did, for one round.
  A checker must exercise the guard in the state where the guard is the only
  thing stopping output - and the same goes for the flush: draining the
  buffer looks identical whether the lines reached disk or were binned, so
  the assertion had to become "the FILE contains the line", driven through a
  `Proxy` over `GEVisualAidConfig` into a temp folder.
- **A DEPOSIT REACHES THE SACK TWELVE TICKS LATER, AS ONE CHANGE.** Measured
  twice in Josh's run log, 2026-08-28:

  | | trip 1 | trip 2 |
  |---|---|---|
  | `Pay-dirt 27 -> 0` | t=386 | t=665 |
  | `SACK +27` | t=398 | t=677 |

  No trickle, no partial arrivals. **Which made 2.89's `mlm_sack_state` wrong
  in the dangerous direction**: for those twelve ticks the count had not moved
  for ages, so it read `settled` while 27 pay-dirt was in the air, and a
  consumer asking "is there room for another load" was told there were 27 more
  spaces than there were. **No steadiness threshold can fix that** - the gap it
  measures starts BEFORE the deposit. The deposit itself has to be the trigger.
- **THE DEPOSIT IS VISIBLE IN THE INVENTORY, AND NOWHERE ELSE.** 2.90 watches
  pay-dirt leaving (`mlm_paydirt_inv`), carries the amount as
  `mlm_flight_amount`, and publishes **`mlm_sack_space_settled`** = space minus
  what is already on its way. That is the number to act on; plain
  `mlm_sack_space` is a trap for twelve ticks after every deposit.
- **`flight_lost` IS A REAL STATE AND IT MEANS THE MACHINE IS STOPPED.** Josh:
  with the struts broken *"the pay-dirt sits in the water and doesnt go to the
  bag"*. A deposit that has not arrived within `mlm_flight_max_ticks` (30,
  against a measured 12) says so in its own word rather than being rounded
  down to `settled`. `mlm_flight_last_ticks` republishes the real delivery time
  every trip, so the constant stays checkable against reality.
- **COLLECTING IS INSTANT AND SYNCHRONOUS; DEPOSITING IS NOT.** The sack and
  the inventory move on the SAME tick when you Search it - 62 -> 34 -> 6 -> 0.
  That asymmetry is the whole reason the deposit needed its own tracker and
  the collect needs none.
- **THE SACK FILLS YOUR FREE SLOTS. IT IS NOT "28 A TIME"** (Josh's
  correction, 2026-08-28). Two things bend it, and both were visible in the
  same run:
  - **Gems stay behind.** An uncut gem from mining cannot go in the hopper, so
    it occupies a slot from the moment it is mined until the next bank. At
    t=681 one Uncut emerald was already carried, so only 27 slots were free.
    Over several trips gems accumulate and every trip carries less pay-dirt.
  - **Golden nuggets stack.** That first collect put 28 UNITS into 27 SLOTS -
    the nuggets took one slot for two. So units removed from the sack can
    EXCEED the free slots, and neither number predicts the other.

  So a module must never compute an expected collect. **Search, bank, re-read
  `mlm_sack_count`, repeat while it is above zero** - the same confirm-rather
  -than-predict rule as everywhere else here. `inv_free_slots` already exists
  in the feed; nothing new was needed for this.
- **"INVENTORY FULL" IS ZERO FREE SLOTS, NOT 27 PAY-DIRT.** The gems riding
  along are why. `Pay-dirt 27 -> 0` in the run log looked like a clean 27 only
  because the 28th slot held the emerald.
- **A SCENE REBUILD FIRES A TRANSITION FOR EVERY VEIN IN THE MINE.** Tick 402
  of Josh's run: ~190 `active -> depleted` followed by ~190 `depleted ->
  active`, against one or two a tick while actually mining. Reported
  individually they buried the run in 380 lines, and **any non-zero gap among
  them would have entered the respawn statistics as a real observation** - only
  the `gap <= 0` guard stopped it. 2.90 queues vein transitions and reports
  them at the TICK BOUNDARY: over `MLM_RESYNC_TRANSITIONS` (12) in one tick is
  a resync, logged as one line and sampled not at all.
- **THE VEIN RESPAWN IS TOO WIDE TO PLAN A CLICK ON.** The three genuine
  samples were 34t, 102t and 175t - a five-fold spread. `mlm_respawn_history`
  and `mlm_respawn_spread_ticks` publish it raw beside the mean, `/motherlode`
  warns when the spread exceeds 2x, and `MLM_ETA_MIN_SAMPLES` went 3 -> 8. **A
  module should find another active vein, not wait for this one.**
- **`./gradlew mlmCheck` IS A REAL CHECKER, NOT A SMOKE TEST.** It reflects
  into the compiled helpers rather than re-implementing them, and it was
  proved red thirteen times before being believed: 13-bit tile packing, a
  missing vein id, an unbumped version string, the wrong sack capacity, an
  unseen wheel counted as flowing, the two wheel ids swapped, the settle
  threshold off by one, the arrival ring walked oldest-first, an off-by-one
  in that walk, the logger recording while switched off, the logger spamming
  its first fill, the flush binning its lines, and the log file opened
  without append, the flight state losing to the steadiness threshold (the
  2.89 bug, now a regression test), a lost delivery rounded down to settled,
  a resync threshold that let a scene rebuild through, a flight window
  shorter than the measured delivery, and the 2.91 rename (it asserts the
  string `flight_lost` appears nowhere, so the old word cannot creep back).
  **Two of them passed the first time they were asked** - see the off-test
  rule above.

## Versioning and deploy

- Filename stays `GEVisualAidPlugin.java` — Java requires it to match the class.
  Only the internal version string bumps.
- The VMs pull from GitHub via `git pull --ff-only` in launch.bat at RuneLite
  start. **Commit and push directly** — never hand Josh a file to paste in.
- After pushing, restart RuneLite on s1 and confirm the change appears in
  `127.0.0.1:8081/state` before touching s2 or s3.
- HTTP is the live source; `.txt` mtime lags badly. Freshness signal is
  `pluginLastUpdateTick <= 5s`.

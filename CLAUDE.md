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

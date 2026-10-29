// Exercises the REAL compiled 2.86 helpers by reflection, not a copy of them.
// A checker that re-implements what it checks passes for the same reason the
// code fails, which is how test_widget_toggle spent months comparing the half
// of the block that did not contain the bug.
package com.ge.gevisualaid;

import java.lang.reflect.Method;
import net.runelite.api.coords.WorldPoint;

public class MlmCheck
{
    private static int fails = 0;

    private static int countLines(String s)
    {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    // The last version whose behaviour this file was actually read against.
    // Raise it when a change here is checked against a new plugin version -
    // never as a chore to make a bump go green, because it does not go red.
    private static final String VERSION_FLOOR = "2.97";

    // N.NN: digits, one dot, digits. Spelled out rather than done with a
    // regex because a backslash has to survive three layers of tooling to
    // reach this file, and it did not on the first attempt.
    private static boolean wellFormed(String v)
    {
        int dots = 0;
        for (int i = 0; i < v.length(); i++)
        {
            char ch = v.charAt(i);
            if (ch == 46) { dots++; if (i == 0 || i == v.length() - 1) return false; }
            else if (ch < 48 || ch > 57) return false;
        }
        return dots == 1;
    }

    // "2.100" is a later version than "2.97", which a string compare gets
    // backwards. Two-part numeric, nothing cleverer - wellFormed() has
    // already established there is exactly one dot with digits either side.
    private static long versionRank(String v)
    {
        int dot = v.indexOf(46);
        long major = Long.parseLong(v.substring(0, dot));
        long minor = Long.parseLong(v.substring(dot + 1));
        return major * 1000000L + minor;
    }

    private static int countOccurrences(String hay, String needle)
    {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static void eq(String what, Object got, Object want)
    {
        boolean ok = (got == null) ? want == null : got.equals(want);
        if (!ok) { fails++; System.out.println("FAIL " + what + ": got " + got + ", want " + want); }
    }

    public static void main(String[] a) throws Exception
    {
        Class<?> c = GEVisualAidPlugin.class;

        Method key   = c.getDeclaredMethod("mlmKey", int.class, int.class, int.class);
        Method kx    = c.getDeclaredMethod("mlmKeyX", long.class);
        Method ky    = c.getDeclaredMethod("mlmKeyY", long.class);
        Method kp    = c.getDeclaredMethod("mlmKeyPlane", long.class);
        Method state = c.getDeclaredMethod("mlmVeinState", int.class);
        Method dist  = c.getDeclaredMethod("mlmDist", WorldPoint.class, long.class);
        for (Method m : new Method[]{ key, kx, ky, kp, state, dist }) m.setAccessible(true);

        // ---- 1. tile key round trip -------------------------------------
        // The whole design rests on identifying a vein by its world tile
        // rather than by a list index, so a key that collides or truncates
        // would silently merge two veins into one.
        int[] xs = { 0, 1, 1234, 3743, 3760, 8191, 16383 };
        int[] ys = { 0, 1, 5000, 5673, 5680, 12345, 16383 };
        int   round = 0;
        for (int p = 0; p <= 3; p++)
            for (int x : xs)
                for (int y : ys)
                {
                    long k = (Long) key.invoke(null, x, y, p);
                    eq("keyX " + x + "," + y + "," + p, kx.invoke(null, k), x);
                    eq("keyY " + x + "," + y + "," + p, ky.invoke(null, k), y);
                    eq("keyPlane " + x + "," + y + "," + p, kp.invoke(null, k), p);
                    round++;
                }
        System.out.println("round trips checked: " + round);

        // ---- 2. no two distinct tiles share a key -----------------------
        // A collision would make one strut hide another, and the count is
        // what mlm_flow_state is built on.
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        int collisions = 0;
        for (int p = 0; p <= 1; p++)
            for (int x = 3700; x < 3800; x++)
                for (int y = 5600; y < 5700; y++)
                    if (!seen.add((Long) key.invoke(null, x, y, p))) collisions++;
        eq("collisions over the whole mine footprint", collisions, 0);
        System.out.println("distinct keys over 2 planes x 100 x 100 tiles: " + seen.size());

        // ---- 3. vein classification -------------------------------------
        // Ids cross-checked twice: against runelite-api's ObjectID symbols
        // (which is where the code gets them) and against Josh's own wildcard
        // read of the mine, which showed 26661-26664 carrying Mine.
        eq("26661 single active", state.invoke(null, 26661), 1);
        eq("26662 left active",   state.invoke(null, 26662), 1);
        eq("26663 middle active", state.invoke(null, 26663), 1);
        eq("26664 right active",  state.invoke(null, 26664), 1);
        eq("26665 depleted",      state.invoke(null, 26665), 2);
        eq("26666 depleted",      state.invoke(null, 26666), 2);
        eq("26667 depleted",      state.invoke(null, 26667), 2);
        eq("26668 depleted",      state.invoke(null, 26668), 2);
        eq("26669 strut is not a vein", state.invoke(null, 26669), 0);
        eq("26674 hopper is not a vein", state.invoke(null, 26674), 0);
        eq("357 crate is not a vein",    state.invoke(null, 357),   0);

        // ---- 4. distance ------------------------------------------------
        WorldPoint me = new WorldPoint(3743, 5673, 0);
        eq("dist to self",  dist.invoke(null, me, key.invoke(null, 3743, 5673, 0)), 0);
        eq("dist 3 east",   dist.invoke(null, me, key.invoke(null, 3746, 5673, 0)), 3);
        eq("dist diagonal", dist.invoke(null, me, key.invoke(null, 3746, 5677, 0)), 4);
        eq("dist with no player location", dist.invoke(null, null, 0L), 9999);

        // ---- 5. the two sack sizes are the ones javap read out of the
        //         client jar, and the settle threshold is what the field
        //         doc claims. A silent edit to either changes what
        //         mlm_sack_space means.
        java.lang.reflect.Field f;
        f = c.getDeclaredField("MLM_SACK_SIZE");       f.setAccessible(true);
        eq("MLM_SACK_SIZE", f.get(null), 108);
        f = c.getDeclaredField("MLM_SACK_LARGE_SIZE"); f.setAccessible(true);
        eq("MLM_SACK_LARGE_SIZE", f.get(null), 189);
        f = c.getDeclaredField("MLM_SETTLE_TICKS");    f.setAccessible(true);
        eq("MLM_SETTLE_TICKS", f.get(null), 4);
        f = c.getDeclaredField("MLM_ETA_MIN_SAMPLES"); f.setAccessible(true);
        eq("MLM_ETA_MIN_SAMPLES", f.get(null), 8);

        // ---- 5b. the logout gate (2.98) ---------------------------------
        // logout_open WAS isVisible(182, 0) - InterfaceID.Logout.UNIVERSE,
        // the interface ROOT - and on 2026-09-28 it read FALSE with the panel
        // open on screen, measured beside the button widget reporting ok and a
        // rectangle. A root is not the panel; it can be loaded and hidden while
        // its children draw. The skiller opened the panel fifty times, asked
        // this field, was told it was shut, and clicked the opener again.
        //
        // TEST THE THING THE CONSUMER IS ABOUT TO CLICK. Asserted three ways,
        // because each fails on its own: the old expression is gone, the new
        // one is a SYMBOL (so a rename fails the build rather than matching
        // nothing for ever), and the reading names its own source.
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/ge/gevisualaid",
                        "GEVisualAidPlugin.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        // COMMENTS STRIPPED FIRST. The first run of this went red against the
        // comment three lines above the fix, which NAMES the old expression in
        // order to explain why it is gone. An assertion that reads prose is
        // asserting the documentation, and this repo has been bitten by that
        // in the other direction often enough.
        StringBuilder code = new StringBuilder();
        for (String line : src.split("\\n"))
            if (!line.trim().startsWith("//")) code.append(line).append("\\n");
        String srcCode = code.toString();
        eq("nothing reads the logout panel off the interface root 182.0",
                srcCode.contains("isVisible(182, 0)"), false);

        // ── 2.127 HOLD YOUR GROUND ──────────────────────────────────────────
        // Consumes a "Walk here" while the hotkey is held, so an attack click
        // that misses a moving NPC does nothing instead of walking the
        // character off. Every assertion here is about the BLAST RADIUS: this
        // handler runs on every menu click in the game, and a mistake in it
        // either makes the client unclickable or strands the character.
        eq("hold ground: the held flag is volatile (key thread writes, client thread reads)",
                srcCode.contains("private volatile boolean holdGroundDown"), true);
        // THE ONLY THING IT MAY EVER CONSUME IS A WALK. Asserted as ORDER, not
        // presence: the walk-here test must come BEFORE the consume, or a
        // reordering would start cancelling Attack, Take and bank clicks with
        // the key down and the plugin would look like a broken mouse.
        int iWalk    = srcCode.indexOf("\"walk here\".equals(");
        int iConsume = srcCode.indexOf("event.consume()");
        eq("hold ground: it tests for a walk at all", iWalk > 0, true);
        eq("hold ground: and does so BEFORE consuming anything",
                iWalk > 0 && iConsume > iWalk, true);
        // THE CHEAP GATE IS FIRST. holdGroundDown is false on virtually every
        // click of the session, so it must be the first thing read - ahead of
        // two config calls - or this costs reflection on every menu click.
        int iFlag = srcCode.indexOf("if (!holdGroundDown)");
        int iCfg  = srcCode.indexOf("config.holdGroundEnabled()");
        eq("hold ground: the boolean gate is read before the config",
                iFlag > 0 && iCfg > iFlag, true);
        // IT FAILS OPEN. shutDown must both detach the listener and clear the
        // flag: a flag left true with nothing able to clear it would suppress
        // walking for the rest of the session with nothing saying why.
        eq("hold ground: shutDown unregisters the key listener",
                srcCode.contains("unregisterKeyListener(holdGroundListener)"), true);
        eq("hold ground: and clears the held flag so it cannot stick",
                srcCode.contains("holdGroundDown = false"), true);
        // AND THE STATE IS PUBLISHED. A cancelled click and a click that did
        // nothing are identical in the game; hold_ground_state names which,
        // and hold_ground_blocks proves it has ever fired.
        eq("hold ground: /state names which kind of nothing it is",
                srcCode.contains("hold_ground_state="), true);
        eq("hold ground: and counts the blocks, so the number can be watched moving",
                srcCode.contains("hold_ground_blocks="), true);
        // SCOPED TO THE ASSIGNMENT. Asking whether the symbol appears ANYWHERE
        // passed with the gate rewritten as a bare isVisible(182, 8) - because
        // logoutOpenSource mentions the symbol two lines below. Proved by
        // making exactly that mutation and watching it go green. Pin the
        // guard, never the vocabulary around it.
        int gateAt = srcCode.indexOf("boolean logoutOpen");
        String gate = gateAt < 0 ? ""
                : srcCode.substring(gateAt, Math.min(srcCode.length(), gateAt + 200));
        // Positively, and with no regex: the call itself has to name the
        // symbol. A backslash does not survive this repo's patch tooling, and
        // an escape that arrives mangled is a worse bug than the six
        // characters it saves.
        eq("the gate calls isVisible on the SYMBOL, not on a bare number",
                gate.contains("isVisible(InterfaceID.Logout.LOGOUT"), true);
        eq("the reading says which component answered",
                srcCode.contains("logout_open_source=" + "\" + logoutOpenSource"), true);

        // ---- 6. the version string -------------------------------------
        // THIS USED TO BE AN EQUALITY AGAINST A LITERAL, AND IT ROTTED.
        // "the version actually moved" is not something a checker can know:
        // only a person knows whether a change deserved a bump. What the
        // equality actually did was go RED ON EVERY BUMP, so it sat failing
        // from 2.92 through 2.96 - five versions - while nobody read it, and
        // a checker that is always red is a checker nobody runs.
        //
        // A FLOOR fails on the two things that are genuinely wrong - a
        // mangled string and a version going BACKWARDS - and is silent on an
        // ordinary bump. It is raised deliberately, not as maintenance.
        f = c.getDeclaredField("PLUGIN_OUTPUT_VERSION"); f.setAccessible(true);
        Object pv = f.get(null);
        String vs = (pv == null) ? "" : pv.toString();
        if (!wellFormed(vs))
        {
            fails++;
            System.out.println("FAIL PLUGIN_OUTPUT_VERSION: \"" + vs
                    + "\" is not N.NN - the panel and every consumer print it raw");
        }
        else if (versionRank(vs) < versionRank(VERSION_FLOOR))
        {
            fails++;
            System.out.println("FAIL PLUGIN_OUTPUT_VERSION: " + vs
                    + " is BELOW the floor " + VERSION_FLOOR
                    + " - a version went backwards, or the floor is wrong");
        }

        // ---- 7. machinery classification (2.87) -------------------------
        // Josh stood at the wheel and read all four out of a live scene:
        //   Strut 26669 no actions / Broken strut 26670 Hammer
        //   Water wheel 26671 no actions / Water wheel 26672 no actions
        // The two WHEELS share a name and have no actions at all, so the id
        // is the only thing in the client that separates turning from
        // stopped. That is what mlm_flow_state is built on.
        Method kind = c.getDeclaredMethod("mlmMachineryKind", int.class);
        kind.setAccessible(true);
        eq("26669 strut ok",      kind.invoke(null, 26669), 1);
        eq("26670 strut broken",  kind.invoke(null, 26670), 2);
        eq("26671 wheel turning", kind.invoke(null, 26671), 3);
        eq("26672 wheel stopped", kind.invoke(null, 26672), 4);
        eq("26674 hopper is not machinery", kind.invoke(null, 26674), 0);
        eq("13589 cogs are not machinery",  kind.invoke(null, 13589), 0);

        // ---- 8. flow state, including the scene Josh actually reported ---
        Method flow = c.getDeclaredMethod("mlmFlowState", int.class, int.class);
        flow.setAccessible(true);
        eq("no wheels tracked is UNKNOWN, never flowing", flow.invoke(null, 0, 0), "unknown");
        eq("both wheels turning",  flow.invoke(null, 2, 0), "flowing");
        eq("Josh 2026-08-28: 1 of 2 stopped", flow.invoke(null, 2, 1), "degraded");
        eq("both stopped = the cycle halts", flow.invoke(null, 2, 2), "stopped");
        eq("more broken than tracked still stops", flow.invoke(null, 2, 3), "stopped");

        // ---- 9. sack state naming ---------------------------------------
        // One namer for the feed line and the /motherlode page, so they
        // cannot disagree about whether a deposit has finished landing.
        Method sack = c.getDeclaredMethod("mlmSackStateName",
                int.class, int.class, int.class, int.class);
        sack.setAccessible(true);
        eq("varbit unreadable",       sack.invoke(null, -1, 10, 0, -1), "unreadable");
        eq("never seen a change",     sack.invoke(null, 41, -1, 0, -1), "unknown");
        eq("just moved is settling",  sack.invoke(null, 41,  0, 0, -1), "settling");
        eq("3 ticks steady still settling", sack.invoke(null, 41, 3, 0, -1), "settling");
        eq("4 ticks steady is settled",     sack.invoke(null, 41, 4, 0, -1), "settled");
        eq("long steady is settled",  sack.invoke(null, 41, 99, 0, -1), "settled");

        // ---- 9b. THE BUG JOSH'S RUN LOG FOUND ---------------------------
        // t=386 Pay-dirt 27 -> 0, t=398 SACK +27. For those twelve ticks the
        // count had not moved for ages, so 2.89 answered "settled" while 27
        // pay-dirt was in the air - and a consumer asking "is there room for
        // another load" was told there were 27 more spaces than there were.
        eq("2.89 answered settled here, which was the bug",
                sack.invoke(null, 35, 99, 27, 0), "in_flight");
        eq("still in flight at the measured 12 ticks",
                sack.invoke(null, 35, 99, 27, 12), "in_flight");
        eq("in flight right up to the timeout",
                sack.invoke(null, 35, 99, 27, 30), "in_flight");
        // 2.91: QUEUED, not lost. Josh, watching a broken machine rather than
        // describing one: "it can still deposit ... it sits in the hopper
        // until its repaired then moves down to the sack". The word decided
        // the behaviour - "lost" made the skiller refuse a deposit that would
        // have worked, and sit on a full inventory.
        eq("past the timeout it is QUEUED, not lost and not settled",
                sack.invoke(null, 35, 99, 27, 31), "queued");
        eq("flight with no stamp is queued too",
                sack.invoke(null, 35, 99, 27, -1), "queued");
        eq("nothing anywhere still says flight_lost",
                new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get("src/main/java/com/ge/gevisualaid",
                                "GEVisualAidPlugin.java")),
                        java.nio.charset.StandardCharsets.UTF_8)
                    .contains("\"flight_lost\""), false);
        eq("once it lands, settled again",
                sack.invoke(null, 62, 99, 0, 40), "settled");
        eq("unreadable outranks everything",
                sack.invoke(null, -1, 99, 27, 0), "unreadable");

        // ---- 10. the sack arrival ring buffer (2.88) --------------------
        // A ring buffer bug always lives in the index arithmetic, and here it
        // would drop the MOST RECENT arrival - the one that decides whether a
        // deposit has finished landing.
        Method hist = c.getDeclaredMethod("mlmSackHistory",
                int[].class, int[].class, int.class, int.class);
        hist.setAccessible(true);

        int[] t = new int[16], d = new int[16];
        eq("empty history is empty", hist.invoke(null, t, d, 0, 0), "");

        // Josh's two real deposits, in the order they were written.
        t[0] = 420; d[0] = 3;
        t[1] = 627; d[1] = 5;
        eq("newest first", hist.invoke(null, t, d, 2, 2), "627:+5,420:+3");
        eq("one entry", hist.invoke(null, t, d, 1, 1), "420:+3");

        // Wrapped: pos has come back round to 1, so the newest is index 0 and
        // the oldest is index 1. Getting this backwards is the whole point.
        int[] t2 = new int[3], d2 = new int[3];
        t2[0] = 300; d2[0] = 9;
        t2[1] = 100; d2[1] = 1;
        t2[2] = 200; d2[2] = 4;
        eq("wrapped ring reads newest first", hist.invoke(null, t2, d2, 1, 3),
                "300:+9,200:+4,100:+1");

        // A count larger than the array must not read past it.
        eq("count beyond capacity is clamped",
                hist.invoke(null, t2, d2, 1, 99), "300:+9,200:+4,100:+1");

        // The sack can only go up in normal play, but a collect empties it and
        // that is a real negative the page must not print as "+-105".
        int[] t3 = new int[4], d3 = new int[4];
        t3[0] = 900; d3[0] = -105;
        eq("a collect prints as a negative", hist.invoke(null, t3, d3, 1, 1), "900:-105");

        // ---- 11. the run logger (2.89) ----------------------------------
        // Driven on a REAL plugin instance. client and config are null on it,
        // which is the point: every path here is wrapped, so a logger that
        // throws on the first strut would be caught now rather than by an
        // empty file after an hour of mining.
        GEVisualAidPlugin p = GEVisualAidPlugin.class.getDeclaredConstructor().newInstance();
        java.lang.reflect.Field fOn  = c.getDeclaredField("mlmLogOn");
        java.lang.reflect.Field fBuf = c.getDeclaredField("mlmLogPending");
        fOn.setAccessible(true); fBuf.setAccessible(true);

        Method logMach = c.getDeclaredMethod("mlmLogMachinery",
                String.class, java.util.Map.class, java.util.Map.class, int.class);
        logMach.setAccessible(true);

        java.util.Map<Long, Integer> cur  = new java.util.HashMap<>();
        java.util.Map<Long, Integer> prev = new java.util.HashMap<>();
        long wheelA = (Long) key.invoke(null, 3748, 5674, 0);
        long wheelB = (Long) key.invoke(null, 3741, 5680, 0);

        // First fill after a seed: adopted SILENTLY. Two lines saying "the
        // wheels are fine" at the top of every run is how a log stops being
        // read.
        fOn.setBoolean(p, true);
        cur.put(wheelA, 26671); cur.put(wheelB, 26671);
        logMach.invoke(p, "WHEEL", cur, prev, 4);
        eq("first fill is silent", fBuf.get(p).toString(), "");
        eq("first fill still adopts the snapshot", prev.size(), 2);

        // Switched OFF, a REAL change is still not recorded. This has to run
        // AFTER the snapshot is seeded: on the first fill nothing is written
        // either way, so an off-test there passes with the gate removed and
        // proves nothing. (It did exactly that until this line was moved.)
        fOn.setBoolean(p, false);
        cur.put(wheelA, 26672);
        logMach.invoke(p, "WHEEL", cur, prev, 4);
        eq("switched off, a real break is not recorded", fBuf.get(p).toString(), "");
        fOn.setBoolean(p, true);
        cur.put(wheelA, 26671);
        logMach.invoke(p, "WHEEL", cur, prev, 4);
        ((StringBuilder) fBuf.get(p)).setLength(0);

        // Now break one, exactly as Josh found it: 26671 -> 26672.
        cur.put(wheelA, 26672);
        logMach.invoke(p, "WHEEL", cur, prev, 4);
        String out = fBuf.get(p).toString();
        boolean gotBreak = out.contains("3748,5674,0") && out.contains("ok -> BROKEN")
                        && out.contains("26672") && out.contains("WHEEL");
        eq("a wheel breaking is logged, once", gotBreak, true);
        eq("and only the one that changed", countLines(out), 1);

        // Repairing it logs the other way.
        ((StringBuilder) fBuf.get(p)).setLength(0);
        cur.put(wheelA, 26671);
        logMach.invoke(p, "WHEEL", cur, prev, 4);
        eq("a repair is logged", fBuf.get(p).toString().contains("BROKEN -> ok"), true);

        // Nothing changing writes nothing. A log that repeats itself every
        // tick is a log nobody can find the real line in.
        ((StringBuilder) fBuf.get(p)).setLength(0);
        logMach.invoke(p, "WHEEL", cur, prev, 4);
        eq("a quiet tick is silent", fBuf.get(p).toString(), "");

        // Losing the scene is said out loud rather than read as "still fine".
        ((StringBuilder) fBuf.get(p)).setLength(0);
        cur.remove(wheelB);
        logMach.invoke(p, "WHEEL", cur, prev, 4);
        eq("a wheel leaving the scene is logged",
                fBuf.get(p).toString().contains("left the scene"), true);

        Method flush = c.getDeclaredMethod("mlmLogFlush");
        flush.setAccessible(true);

        // A flush with no output folder (config is null here) must not throw
        // and must not leave the buffer to grow for ever.
        flush.invoke(p);
        eq("flush with nowhere to write drains rather than throwing",
                fBuf.get(p).toString(), "");

        // ---- 11b. DOES IT ACTUALLY WRITE A FILE? ------------------------
        // The only question that matters about a logger, and the one the
        // buffer assertions above cannot answer - draining the buffer looks
        // identical whether the lines reached disk or were thrown away.
        // GEVisualAidConfig is an interface, so a Proxy is enough to hand the
        // real flush path a real folder.
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("mlmcheck");
        Object cfgStub = java.lang.reflect.Proxy.newProxyInstance(
                GEVisualAidConfig.class.getClassLoader(),
                new Class<?>[]{ GEVisualAidConfig.class },
                (proxy, m, args2) -> {
                    if (m.getName().equals("outputFolder")) return tmp.toString();
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return m.getName().equals("motherlodeLog");
                    if (rt == int.class)     return 0;
                    if (rt == String.class)  return "";
                    return null;
                });
        java.lang.reflect.Field fCfg = c.getDeclaredField("config");
        fCfg.setAccessible(true);
        fCfg.set(p, cfgStub);

        Method logOne = c.getDeclaredMethod("mlmLog", String.class, String.class);
        logOne.setAccessible(true);
        fOn.setBoolean(p, true);
        logOne.invoke(p, "SACK", "count=8  +5");
        flush.invoke(p);

        java.io.File written = new java.io.File(tmp.toFile(), "motherlode_log.txt");
        eq("the log file exists after a flush", written.exists(), true);
        String body = written.exists()
                ? new String(java.nio.file.Files.readAllBytes(written.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8)
                : "";
        eq("the line reached the file", body.contains("count=8  +5"), true);
        eq("and carries its kind",      body.contains("SACK"), true);
        eq("and a tick stamp",          body.contains(" t="), true);

        // Appended, not overwritten - a run is one file, not the last line of
        // one.
        logOne.invoke(p, "VEIN", "3742,5677,0  active -> depleted");
        flush.invoke(p);
        String body2 = new String(java.nio.file.Files.readAllBytes(written.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        eq("the second line is appended", countLines(body2), 2);
        eq("the first line survived",     body2.contains("count=8  +5"), true);

        written.delete();
        tmp.toFile().delete();

        // ---- 12. small formatters ---------------------------------------
        Method tile = c.getDeclaredMethod("mlmTileText", long.class);
        Method sign = c.getDeclaredMethod("mlmSigned", int.class);
        tile.setAccessible(true); sign.setAccessible(true);
        eq("tile text", tile.invoke(null, wheelA), "3748,5674,0");
        eq("a gain is signed",  sign.invoke(null, 5),   "+5");
        eq("a collect is not", sign.invoke(null, -105), "-105");
        eq("no change",        sign.invoke(null, 0),    "0");

        // ---- 13. the respawn list (2.90) --------------------------------
        // Josh's three genuine samples. A mean over a five-fold spread is a
        // confident number describing nothing, so the raw list is published
        // beside it and this checks the same ring arithmetic as the sack.
        Method ints = c.getDeclaredMethod("mlmIntList", int[].class, int.class, int.class);
        ints.setAccessible(true);
        int[] rs = new int[32];
        rs[0] = 102; rs[1] = 34; rs[2] = 175;
        eq("respawn list newest first", ints.invoke(null, rs, 3, 3), "175,34,102");
        eq("empty respawn list", ints.invoke(null, rs, 0, 0), "");
        eq("count beyond capacity is clamped",
                ints.invoke(null, new int[]{ 7 }, 1, 99), "7");

        // ---- 14. the scene-storm threshold ------------------------------
        // ~190 vein transitions arrived in tick 402 of Josh's run, against
        // one or two a tick while actually mining. Anything over the
        // threshold is a rebuild and must not reach the respawn statistics.
        f = c.getDeclaredField("MLM_RESYNC_TRANSITIONS"); f.setAccessible(true);
        int resync = (Integer) f.get(null);
        eq("a real mining tick is under the threshold", 2 <= resync, true);
        eq("the observed storm is over it", 190 > resync, true);

        f = c.getDeclaredField("MLM_FLIGHT_MAX_TICKS"); f.setAccessible(true);
        int flightMax = (Integer) f.get(null);
        eq("the flight window clears the measured 12 ticks", flightMax > 12, true);

        f = c.getDeclaredField("MLM_PAYDIRT"); f.setAccessible(true);
        eq("the item name is the one the log printed", f.get(null), "Pay-dirt");

        // ---- 15. the portfolio location split (2.105) --------------------
        //
        // Josh: a 1m item "will show as a hold item and then in the ge when
        // its being sold, its not removed from the hold prediction". The row
        // is right to stay - it is unsold, so the profit is unrealised - and
        // what was missing is WHERE the quantity sits. portfolioGeShare is
        // the boundary, and the real compiled one is called here rather than
        // a copy, for the reason at the top of this file.
        Method geShare = c.getDeclaredMethod("portfolioGeShare", long.class, long.class);
        geShare.setAccessible(true);
        eq("nothing listed",            geShare.invoke(null, 0L,  10L), 0L);
        eq("part of it listed",         geShare.invoke(null, 4L,  10L), 4L);
        eq("all of it listed",          geShare.invoke(null, 10L, 10L), 10L);
        eq("no holding, nothing to it", geShare.invoke(null, 5L,  0L),  0L);

        // THE TWO THAT MATTER. Copilot reports what the CLIENT holds, which
        // can exceed what the portfolio TRACKS (their own
        // getNotInPortfolioQuantity names that case). Unclamped, the hold
        // share goes negative and the amber band on the capital chart
        // SUBTRACTS - so listing a big stack would make capital in use appear
        // to drop. A negative share would do the same at the other end.
        eq("more listed than tracked is clamped", geShare.invoke(null, 25L, 10L), 10L);
        eq("a negative reading cannot subtract",  geShare.invoke(null, -3L, 10L), 0L);

        // The split must PARTITION the holding at every one of those points,
        // because portfolio_ge_value + portfolio_hold_value is checked
        // against Copilot's own portfolio_assets_value in the feed. Asserted
        // as the identity rather than as six more numbers: a clamp that
        // merely returns something in range still breaks the sum.
        long[][] cases = { {0,10}, {4,10}, {10,10}, {25,10}, {-3,10}, {5,0}, {3,3} };
        for (long[] cse : cases)
        {
            long share = (Long) geShare.invoke(null, cse[0], cse[1]);
            eq("ge+hold partitions " + cse[0] + " of " + cse[1],
                    share + (cse[1] - share), cse[1]);
            eq("the listed share is never negative " + cse[0] + "/" + cse[1], share >= 0L, true);
            eq("the held share is never negative " + cse[0] + "/" + cse[1], cse[1] - share >= 0L, true);
        }

        // The feed has to actually carry it, and the logged-out branch has to
        // say WHY rather than reporting a confident zero: "nothing listed" and
        // "nobody looked" need different repairs and read identically as 0.
        String pfSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/ge/gevisualaid/GEVisualAidPlugin.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String k : new String[]{ "portfolio_split_source", "portfolio_ge_value",
                                      "portfolio_hold_value", "portfolio_ge_qty",
                                      "portfolio_split_check", "portfolio_item_split" })
        {
            eq("the feed declares " + k, pfSrc.contains("\"" + k + "=\""), true);
        }
        eq("logged out, the split is unknown and not zero",
                pfSrc.contains("\"portfolio_ge_value=-1\\n\""), true);
        eq("the split checks itself against the assets total",
                pfSrc.contains("geValue + holdValue == s[3]"), true);
        // The ROW list is capped for readability; the VALUES must not be, or a
        // 41-item portfolio would disagree with Copilot and the check would
        // blame Copilot for this plugin truncating. Same call as 2.104.
        eq("the value totals are not inside the cap",
                pfSrc.contains("geValue   += unit * geShare;"), true);


        // ================================================================
        // 2.108 - SLAYER AND SPELLBOOK
        //
        // The match word replaces a 130-row monster table, so it is the one
        // piece here that can mislabel rather than fail loudly: a word too
        // broad attacks the wrong thing, a word too narrow walks past three
        // variants of the right one. It is asserted against real task names.
        // ================================================================
        String slSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/ge/gevisualaid/GEVisualAidPlugin.java")),
                java.nio.charset.StandardCharsets.UTF_8);

        eq("aberrant spectres -> spectre",
                GEVisualAidPlugin.slayerMatchWord("Aberrant spectres"), "spectre");
        eq("abyssal demons -> demon",
                GEVisualAidPlugin.slayerMatchWord("Abyssal demons"), "demon");
        eq("bloodveld keeps its name",
                GEVisualAidPlugin.slayerMatchWord("Bloodveld"), "Bloodveld");
        // "Bats" -> "Bat" is right: the npcs are "Bat" and "Giant bat". The
        // word boundary, not the length, is what stops it matching "Batman".
        eq("bats -> bat",
                GEVisualAidPlugin.slayerMatchWord("Bats"), "Bat");
        // The five the naive strip got wrong, measured against the 152 task
        // names in RuneLite's own enum. "Bos" is the dangerous one: not a
        // word, so it fails by matching something unrelated, not by matching
        // nothing. These are regression tests, not examples.
        eq("dwarves -> dwarf", GEVisualAidPlugin.slayerMatchWord("Dwarves"), "Dwarf");
        eq("elves -> elf",     GEVisualAidPlugin.slayerMatchWord("Elves"), "Elf");
        eq("wolves -> wolf",   GEVisualAidPlugin.slayerMatchWord("Wolves"), "Wolf");
        eq("jellies -> jelly", GEVisualAidPlugin.slayerMatchWord("Jellies"), "Jelly");
        eq("a double s is not a plural",
                GEVisualAidPlugin.slayerMatchWord("The Cave Kraken Boss"), "Boss");
        eq("no task is no word", GEVisualAidPlugin.slayerMatchWord(""), "");
        eq("null is no word", GEVisualAidPlugin.slayerMatchWord(null), "");

        // The whole claim of this release: one word off the task name finds
        // every variant, including the two the wiki lists separately and the
        // superior. If this stops holding, the table argument comes back.
        for (String v : new String[]{ "Aberrant spectre", "Deviant spectre",
                                      "Abhorrent spectre", "Repugnant spectre",
                                      "Aberrant spectres" })
        {
            eq("the word finds " + v,
                    GEVisualAidPlugin.slayerNameMatches(v, "spectre"), true);
        }
        // ...and does NOT find a word that merely starts the same. A
        // substring match here would attack anything "spectral" in range.
        eq("spectral is not a spectre",
                GEVisualAidPlugin.slayerNameMatches("Spectral creature", "spectre"), false);
        eq("demonic is not a demon",
                GEVisualAidPlugin.slayerNameMatches("Demonic gorilla", "demon"), false);
        eq("an empty word matches nothing",
                GEVisualAidPlugin.slayerNameMatches("Aberrant spectre", ""), false);
        eq("a null name matches nothing",
                GEVisualAidPlugin.slayerNameMatches(null, "spectre"), false);

        // THE BUILT-IN WIDGET IS SPELLED FROM THE SYMBOL. Written as the
        // literal 218.47 it would compile for ever against an api that had
        // already moved the component - the 2.100 failure exactly - so the
        // value is checked AND the source is checked for the symbol.
        eq("the built-in high alch widget resolves to 218.47",
                GEVisualAidPlugin.WG_BUILTIN_HIGH_ALCH, "high_alch=218.47");
        eq("...and is spelled from the symbol, not the number",
                slSrc.contains("InterfaceID.MagicSpellbook.HIGH_ALCHEMY >>> 16"), true);
        eq("the merged widget list always carries it",
                slSrc.contains("wgts.append(WG_BUILTIN_HIGH_ALCH)"), true);

        // Logged out, slayer_count MUST be -1. Zero is a real value meaning
        // the task is finished, so a confident zero at the login screen tells
        // a consumer to go and buy a new task. Same trap as 2.105.
        eq("logged out, the count is unknown and not zero",
                slSrc.contains("\"slayer_count=-1\\nslayer_count_initial=-1\\n\""), true);
        eq("logged out, the points are unknown and not zero",
                slSrc.contains("slayer_points=-1"), true);

        // EVERY FIELD THE ONLINE BRANCH PUBLISHES MUST EXIST OFFLINE TOO. A
        // field that appears and disappears with the login state is read by
        // the far end as "this plugin is too old", which sends the repair to
        // entirely the wrong machine.
        int offFrom = slSrc.indexOf("slayer_state=offline");
        int offTo   = slSrc.indexOf("slayer_seen_source=offline") + 30;
        eq("the offline slayer branch is findable", offFrom > 0 && offTo > offFrom, true);
        String offline = (offFrom > 0 && offTo > offFrom) ? slSrc.substring(offFrom, offTo) : "";
        java.util.regex.Matcher mk = java.util.regex.Pattern
                .compile("\"(slayer_[a-z_]+)=\"")
                .matcher(slSrc);
        java.util.Set<String> onlineKeys = new java.util.LinkedHashSet<>();
        while (mk.find()) onlineKeys.add(mk.group(1));
        eq("the online branch declares a reasonable number of fields",
                onlineKeys.size() >= 15, true);
        for (String k : onlineKeys)
        {
            if (k.equals("slayer_state")) continue;
            eq("offline also publishes " + k, offline.contains(k + "="), true);
        }

        // The centre is a REFERENCE. No page this plugin serves may call it
        // a click point: the suite randomises inside the rect, and a
        // pixel-perfect centre click is a signature no human produces. The
        // rule was unwritten until 2026-10-18 and 2.108 broke it twice.
        eq("no page calls the centre a point to click",
                slSrc.contains("is the point to click"), false);
        eq("/widgets says to randomise inside the rect",
                slSrc.contains("Randomise inside the RECT"), true);

        // 2.108b: the signature only reads while the tab is OPEN, so a shut
        // tab must fall back to a LEARNED pairing, never an assumed one, and
        // must still say "unknown" before the tab has been seen once.
        eq("a shut tab falls back to a remembered id",
                slSrc.contains("source = \"remembered_id\""), true);
        eq("...which is only ever written from a real signature reading",
                slSrc.contains("if (id >= 0 && id < sbLearned.length) sbLearned[id] = name;"), true);
        eq("the learned map is published so it can be checked",
                slSrc.contains("spellbook_id_map="), true);

        // A count timed from FIRST SIGHT reads identically to one that has
        // stalled, and warning on it accuses the task every time you log in.
        // Checked on the ONLINE expression, not the bare key: the offline
        // branch publishes the same key as a literal, so searching for the
        // key alone stayed true with the online field deleted. Proved.
        eq("the count says which kind of number it is",
                slSrc.contains("slCountSeen ? \"change\" : \"first_sight\""), true);
        eq("the stall warning only fires on a real change",
                slSrc.contains("if (fromChange && Integer.parseInt(still) > 300)"), true);

        // THE OFFLINE BRANCH MUST BE REACHABLE. GameTick stops firing when
        // logged out, so the only caller inside updateSceneState can never
        // run it - the block froze and reported a live task at the login
        // screen. Asserting the branch EXISTS is what 2.108 already did and
        // it passed while the bug was live; assert it is CALLED from the
        // logout path, beside mlmReset.
        // Searched FORWARD FROM mlmReset, not from the top of the file: a
        // plain indexOf found the startUp seed added later and declared the
        // logout path broken. Anchor on the thing you mean.
        int mlmAt   = slSrc.indexOf("mlmReset();");
        int resetAt = mlmAt < 0 ? -1 : slSrc.indexOf("updateSpellbookAndSlayer(false)", mlmAt);
        eq("the logout path resets slayer too",
                resetAt > 0 && (resetAt - mlmAt) < 900, true);

        // ...AND SEEDED AT STARTUP. Starting at the login screen yields no
        // GameStateChanged and no GameTick, so neither caller runs and the
        // feed sat on "no_reading_yet" - observed live. Both call sites are
        // required; asserting one would have passed with the other missing.
        eq("startUp seeds the offline block",
                slSrc.contains("updateSpellbookAndSlayer(false);\n        overlayManager.add"), true);
        eq("there are TWO offline call sites, not one",
                slSrc.split("updateSpellbookAndSlayer\\(false\\)", -1).length - 1, 2);

        // The spellbook NAME must not come from the varbit. Nobody has
        // watched all four of its values, so a 0..3 map would be a guess
        // printed as a fact - and the raw id has to go out beside the name
        // so the mapping stays measurable from the feed without a deploy.
        eq("the spellbook name is read off the interface",
                slSrc.contains("source = \"signature\""), true);
        eq("the raw varbit goes out beside it",
                slSrc.contains("\"spellbook_id=\" + id"), true);
        eq("the four books are named by symbols",
                slSrc.contains("InterfaceID.MagicSpellbook.ICE_BARRAGE")
                        && slSrc.contains("InterfaceID.MagicSpellbook.VENGEANCE")
                        && slSrc.contains("InterfaceID.MagicSpellbook.WARD_OF_ARCEUUS"), true);

        // The stale constant must not creep back. net.runelite.api.Varbits
        // still exports SUPERIOR_ENABLED = 5362 and it matches neither
        // gameval name for the feature.
        eq("the deprecated superior varbit is not used",
                slSrc.contains("Varbits.SUPERIOR_ENABLED"), false);
        eq("both halves of the superior question are published",
                slSrc.contains("SLAYER_UNLOCK_SUPERIORMOBS")
                        && slSrc.contains("SLAYER_TOGGLEOFF_SUPERIORMOBS"), true);

        // Slayer reads must sit ABOVE the Scene & Tiles gate - they read no
        // scene, and gating them there makes them vanish for an unrelated
        // reason. Order inside updateSceneState is the whole guarantee.
        int slCallAt = slSrc.indexOf("updateSpellbookAndSlayer(client.getGameState()");
        int slGateAt = slSrc.indexOf("if (!config.sceneTrackingEnabled())");
        eq("the slayer read runs before the scene gate",
                slCallAt > 0 && slGateAt > slCallAt, true);

        // ================================================================
        // 2.109 - /widgettree
        // ================================================================

        // THE WALK IS CLIENT STATE. Doing it in the HTTP handler is the one
        // thing this repo has always refused to do; the handler queues and
        // waits, updateSceneState walks.
        eq("the tree is walked from the client thread",
                slSrc.contains("if (online) applyPendingWidgetTree();"), true);
        eq("...and the handler only queues",
                slSrc.contains("wtQuery = q;"), true);

        // REFUSE, DO NOT TRUNCATE. A truncated dump reads exactly like a
        // complete one that happens not to contain what you wanted - which
        // is the 2.81 widget-cap failure with a bigger number.
        eq("an unfiltered walk is refused, not truncated",
                slSrc.contains("REFUSED: no filter given."), true);
        eq("the cap announces itself when it bites",
                slSrc.contains("*** CAP HIT at "), true);

        // RULE ZERO. This page exists to be read by someone deciding what to
        // click, so every row carries the RECT. A row that printed only a
        // centre would be the rule broken at the source.
        eq("every row carries a rect",
                slSrc.contains("out.append(\"  rect=\")"), true);
        eq("...and says so explicitly when there is none",
                slSrc.contains("rect=none"), true);

        // Logged out there is no tree AND no GameTick to walk it on, so a
        // queued request would wait the full timeout and report "timeout" -
        // which reads as a broken endpoint, not a client at the login
        // screen. Two different repairs, so two different words.
        eq("logged out is answered by the handler, not by timing out",
                slSrc.contains("widgettree_state=offline"), true);
        eq("a real timeout is still a distinct state",
                slSrc.contains("widgettree_state=timeout"), true);

        // THE NAMES ARE GENERATED FROM THE JAR, NOT TYPED. A hand-written
        // id->name map is the forbidden kind of table; reflecting over
        // InterfaceID cannot disagree with the api it was built against.
        eq("symbolic names come from reflection over InterfaceID",
                slSrc.contains("InterfaceID.class.getDeclaredClasses()"), true);

        // 2.109a: /widgettree printed RAW CANVAS bounds under the same field
        // name /widgets uses for desktop pixels - 62,5,86,29 against
        // 3370,1837,3406,1873 for the SAME button, seen live. Both look like
        // coordinates and the small one clicks the corner of the screen.
        // It must use the SAME transform, captured where buildSceneBlock
        // computes it; a second copy of that arithmetic is the 2.104 trap.
        eq("the tree converts to desktop pixels",
                slSrc.contains("toScreenX(b.x, wtOx, wtDsx)"), true);
        eq("...using the captured transform, not its own copy",
                slSrc.contains("wtCanvasOk = canvasOk;"), true);
        eq("raw canvas bounds are never printed as rect",
                slSrc.contains("rect=no_transform(canvas_off)"), true);
        // A scrolled-out widget still HAS a rectangle, just somewhere else -
        // possibly over the game world, where clicking it walks the player.
        eq("a scrolled-out widget is named, not offered as a target",
                slSrc.contains("OFFSCREEN-DO-NOT-CLICK"), true);

        // A HIDDEN widget's bounds are not where it will be SHOWN. Measured
        // on 2026-10-19 against the live client: high alch read
        // 3733,1837,3769,1873 with the magic tab open and 91,42,127,78 with
        // it shut - the shut figure is a position inside the spellbook
        // panel's own layout and was never laid out against the canvas.
        // Printed as "rect" it is a plausible wrong click point sitting
        // beside the word HIDDEN. /widgets answers -1 for this case; the
        // tree must not answer something that looks usable.
        // Asserted on the GUARD, not on the string. The first version looked
        // for out.append("  rect=hidden") and passed happily with the branch
        // changed to if(false) - the dead code is still there to find. That
        // is the 2.108d mistake exactly: testing that code EXISTS rather
        // than that it RUNS.
        int hidGuard = slSrc.indexOf("                if (hidden)");
        int hidEmit  = slSrc.indexOf("out.append(\"  rect=hidden\")");
        eq("a hidden widget offers no rect",
                hidGuard > 0 && hidEmit > hidGuard && (hidEmit - hidGuard) < 700, true);
        eq("...but keeps the size, which stays true",
                slSrc.contains("  size=") && slSrc.contains("b.width * (wtCanvasOk ? wtDsx : 1)"), true);

        // ================================================================
        // 2.110 - THE ALCH RULE
        //
        // Josh's words: "only alch unless the ge is 30% above". Alch is the
        // DEFAULT; a high GE price is the exception. Calling the real
        // compiled method, not a copy - the 2.105 reason.
        // ================================================================
        // Rune sq shield shape: alch 25600-ish, GE below it -> alch.
        eq("GE below the alch value -> alch",
                GEVisualAidPlugin.alchVerdict(25600, 22335, true, 30), "alch");
        // Exactly at the threshold is NOT above it, so it still alchs. The
        // boundary is stated rather than left to whoever reads > vs >=.
        eq("exactly at the threshold -> alch",
                GEVisualAidPlugin.alchVerdict(1000, 1300, true, 30), "alch");
        eq("one gp over the threshold -> keep",
                GEVisualAidPlugin.alchVerdict(1000, 1301, true, 30), "keep");
        // A 0% margin means "alch whenever the GE does not beat the alch
        // value", which must still work - a margin is not a minimum.
        eq("a zero margin still decides",
                GEVisualAidPlugin.alchVerdict(1000, 1001, true, 0), "keep");

        // UNTRADEABLE CANNOT LOSE TO THE GE, because it has no GE price to
        // lose to. This is the branch that keeps a Crystal shard from being
        // compared against a meaningless number.
        eq("an untradeable item alchs regardless of any price",
                GEVisualAidPlugin.alchVerdict(1000, 999999, false, 30), "alch");

        // UNKNOWN IS NEVER A VERDICT. An item whose price could not be read
        // must not be alched on the strength of a missing number - the
        // -1-versus-0 rule applied to a decision instead of a field.
        eq("no alch value is not a reason to alch",
                GEVisualAidPlugin.alchVerdict(0, 500, true, 30), "no_alch_value");
        eq("an unreadable GE price is not a reason to alch",
                GEVisualAidPlugin.alchVerdict(1000, -1, true, 30), "unknown_ge_price");

        // The terms must travel WITH the verdict, or it cannot be checked -
        // the 2.104 rule. A page that printed only "alch" would be asking to
        // be trusted.
        eq("/item publishes the margin it used",
                slSrc.contains("alch_margin_pct="), true);
        eq("/item publishes both terms beside the verdict",
                slSrc.contains("_ha_price=") && slSrc.contains("_ge_price="), true);
        // Both numbers come from the client, never from a table.
        eq("the alch value is the game's own",
                slSrc.contains("getHaPrice()"), true);
        eq("the GE price is RuneLite's live one",
                slSrc.contains("itemManager.getItemPrice(id)"), true);

        // ================================================================
        // 2.110 - /widgettree?save=
        // ================================================================
        // The label IS the annotation, so it has to come from the symbol.
        eq("a symbolic name becomes the label",
                GEVisualAidPlugin.widgetSaveLabel("MagicSpellbook.HIGH_ALCHEMY", 218, 47, -1),
                "MagicSpellbook_HIGH_ALCHEMY");
        eq("an unnamed widget still gets a usable label",
                GEVisualAidPlugin.widgetSaveLabel(null, 218, 47, -1), "w218_47");
        eq("an indexed child keeps its index",
                GEVisualAidPlugin.widgetSaveLabel(null, 149, 0, 3), "w149_0_3");
        // A label is used verbatim in a comma-separated config box, so a
        // separator inside one would split an entry and silently shift
        // everything after it.
        eq("a label can never contain a separator",
                GEVisualAidPlugin.widgetSaveLabel("Odd.Name With,Comma", 1, 2, -1)
                        .matches("[A-Za-z0-9_]+"), true);

        // IT MERGES. Overwriting the box destroys whatever another activity
        // had and persists that to the RuneLite profile - the hazard
        // CLAUDE.md records for /filter?scenery=.
        eq("save merges by default",
                slSrc.contains("if (!replace)"), true);
        eq("replace has to be asked for",
                slSrc.contains("boolean replace = \"1\".equals(p.get(\"replace\"))"), true);
        // A duplicate label is dropped by parseWidgets, so one written here
        // would match nothing for ever while looking configured.
        eq("a label collision is reported, not written",
                slSrc.contains("COLLIDED (kept what was there"), true);
        eq("the save cap says when it bites",
                slSrc.contains("NOT SAVED - cap "), true);
        eq("an unknown save target is refused, not guessed",
                slSrc.contains("SAVE REFUSED: unknown target"), true);
        // Config writes go through the queue and land on the client thread,
        // never straight from the HTTP handler.
        int saveAt = slSrc.indexOf("SAVED to ");
        int queueAt = slSrc.indexOf("synchronized (pendingConfig) { pendingConfig.add(new String[]{ key, spec.toString() }); }");
        eq("the write is queued for the client thread",
                queueAt > 0 && saveAt > queueAt, true);

        // /state MUST CARRY THE VERSION FROM THE FIRST MILLISECOND. The HTTP
        // server binds before the first state is built, and the placeholder
        // used to be "status=starting" alone - 200 OK with no
        // plugin_output_version, which 2.99 made the field machines compare.
        // "Just started" and "a plugin too old to have the field" then read
        // identically. Caught by the dev launcher printing "UP - plugin , ,".
        eq("the placeholder state carries the version",
                slSrc.contains("\"plugin_output_version=\" + PLUGIN_OUTPUT_VERSION + \"\\n\"\n            + \"game_state=STARTING\\n\""), true);

        // AN UNKNOWN ITEM ID RETURNS A STUB NAMED "null" - the four-letter
        // STRING. Observed live: id 99999999 published name=null, state=ok,
        // ha_price=0, ge_price=0 - a row that reads as a real item worth
        // nothing and would enter a drop list as one.
        eq("the \"null\" stub name is caught",
                slSrc.contains("name.equals(\"null\")"), true);
        eq("an unknown item is -1, not a confident zero",
                slSrc.contains("_ha_price=-1\\n") && slSrc.contains("_alch=unknown_item\\n"), true);
        // ClientThread, not the HTTP thread: off-thread this read does not
        // throw, it just yields nothing, which is how it shipped reading
        // no_such_item for every id while getItemPrice beside it worked.
        // 2.112: WHETHER A PICK-UP NEEDS A FREE SLOT. The wiki marks noted
        // drops, but coins, runes and arrows stack natively and no wiki
        // column says so - isStackable() is the game's own answer, and
        // getLinkedNoteId() is what an inventory check must match, because
        // the note and the item are different ids.
        eq("/item says whether an item stacks",
                slSrc.contains("_stackable=\").append(stk)"), true);
        eq("...and gives the noted form's id, which is a different item",
                slSrc.contains("getLinkedNoteId()"), true);
        // Both branches, or the field comes and goes with the item existing.
        eq("the unknown-item branch publishes them too",
                slSrc.contains("_stackable=false\\n\"") && slSrc.contains("_noted_id=-1\\n\""), true);

        // 2.113: THE SHORTEST PATH LINK MUST NOT GIVE UP FOR THE SESSION.
        // It had `if (spLinkTried && spPlugin == null) return false;`, so a
        // single early miss - looking before a hub plugin finished loading -
        // latched path_state=no_plugin until RuneLite restarted. Caught live
        // with the plugin's OWN list reading "Shortest Path|enabled|active"
        // beside its own path_plugin_found=false; both read the same
        // getPlugins(), so the only way both are true is a link that never
        // ran again. Third time in this file after 2.75 and 2.92: a NEGATIVE
        // result is not a fact about the session.
        // Tested against the source with // comments REMOVED. A "must not
        // contain" check cannot be run over a file whose comments quote the
        // very line being banned - and the comment above that fix quotes it
        // deliberately, because a future reader needs to see what was
        // wrong. Second time this has bitten: the deprecated-varbit check
        // dodged it by searching for "Varbits.SUPERIOR_ENABLED" instead of
        // the bare name, which worked but only by luck of spelling.
        String slCode = slSrc.replaceAll("(?m)^\\s*//.*$", "");
        eq("the shortest path link never latches off",
                slCode.contains("if (spLinkTried && spPlugin == null) return false;"), false);
        eq("...and spLinkTried only throttles the log now",
                slSrc.contains("if (!spLinkTried) log.info(\"GEVisualAid: Shortest Path plugin not found yet"), true);
        eq("...and a later loss can be reported again",
                slSrc.contains("spLinkTried = false;   // so a later loss is reported again"), true);

        // 2.114: a /config write made at the LOGIN SCREEN answered "queued"
        // and then sat there, because applyPendingConfig only runs from
        // onGameTick and GameTick does not fire while logged out. "Queued"
        // reads exactly like a write that worked. Found turning on rune
        // tracking after Josh's mage session stalled on empty runes.
        eq("a config write drains on the client thread, not the next tick",
                slSrc.contains("clientThread.invoke(this::applyPendingConfig)"), true);

        // 2.115: A KODAI WAND IS NEITHER A STAFF NOR A TOME. The supplies
        // test was `staff || tome`, so a Kodai was skipped and water read
        // ZERO - which with rune tracking on is rune_cast_remaining=0 and an
        // instant FALSE "out of runes". The cache has no param for this
        // (checked ParamID in 1.13.2) and the only other statement of it is
        // the spellbook row, which exists only while the magic tab is open.
        // So it stays a heuristic, and the two things that make one
        // acceptable are required instead: say what it concluded, and let
        // the operator override it.
        eq("a wand can supply runes",
                slSrc.contains("nm.contains(\"wand\")"), true);
        eq("the Kodai is named, because its name does not carry its element",
                slSrc.contains("if (nm.contains(\"kodai\")) inf.put(\"water\", Boolean.TRUE);"), true);
        eq("the feed says what it concluded",
                slSrc.contains("\"rune_infinite=\"") && slSrc.contains("\"rune_infinite_from=\""), true);
        eq("...and from which item, so a wrong guess is visible",
                slSrc.contains("infFrom = safeItemName(it.getId());"), true);
        // The override is applied AFTER the heuristic or it would not win.
        int heurAt = slSrc.indexOf("nm.contains(\"kodai\")");
        int ovrAt  = slSrc.indexOf("config.runeInfiniteExtra()");
        eq("the operator override is applied last, so it always wins",
                heurAt > 0 && ovrAt > heurAt, true);

        // 2.116: slayer_npc_filter published "<word>#Attack" from 2.108, and
        // parseFilters is an if/else over the token - a leading '#' is an
        // ACTION, all digits an ID, anything else a NAME. So that string was
        // read as the name "gargoyle#attack" and matched nothing for ever,
        // while looking exactly like a working filter. The field exists to be
        // pasted somewhere, which makes a plausible-but-dead value the worst
        // possible output.
        eq("the npc filter is a bare name, not name#action",
                slCode.contains("String filter = word;"), true);
        eq("...and the dead form cannot come back",
                slCode.contains("word + \"#Attack\""), false);

        // 2.117: SHORTEST PATH CHANGED restartPathfinding's SIGNATURE.
        //   was  restartPathfinding(int, Set<Integer>)
        //   now  restartPathfinding(String, int, Targets)
        // The plugin was still FOUND - the class name matched - so this
        // surfaced as path_state=no_plugin, blaming a plugin that was
        // enabled and active, while Josh's MM2 run walked nowhere for an
        // afternoon. Both signatures are tried, OLD FIRST, because a VM
        // pinned to an older RuneLite gets an older Shortest Path from the
        // hub (2.99) and has to keep working.
        eq("the old signature is still tried",
                slCode.contains("getMethod(\"restartPathfinding\", int.class, java.util.Set.class)"), true);
        eq("...and the new one is the fallback",
                slCode.contains("getMethod(\"restartPathfinding\", String.class, int.class, targets)"), true);
        eq("Targets.at adapts the same packed points",
                slCode.contains("targets.getMethod(\"at\", java.util.Collection.class)"), true);
        // ONE call site for both, or the two callers each learn which build
        // is installed and drift apart at the next change.
        eq("both callers go through one adapter",
                slCode.contains("spRestartCall(-1, new HashSet<Integer>())")
                        && slCode.contains("spRestartCall(start, ends)"), true);
        eq("the feed names which API answered",
                slCode.contains("\"path_api=\""), true);

        eq("the item read runs on the client thread",
                slSrc.contains("clientThread.invoke(() -> {"), true);
        eq("...and the handler waits for it rather than guessing",
                slSrc.contains("done.await(3, java.util.concurrent.TimeUnit.SECONDS)"), true);

        // ================================================================
        // 2.118 - THE GAME UPDATE COUNTDOWN
        //
        // Calling the real compiled parser, not a copy of it. -1 is the
        // only "no" answer: 0 is a real countdown value meaning the
        // shutdown is now, and a consumer that cannot tell those apart
        // stops flipping for ever on a client that never saw a warning.
        // ================================================================
        eq("the observed line parses",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: 40:35"), 2435);
        eq("a single-digit minute parses",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: 0:59"), 59);
        eq("one minute out - the number the flipper acts on",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: 1:00"), 60);
        eq("zero is a real reading, not a failure",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: 0:00"), 0);
        eq("an hours field is handled rather than misread",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: 1:02:03"), 3723);
        // The chat area is full of other people's text. Anything that is
        // not the game's own line must read as -1, not as a number.
        eq("an unrelated chat line is not a countdown",
                GEVisualAidPlugin.parseSystemUpdateSeconds("Bob: whats the system update in a bit?"), -1);
        eq("the prefix with no clock after it",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: soon"), -1);
        eq("null is handled",
                GEVisualAidPlugin.parseSystemUpdateSeconds(null), -1);
        eq("a single number is not a clock",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: 40"), -1);
        eq("three-digit parts are refused rather than guessed at",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: 400:35"), -1);
        // Trailing markup or punctuation must not take the whole line down.
        eq("trailing text after the clock is ignored",
                GEVisualAidPlugin.parseSystemUpdateSeconds("System update in: 12:34 </col>"), 754);

        // SCANNED, NOT INDEXED. It sat on child [0] the day it was seen and
        // it is a PM chat line, so traffic moves it. A fixed index would
        // quietly read somebody else's message - the go_<label>_0 trap.
        eq("the PM container is reached by its SYMBOL, not a bare 163",
                slSrc.contains("InterfaceID.PmChat.CONTAINER"), true);
        // SCOPED TO THE FUNCTION. A bare contains() passed with this very
        // loop gutted, because an identical "for (int i = 0; i < kids.length"
        // lives elsewhere in the plugin - an assertion matched on text that
        // exists in more than one place is an assertion about the file, not
        // about the thing. Proved by the mutation going green the first time.
        int suAt = slCode.indexOf("private String buildSystemUpdateBlock");
        String suBody = (suAt < 0) ? ""
                : slCode.substring(suAt, Math.min(slCode.length(), suAt + 3000));
        eq("the system-update block was found at all", suAt >= 0, true);
        eq("the children are scanned rather than indexed",
                suBody.contains("for (int i = 0; i < kids.length; i++)"), true);
        eq("and \"nothing to report\" is told apart from \"could not look\"",
                slCode.contains("system_update_state=none") && slCode.contains("system_update_state=unreadable"), true);

        // ================================================================
        // 2.118 - THE LOGIN SCREEN OK BOX
        //
        // There is no widget for it - /widgettree returns nothing at all
        // while logged out - so it is computed. The real arithmetic, not a
        // copy.
        // ================================================================
        int[] okBox = GEVisualAidPlugin.loginOkRect(2288, 1370);
        eq("the box exists on the measured canvas", okBox != null, true);
        if (okBox != null)
        {
            // RULE ZERO: a RECT, never a point. Width and height both real.
            eq("it is a rectangle, not a point", okBox[2] > okBox[0] && okBox[3] > okBox[1], true);
            // Measured: the button centre sat on the canvas centre.
            eq("it is centred on the canvas horizontally",
                    (okBox[0] + okBox[2]) / 2, 2288 / 2);
            eq("it is the measured width", okBox[2] - okBox[0], 147);
            eq("it is the measured height", okBox[3] - okBox[1], 41);
            // The hover Josh gave: desktop 1689,482 -> canvas 1127,299.
            // Two independent measurements, and the box has to contain it.
            eq("it contains the point Josh hovered",
                    okBox[0] <= 1127 && 1127 <= okBox[2]
                 && okBox[1] <= 299  && 299  <= okBox[3], true);
        }
        // A canvas that cannot hold the dialog gets NOTHING, not a
        // plausible rectangle off the top of the screen.
        eq("a canvas too short returns null", GEVisualAidPlugin.loginOkRect(2288, 100), null);
        eq("a canvas too narrow returns null", GEVisualAidPlugin.loginOkRect(10, 1370), null);
        eq("a zero canvas returns null", GEVisualAidPlugin.loginOkRect(0, 0), null);
        eq("a negative canvas returns null", GEVisualAidPlugin.loginOkRect(-1, -1), null);
        // It must be computed from a LIVE canvas read. The cached figures
        // are frozen while logged out - measured 2026-10-20, the feed still
        // said 2288x1370 after the window had been shrunk - and a click
        // built from those aims at a window that is no longer there.
        eq("the box reads the canvas live rather than the tick cache",
                slCode.contains("client.getCanvas()") && slCode.contains("buildLoginGeometryState"), true);
        eq("the feed says what the box is based on",
                slCode.contains("login_ok_basis="), true);

        // ================================================================
        // 2.118 - update_required CAN SEE THE LOGIN SCREEN AT LAST
        //
        // It was a text match over getLoginScreenMessage(), which is ALWAYS
        // EMPTY - there is no API for the login screen text. Measured on
        // 2026-10-20 it published false while the screen said "please
        // restart your client". A field that cannot see the thing it is
        // named after still answers, confidently.
        // ================================================================
        eq("update_required asks the client, not only a string",
                slCode.contains("safeLoginIndex() == 9"), true);
        eq("...and only while logged out, where that index means anything",
                slCode.contains("lastGameState != GameState.LOGGED_IN && safeLoginIndex() == 9"), true);

        // 2.118 - server_restart_seconds was DEAD and the flipper waited on it.
        // Flipping.ahk has had the behaviour since V9.04 and it never fired:
        // the old reader asked widget 229,1 (the countdown is at 163.0[0]) and
        // its regex kept the colon, so parseInt("40:35") threw into an empty
        // catch. Both silent, and -1 reads as "no restart scheduled".
        eq("the restart countdown no longer asks widget 229",
                slCode.contains("client.getWidget(229, 1)"), false);
        eq("it shares the one scan and the one parser",
                slCode.contains("return parseSystemUpdateSeconds(findSystemUpdateLine(c)[0]);"), true);
        eq("and the scan exists in exactly one place",
                countOccurrences(slCode, "String[] findSystemUpdateLine"), 1);
        // ================================================================
        // 2.119 - AM I IN COMBAT (the slayer in-combat gate)
        //
        // Josh: "it wont let me attack a different one if im in the middle
        // of combat". A click the game refuses is worse than no click - the
        // module believes it switched target and fights the wrong thing.
        //
        // IT MUST BE A TIMER. getInteracting() goes null between attacks
        // while the fight is plainly still on, so an instant read says
        // "clear" several times a fight - exactly when a click would be
        // wrong. Driving the real compiled rule.
        // ================================================================
        eq("mid-fight, a fresh reading is in combat",
                GEVisualAidPlugin.combatFromAge(0), true);
        eq("a gap between attacks is still in combat",
                GEVisualAidPlugin.combatFromAge(2400), true);
        eq("exactly at the timeout is still in combat - the boundary is stated",
                GEVisualAidPlugin.combatFromAge(GEVisualAidPlugin.COMBAT_TIMEOUT_MS), true);
        eq("one ms past it is clear",
                GEVisualAidPlugin.combatFromAge(GEVisualAidPlugin.COMBAT_TIMEOUT_MS + 1), false);
        // NEVER SEEN is not IN COMBAT. -1 must not read as a tiny age and
        // lock the module out of ever attacking anything.
        eq("never having seen combat is not being in combat",
                GEVisualAidPlugin.combatFromAge(-1), false);
        // The timeout must clear a real between-attack gap. 4 ticks is a
        // normal weapon speed, so anything under that would unlock mid-fight.
        eq("the timeout clears an ordinary attack gap",
                GEVisualAidPlugin.COMBAT_TIMEOUT_MS >= 2400, true);

        // Both kinds of evidence. The aggressive case starts with NO click
        // of ours, which is the one Josh actually described.
        eq("it counts being attacked, not only attacking",
                slCode.contains("n.getInteracting() != me"), true);
        eq("...and says which kind of evidence it saw",
                slCode.contains("player_combat_source="), true);
        eq("the raw age is published so a consumer can pick its own margin",
                slCode.contains("player_combat_idle_ms="), true);
        eq("and the threshold itself goes out, like MLM_SETTLE_TICKS",
                slCode.contains("player_combat_timeout_ms="), true);
        // Logged out must not publish a confident "not in combat".
        eq("logged out says offline rather than clear",
                slCode.contains("player_combat_state=offline"), true);
        // 2.121 - THE 28 INVENTORY SLOTS, ALWAYS PUBLISHED.
        // Josh: "use widget coordinates for everything going forwards if
        // we have them with pixel backups." A measured literal is right
        // on one machine and silently wrong elsewhere; a widget id is
        // durable across RESOLUTIONS, the one axis a literal cannot cover.
        String inv = GEVisualAidPlugin.wgBuiltinInventory();
        eq("all 28 slots are published", countOccurrences(inv, "inv_"), 28);
        eq("slot 1 is the first index, not the second",
                inv.startsWith("inv_1=149.0.0|15.3.0|467.0.0"), true);
        eq("and slot 28 is index 27",
                inv.contains("inv_28=149.0.27|15.3.27|467.0.27"), true);
        // EACH SLOT IS A CHAIN. The same slot lives in a different
        // interface depending on what is open, and only the bank one has a
        // rectangle for an EMPTY slot - so a single id would go blind the
        // moment the bank opened.
        eq("every slot carries all three interfaces",
                countOccurrences(inv, "|"), 56);
        eq("no slot is numbered 0 - the game counts from 1",
                inv.contains("inv_0="), false);

        // 2.122 - A TRAILING * IS A PREFIX MATCH, BECAUSE ONE DROP HAS
        // TWELVE NAMES. Josh, 2026-10-21: "just make all clues act the same
        // way." A clue is "Clue scroll (<tier>)", or "Scroll box (<tier>)" if
        // you already hold one of that tier: six tiers, two spellings.
        //
        // The real compiled rule is called here, not a copy of it. Filter
        // names are lowercased at parse time (parseEntityFilter does
        // tok.toLowerCase()), so every filter argument below is lowercase -
        // writing it any other way would test a string the plugin can never
        // actually hold.
        Method fnm = c.getDeclaredMethod("filterNameMatches", String.class, String.class);
        fnm.setAccessible(true);

        // THE WHOLE POINT: one entry, every tier, both spellings, for ever.
        for (String tier : new String[]{ "beginner", "easy", "medium", "hard",
                                         "elite", "master" })
        {
            eq("clue scroll* finds the " + tier + " clue",
                    fnm.invoke(null, "clue scroll*", "clue scroll (" + tier + ")"), true);
            eq("scroll box* finds the " + tier + " box",
                    fnm.invoke(null, "scroll box*", "scroll box (" + tier + ")"), true);
        }
        // A tier nobody has heard of yet. This is the assertion that makes
        // the prefix worth having over twelve exact names: a lookup table
        // would match nothing here and say nothing about it.
        eq("and a tier that does not exist yet",
                fnm.invoke(null, "clue scroll*", "clue scroll (impossible)"), true);

        // IT IS A PREFIX, NEVER A SUBSTRING. A substring match would widen
        // every filter already written - "bar" would start finding the Blast
        // Furnace bar dispenser, which is a live filter in set 2 today.
        eq("a prefix does not match in the middle",
                fnm.invoke(null, "bar*", "bar dispenser"), true);
        eq("and does not match a name that merely CONTAINS it",
                fnm.invoke(null, "dispenser*", "bar dispenser"), false);
        eq("scroll box* does not pick up a clue scroll",
                fnm.invoke(null, "scroll box*", "clue scroll (hard)"), false);

        // The two older behaviours must survive untouched: this branch sits
        // on the path every scenery, NPC and item filter already takes.
        eq("a bare * still means any named entity",
                fnm.invoke(null, "*", "anything at all"), true);
        eq("an exact name still matches exactly",
                fnm.invoke(null, "gold ore", "gold ore"), true);
        eq("and an exact name is still not a prefix",
                fnm.invoke(null, "gold ore", "gold ore stack"), false);
        eq("nor a suffix",
                fnm.invoke(null, "ore", "gold ore"), false);

        // An unnamed entity matches NOTHING, wildcard included - that was the
        // "wildcards skip unnamed objects" guard, and losing it would make a
        // bare * match every unnamed object in the scene.
        eq("an unnamed entity does not match *",
                fnm.invoke(null, "*", ""), false);
        eq("nor a prefix",
                fnm.invoke(null, "clue scroll*", ""), false);
        eq("a null name is safe",
                fnm.invoke(null, "clue scroll*", (Object) null), false);

        // AND THE PLUGIN HAS TO ACTUALLY CALL IT. Every assertion above tests
        // the helper; none of them tests that matchFilter still asks it.
        // Proved by mutation: the call site was reverted to its old inline
        // compare and all twenty-odd assertions stayed green, because a
        // correct rule nobody consults is the same as no rule. This suite has
        // made exactly this mistake before - checking a hand-over and never
        // the read.
        String fSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/ge/gevisualaid/GEVisualAidPlugin.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        eq("matchFilter delegates the name rule",
                fSrc.contains("if (!filterNameMatches(f.name, lower)) continue;"), true);
        eq("and keeps no inline name compare of its own",
                fSrc.contains("!lower.equals(f.name)"), false);

        // 2.123 - TIMERS AND BUFFS.
        //
        // Josh asked for the goading, divine, imbued heart, cannon and
        // pickpocket-stun timers as groundwork for a "keep the goading going"
        // tick box. Watching a stat fall back to base is a LAGGING signal -
        // it says the boost has gone, never that it is about to - so it
        // cannot plan a top-up.
        String tSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/ge/gevisualaid/GEVisualAidPlugin.java")),
                java.nio.charset.StandardCharsets.UTF_8);

        java.lang.reflect.Field tvF = c.getDeclaredField("TIMER_VARBITS");
        tvF.setAccessible(true);
        Object[][] tv = (Object[][]) tvF.get(null);
        eq("the timer list is populated", tv.length >= 10, true);

        java.util.Set<String> tnames = new java.util.HashSet<>();
        java.util.Set<Integer> tbits = new java.util.HashSet<>();
        for (Object[] row : tv)
        {
            eq("timer name is unique: " + row[0], tnames.add((String) row[0]), true);
            // A DUPLICATE VARBIT MEANS TWO NAMES READING ONE NUMBER, which
            // would report a potion nobody drank. Same family as the
            // duplicate filter label that is silently dropped.
            eq("timer varbit is unique: " + row[0], tbits.add((Integer) row[1]), true);
        }
        for (String want : new String[]{ "goading", "imbued_heart", "divine_combat" })
            eq("the timer Josh named is present: " + want, tnames.contains(want), true);

        // EVERY ID IS A SYMBOL, NEVER A LITERAL. Varbit 4398 compiled fine
        // against an API that had already deleted the constant naming it
        // (2.100), and the breakage arrived as a flipper clicking the
        // inventory in a loop. A literal in this table would do the same.
        int tblock = tSrc.indexOf("private static final Object[][] TIMER_VARBITS");
        int tend   = tSrc.indexOf("};", tblock);
        eq("the timer table was found", tblock > 0 && tend > tblock, true);
        String tbody = tSrc.substring(tblock, tend);
        // One symbol per row, whatever the row count - asserted as the
        // IDENTITY rather than a number, so adding a timer cannot make this
        // fail for the wrong reason while a literal slips in unnoticed.
        eq("every timer id is a VarbitID symbol",
                countOccurrences(tbody, "VarbitID."), tv.length);
        // A bare number would look exactly like a working table.
        eq("and no row carries a raw number",
                tbody.matches("(?s).*,\\s*\\d+\\s*\\}.*"), false);

        // THE NEAR-MISS SYMBOL IS THE DANGEROUS ONE. Beside every
        // DIVINE*_POTION_TIME there is a DIVINE*_POTION_BASE, and beside the
        // goading timer a BUFF_..._DURATION_DISABLED. Both compile, both read,
        // and neither is a countdown - so picking one would publish a
        // confident number that never ticks down.
        eq("no _BASE varbit is used as a timer", tbody.contains("_POTION_BASE"), false);
        eq("no _DISABLED varbit is used as a timer", tbody.contains("DISABLED"), false);

        // THE UNIT IS MEASURED, NOT ASSUMED. A varbit called _TIMER does not
        // say what one of its units is worth, so the plugin watches one step
        // go by and withholds the eta until it has.
        eq("a single clean step is what measures the unit",
                tSrc.contains("raw == o.last - 1"), true);
        eq("and the eta is withheld until it is measured",
                tSrc.contains("o.ticksPerStep > 0 && raw > 0 ? (raw * o.ticksPerStep) : -1"), true);
        // Scoped to the TIMER loop. "_witnessed=" alone also matches
        // pickpocket_stun_witnessed, so the first version of this passed with
        // the timer field deleted - the fourth time in this session that a
        // pattern matched something other than the thing it was about.
        eq("never watch a number you have not seen move",
                tSrc.contains(".append(k).append(\"witnessed=\")"), true);

        // THE CANNON BALL COUNT IS NOT RESOLVED AND MUST NOT PRETEND TO BE.
        // net.runelite.api.VarPlayer.CANNON_AMMO = 3 and
        // gameval VarPlayerID.ROCKTHROWER = 3 disagree in the SAME jar, and
        // no reading of constants settles which label is wrong. Publishing
        // one as "cannon_ammo" would be the confident-wrong-number this repo
        // keeps paying for, so all five candidates go out labelled instead -
        // the /geoffer technique (2.102), one live reading settles it.
        // 2.124 REVERSED THIS DELIBERATELY. It used to assert the ammo was
        // published as UNRESOLVED with five candidates beside it, which was
        // correct while two names in the same jar disagreed about varp 3. Josh
        // set a cannon up with 45 balls on 2026-10-22, varp 3 read exactly 45,
        // and the question is closed - so the assertion is inverted here
        // rather than deleted, because a test quietly dropped is a decision
        // nobody can find afterwards. The replacement is a few lines below.
        eq("the question is no longer published as open",
                tSrc.contains("cannon_ammo_source=unresolved_see_candidates"), false);
        // These ones ARE named unambiguously, so they are published plainly.
        eq("the breakdown flag is published", tSrc.contains("MCANNON_DECAYED"), true);

        // THE PICKPOCKET STUN HAS NO VARBIT and the feed says what it really
        // is: a graphic, with a length measured on the falling edge rather
        // than a countdown it does not have.
        eq("the stun names its real source",
                tSrc.contains("pickpocket_stun_source=spotanim:STUNNED_THIEVING"), true);
        eq("and reads the graphic, not an invented varbit",
                tSrc.contains("hasSpotAnim(SpotanimID.STUNNED_THIEVING)"), true);
        eq("its length is recorded when it ENDS",
                tSrc.contains("if (stunNow) stunLastTicks = stunTicks;"), true);

        // AN AGE BAKED INTO A CACHED STRING IS NOT AN AGE. timersBlock is
        // built once a tick and handed to every reader, so only the absolute
        // stamp is stored and /timers subtracts at request time.
        eq("/timers is registered", tSrc.contains("createContext(\"/timers\""), true);
        eq("only an absolute stamp is kept", tSrc.contains("timersStampMs ="), true);
        eq("and the age is computed in the handler",
                tSrc.contains("(System.currentTimeMillis() - stamp)"), true);
        // Logged out is not a fault and must not read as one.
        eq("offline is named as its own state",
                tSrc.contains("timers_state=offline"), true);

        // 2.124 - THE REST OF THE POTIONS, AND THREE THINGS MEASURED SINCE.

        // THE FIRST READ IS NOT A MOVEMENT. 2.123 started o.last at -1, so
        // the first reading always differed and flipped witnessed to true -
        // the field's only job being to separate "this has moved" from "this
        // may not even be the right varbit". Found by reading the live feed:
        // an INACTIVE goading potion claimed witnessed=true after a restart.
        // COUNTED, NOT CONTAINED. The guard exists in TWO places - the
        // varbit loop and trackStep - so contains() stayed true with one of
        // them broken, and the prove-red for the very bug this fixes came
        // back green. Sixth time this session a pattern matched past the
        // thing it was about.
        eq("the first read is excluded from witnessed, in BOTH places",
                countOccurrences(tSrc, "if (!firstRead) o.witnessed = true;"), 2);
        eq("and nothing sets it unguarded",
                countOccurrences(tSrc, "o.witnessed      = true;"), 0);
        // Same guard on the measurement, or a potion being drunk would be
        // timed as if it were a step.
        eq("the unit is only measured off a known previous value, in both",
                countOccurrences(tSrc, "if (!firstRead && o.lastChangeTick >= 0"), 2);

        // The list grew by the six the game really does count down.
        eq("sixteen timers now", tv.length, 16);
        for (String want : new String[]{ "antifire", "super_antifire", "stamina",
                                         "magic_imbue", "overload_raids", "overload_nmz" })
            eq("added: " + want, tnames.contains(want), true);

        // NO TIMER IS CLAIMED FOR AN ORDINARY COMBAT POTION, because none
        // exists - searched all of VarbitID and every near match is a DIVINE
        // one, a league task, or a POTIONSTORE dose count. A divine potion
        // HOLDS the boost so the server stores a countdown; an ordinary one
        // drains a level a minute and stores nothing. Publishing a
        // super-attack timer would be inventing one.
        for (String never : new String[]{ "super_attack", "super_strength",
                                          "super_combat", "ranging_potion" })
            eq("no invented timer for: " + never, tnames.contains(never), false);
        // The honest substitute: the boost itself, which exists for every
        // potion either way and leads rather than lags.
        eq("the boost delta is published instead",
                tSrc.contains("boost_source=boosted_minus_real"), true);
        eq("per skill, real and boosted both",
                tSrc.contains("_real=") && tSrc.contains("_now="), true);
        // A DRAINED stat is negative and is not a boost. Counting it would
        // make "still boosted" true while standing in dragonfire.
        eq("only a positive delta counts as boosted",
                tSrc.contains("if (delta > 0) boosted++;"), true);

        // POISON IS ONE SIGNED VARP: positive poisoned, negative immune (that
        // IS the anti-venom countdown), zero neither. The sign is the state,
        // so publishing a bare number would let a reader take "-18" for
        // damage and have it exactly backwards.
        eq("poison reads the legacy signed varp",
                tSrc.contains("client.getVarpValue(net.runelite.api.VarPlayer.POISON)"), true);
        eq("and the sign is turned into a state",
                tSrc.contains("raw > 0 ? \"poisoned\" : (raw < 0 ? \"immune\" : \"clear\")"), true);
        // NOTHING IS DERIVED FROM IT. Neither conversion has been measured.
        eq("no damage figure is invented", tSrc.contains("poison_damage"), false);
        eq("no seconds are invented", tSrc.contains("poison_seconds"), false);
        eq("the immunity is timed by the same machinery",
                tSrc.contains("trackStep(\"poison_immunity\""), true);

        // THE CANNON AMMO IS SETTLED. Josh set one up with 45 balls and varp 3
        // read exactly 45, so legacy VarPlayer.CANNON_AMMO is right and
        // gameval ROCKTHROWER is the stale label. The candidate scan has done
        // its job and must not linger - a resolved question published as open
        // is just noise that outlives its reason.
        eq("the ammo is a named field now",
                tSrc.contains("appendVarpPlain(sb, \"cannon_ammo\",  net.runelite.api.VarPlayer.CANNON_AMMO)"), true);
        eq("the candidate scan is gone",
                tSrc.contains("unresolved_see_candidates"), false);
        eq("and it says where the answer came from",
                tSrc.contains("varp_measured_45_balls_2026_10_22"), true);
        // The two that fell out of the same reading.
        eq("the setup stage is published", tSrc.contains("VarPlayer.CANNON_STATE"), true);
        eq("and the location", tSrc.contains("VarPlayer.CANNON_COORD"), true);

        // THE 25 MINUTES IS NOT BAKED IN. Two readings off RuneLite were
        // CONSISTENT WITH 25 minutes from placement, and consistent-with is
        // not measured - nothing in the game counts it down (setup_time rose
        // during assembly then froze at 362616, decayed never left 0), so the
        // age goes out and the lifespan waits for a cannon watched to the end.
        // 2.125 REVERSED THIS. It asserted the lifespan was published as
        // UNMEASURED, which was right while the only evidence was two
        // readings consistent-with 25 minutes. Josh pointed at RuneLite's own
        // plugin, javap gave GameTimer.CANNON = 25 MINUTES outright, and the
        // question closed. Inverted rather than deleted, and the replacement
        // asserts the SOURCE is named - see below.
        eq("the lifespan is no longer withheld",
                tSrc.contains("cannon_lifespan_source=unmeasured"), false);
        // SCOPED TO THE CANNON BLOCK. The first version searched the whole
        // file for "1500"/"2500" and fired on an unrelated buffer size - an
        // assertion about the file rather than about the cannon.
        int cb0 = tSrc.indexOf("private void appendCannonBlock");
        int cb1 = tSrc.indexOf("private void appendVarpPlain", cb0);
        eq("the cannon block was found", cb0 > 0 && cb1 > cb0, true);
        String cbody = tSrc.substring(cb0, cb1);
        // AND SO WAS THIS. It forbade a 25-minute constant in the cannon
        // block, which was the right guard while such a number could only
        // have been a guess. It is now LOOKED UP from the client jar and
        // carries its provenance in the feed, so what matters is no longer
        // its absence but that it never appears without a source beside it.
        eq("a lifespan constant never appears without its source",
                !cbody.contains("2500") || cbody.contains("CANNON_LIFESPAN_TICKS"), true);
        eq("and no OTHER bare duration crept in", cbody.contains("1500"), false);
        eq("the age is timed from the placement tick",
                tSrc.contains("cannon_age_ticks="), true);
        // A cannon already down when the plugin started is timed from first
        // sight, which is a floor and not an age - the mlm_dep_N_timed_from
        // distinction.
        eq("and says which kind of number it is",
                tSrc.contains("first_sight_not_placement"), true);
        eq("a cannon taken down forgets its placement",
                tSrc.contains("cannonPlacedTick = -1;"), true);

        // 2.125 - THE CANNON LIFESPAN, LOOKED UP INSTEAD OF WAITED FOR.
        // Josh: "cant we just look up that?" Read from the compiled client
        // with javap: GameTimer.CANNON = 25 MINUTES, CANNON_REPAIR = 10.
        eq("the lifespan is 2500 ticks",
                tSrc.contains("CANNON_LIFESPAN_TICKS = 2500"), true);
        eq("and the repair timer is 1000",
                tSrc.contains("CANNON_REPAIR_TICKS   = 1000"), true);
        // A CONSTANT LIFTED FROM SOMEONE ELSE'S CODE IS ONLY AS CURRENT AS
        // THEIR CODE, and 2.99 established the hub serves a different build
        // per client version. A consumer told where the number came from can
        // distrust it; one handed a bare 2500 cannot.
        eq("the feed names where the lifespan came from",
                tSrc.contains("cannon_lifespan_source=runelite_gametimer_cannon_25min"), true);
        eq("it is no longer published as unmeasured",
                tSrc.contains("cannon_lifespan_source=unmeasured"), false);
        // The broken flag is CONFIRMED against RuneLite, not a candidate.
        eq("and names the source of the broken flag too",
                tSrc.contains("cannon_broken_source=runelite_cannonplugin_varbit_13672"), true);
        // AN ETA BUILT ON A FLOOR READS LONGER THAN THE TRUTH, which is the
        // dangerous direction, so a cannon that was already down when the
        // plugin started gets -1 rather than an optimistic number.
        eq("no eta from a first-sight age",
                tSrc.contains("!cannonSeenFromStart && stage > 0"), true);

        // 2.126 - the three that were genuinely missing, after checking first
        // killed the rest of the list: vit_run_percent and vit_spec_percent
        // already existed, and the rune pouch was already merged into the
        // inventory tally. I searched for the wrong prefix and nearly
        // reported three gaps that were not there.
        eq("the selected attack style is published",
                tSrc.contains("attack_style_index="), true);
        // THE INDEX, NOT A NAME. What 0..3 means depends on the weapon, so a
        // name would be a lookup table of every weapon class.
        eq("as an index rather than a guessed name",
                tSrc.contains("attack_style_name"), false);
        eq("and it names the varp it came from",
                tSrc.contains("attack_style_source=varp:"), true);

        // -1 means NOT YET KNOWN, because getHealthRatio() is -1 until the
        // target takes a hit. A consumer reading 0 for unknown would switch
        // target off a monster at full health.
        eq("the target health is published", tSrc.contains("target_hp_percent="), true);
        eq("with the raw ratio and scale beside it",
                tSrc.contains("target_hp_ratio=") && tSrc.contains("target_hp_scale="), true);
        eq("and a percent is only computed when both are real",
                tSrc.contains("if (ratio >= 0 && scale > 0)"), true);

        // A COPIED TABLE THAT ROTS, AND THE FEED SAYS SO. The ids came from
        // RandomEventPlugin bytecode, not a wiki page, and the NAME goes out
        // beside them so a human can see an event the list has never heard of.
        eq("random events are detected", tSrc.contains("random_event_active="), true);
        eq("the id list names its provenance",
                tSrc.contains("random_event_list_source=runelite_randomeventplugin_1.13.3"), true);
        eq("and the NPC name goes out beside the id",
                tSrc.contains("random_event_npc="), true);
        // The INTERACTION half matters: these ids also appear as ordinary
        // scenery NPCs, so an id-only test would cry wolf every pass.
        // COUNTED. The same test exists in buildCombatBlock (the attacked_by
        // half), so contains() stayed true with the extras one deleted - the
        // SEVENTH time in this session that a pattern matched past the thing
        // it was about. If a line could plausibly appear twice, count it.
        eq("only an NPC interacting with US counts, in both places",
                countOccurrences(tSrc, "n.getInteracting() != me"), 2);
        eq("offline is its own state", tSrc.contains("extras_state=offline"), true);

        // ===================================================================
        // 2.128 - THE PROJECTILE STYLE TABLE.
        //
        // This is the one lookup table CLAUDE.md allows, and it is allowed
        // only because an id it does not know returns UNKNOWN instead of a
        // guess. Every assertion here is about keeping that true - a table
        // that answered RANGED for an id it had never seen would be the
        // mislabel-rather-than-fail-loudly trap, wearing a prayer book.
        //
        // The REAL compiled rule is called, not a copy: same reason as
        // filterNameMatches and portfolioGeShare above.
        // ===================================================================
        Method pStyle = ProjectileStyles.class.getDeclaredMethod("styleOf", int.class);
        Method pBoss  = ProjectileStyles.class.getDeclaredMethod("bossOf",  int.class);
        Method pOver  = ProjectileStyles.class.getDeclaredMethod("overheadFor", String.class);
        Method pSize  = ProjectileStyles.class.getDeclaredMethod("size");
        Method pConf  = ProjectileStyles.class.getDeclaredMethod("conflicts");
        pStyle.setAccessible(true); pBoss.setAccessible(true);
        pOver.setAccessible(true);  pSize.setAccessible(true);
        pConf.setAccessible(true);

        // NO ID MAY CARRY TWO STYLES. If one did, which answer you got would
        // depend on map iteration order - a prayer that is right on Monday.
        eq("no projectile id is listed under two styles", pConf.invoke(null), 0);

        // The count is asserted, not just "> 0": the table is transcribed by
        // hand, and a row lost in an edit leaves a table that still works for
        // everything except the boss that went missing.
        // 77 since 2.133: Frost Nagua's magic projectile 2948, measured from
        // the attack catalogue's first run rather than lifted from another
        // plugin. The pin is here so an id cannot be LOST silently; it is
        // meant to be bumped deliberately when one is added.
        eq("the table has all 77 ids", pSize.invoke(null), 77);

        // Measured samples, one per style, from three different bosses - so a
        // single wrong column cannot pass. Zulrah's two are the pair most
        // likely to be read the wrong way round (1046 magic, 1044 ranged).
        eq("Zulrah 1046 is magic",   pStyle.invoke(null, 1046), "MAGIC");
        eq("Zulrah 1044 is ranged",  pStyle.invoke(null, 1044), "RANGED");
        eq("Olm 1345 is melee",      pStyle.invoke(null, 1345), "MELEE");
        eq("Leviathan 2487 is ranged", pStyle.invoke(null, 2487), "RANGED");
        eq("the boss name comes out with it", pBoss.invoke(null, 1046), "Zulrah");

        // THE WHOLE SAFETY PROPERTY. An id nobody listed must be UNKNOWN and
        // must map to NO prayer, so the consumer has nothing to click.
        eq("an unlisted id is UNKNOWN", pStyle.invoke(null, 999999), "UNKNOWN");
        eq("and has no boss",           pBoss.invoke(null, 999999), "");
        eq("UNKNOWN maps to no overhead", pOver.invoke(null, "UNKNOWN"), "");
        // -1 is what the plugin publishes before it has ever seen one. It
        // must take the same path as any other unlisted id, not throw.
        eq("the never-seen id -1 is UNKNOWN", pStyle.invoke(null, -1), "UNKNOWN");

        // Spelled as the Prayer enum spells it, because the consumer compares
        // this against pray_overhead, which comes straight off Prayer.name().
        eq("magic asks for PROTECT_FROM_MAGIC",
                pOver.invoke(null, "MAGIC"), "PROTECT_FROM_MAGIC");
        eq("ranged asks for PROTECT_FROM_MISSILES",
                pOver.invoke(null, "RANGED"), "PROTECT_FROM_MISSILES");
        eq("melee asks for PROTECT_FROM_MELEE",
                pOver.invoke(null, "MELEE"), "PROTECT_FROM_MELEE");

        // AND THE PLUGIN HAS TO ACTUALLY ASK IT. A correct table nobody
        // consults is the same as no table - the lesson that cost a round on
        // filterNameMatches.
        eq("appendAttackStyle calls the real table",
                tSrc.contains("ProjectileStyles.styleOf(attLastProjectile)"), true);
        eq("the style is published", tSrc.contains("att_projectile_style="), true);
        eq("the prayer to turn on is published",
                tSrc.contains("att_projectile_overhead="), true);
        eq("the flight time left is published",
                tSrc.contains("att_projectile_cycles_left="), true);

        // THE BLANK HAS TO SAY WHY - 2.92's rule. A style of NONE because no
        // projectile has ever arrived and a style of NONE because the last
        // one was two minutes ago need different repairs, and an id the table
        // does not know is a third thing again.
        eq("no projectile is named as such",
                tSrc.contains("projSource = \"no_projectile\""), true);
        eq("a lapsed projectile is named stale",
                tSrc.contains("projSource = \"stale\""), true);
        eq("an unlisted id is named not_in_table",
                tSrc.contains("\"not_in_table\""), true);
        eq("a match names the boss it matched",
                tSrc.contains("(\"table:\" + projBoss)"), true);

        // att_style IS NOT ALLOWED TO CHANGE MEANING. It has said
        // RANGED_OR_MAGIC since 2.126 and the skiller reads it; narrowing it
        // in place would re-point an existing consumer without touching it,
        // which is exactly the flight_lost -> queued mistake. The new answer
        // is a NEW field or it is not safe.
        eq("att_style still publishes RANGED_OR_MAGIC",
                tSrc.contains("style = \"RANGED_OR_MAGIC\""), true);

        // ---- 2.129: FACING ME IS NOT ATTACKING ME --------------------------
        //
        // Josh, live: "it also just triggered when i was standing next to an
        // non aggressive npc. talking to a npc. it only should fire when im
        // actively being attacked." getInteracting() == me is true of a
        // conversation, so until 2.129 every shopkeeper published MELEE.
        //
        // A hitsplat would also prove an attack and was REJECTED for Josh's
        // reason - "a hitsplat means its not protected me from it". The action
        // list is the only pre-damage, table-free answer.
        eq("the melee reading requires the NPC to be attackable",
                tSrc.contains("melee = byDist >= 0 && byDist <= 2 && byAttackable"), true);
        eq("attackability is read from the NPC's own action list, not an id list",
                tSrc.contains("s.equalsIgnoreCase(\"Attack\")"), true);
        eq("and from the TRANSFORMED composition, so a monster that changes form still reads right",
                tSrc.contains("n.getTransformedComposition()"), true);
        // THE BASIS HAD TO CHANGE WITH THE RULE. A consumer reads att_basis to
        // know WHY, so leaving the old word in place would tell every reader
        // the old rule still applied - the flight_lost -> queued lesson, which
        // the block directly above this one already names.
        eq("att_basis names the new rule",
                tSrc.contains("adjacent_attackable_no_projectile"), true);
        eq("and the old basis word cannot creep back",
                tSrc.contains("\"adjacent_no_projectile\""), false);
        // THE ASSIGNMENT, NOT THE BARE STRING. The plain string also occurs in
        // the comment above that branch, so tSrc.contains("facing_but_not_
        // attackable") stayed green with the branch itself deleted - proved by
        // the mutation that replaced it with attacker_out_of_melee_range. Weak
        // key, and the THIRD of these in one session.
        eq("a facing-but-harmless NPC is its own named case, and is NONE not MELEE",
                tSrc.contains("conf = \"facing_but_not_attackable\""), true);
        // An attackable NPC must win over a nearer harmless one, or the melee
        // reading depends on whether the shopkeeper or the hellhound is closer.
        eq("an attackable attacker outranks a nearer harmless NPC",
                tSrc.contains("if (byAttackable && !atk) continue;"), true);
        // The evidence goes out beside the verdict, so it is checkable against
        // the right-click menu without a deploy.
        for (String af : new String[]{"att_attacker_attackable", "att_attacker_actions",
                                      "att_attacker_combat_level"})
            eq("the feed carries " + af, tSrc.contains(af), true);

        // ---- 2.129: /attack, the human view -------------------------------
        eq("/attack is registered",
                tSrc.contains("createContext(\"/attack\""), true);
        eq("and it serves a block built on the CLIENT thread, never re-derived in the handler",
                tSrc.contains("r.append(attNearbyBlock)")
             && tSrc.contains("attNearbyBlock = near.toString()"), true);
        // Same trap as scene_age_ms and game_tick_age_ms: an age baked into a
        // cached string reads as fresh for ever.
        eq("the nearby age is computed in the handler, not baked into the cache",
                tSrc.contains("att_nearby_age_ticks"), true);
        eq("the animation is published and never acted on - which one means 'attack' needs a table",
                tSrc.contains("safeNpcAnim"), true);

        String cfgSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/ge/gevisualaid/GEVisualAidConfig.java")),
                java.nio.charset.StandardCharsets.UTF_8);

        // ---- 2.129: THE NPC STYLE TABLE, AND WHY IT IS ALLOWED ------------
        //
        // CLAUDE.md forbids lookup tables because they rot silently and
        // mislabel rather than fail loudly. These assertions ARE the three
        // things that answer that, so they are the ones that matter most:
        // a hybrid must never be answered, a missing id must never be
        // answered, and the thing must be rebuildable.

        // 1. AMBIGUOUS IS NOT A GUESS. This is the whole safety property.
        //    Josh asked specifically about a Tormented Demon, which the wiki
        //    lists as melee+ranged+magic. Called against the REAL compiled
        //    class, not a second copy of the rule.
        eq("a melee-only NPC is answered",
                "MELEE".equals(NpcStyleData.soleStyle(soleIdWithMask(1))), true);
        eq("a hybrid is NOT answered - null, so nothing acts on it",
                NpcStyleData.soleStyle(soleIdWithMask(7)) == null, true);
        eq("nor a two-style hybrid",
                NpcStyleData.soleStyle(soleIdWithMask(5)) == null, true);
        // 2. A MISSING ID IS SILENCE, NEVER A STYLE.
        eq("an id that is not in the table answers nothing",
                NpcStyleData.soleStyle(999_999_999) == null, true);
        eq("and its mask is 0, not a style",
                NpcStyleData.styleMask(999_999_999), 0);
        eq("and its max hit is -1, which is not 0 - 0 is a real reading",
                NpcStyleData.maxHit(999_999_999), -1);
        // 3. IT LOADED AT ALL, and says so rather than failing silently.
        eq("the bundled table parses", "ok".equals(NpcStyleData.state()), true);
        eq("and has the full wiki set in it", NpcStyleData.rows() >= 4000, true);
        // 4. REBUILDABLE, which is what makes it a cache and not folklore.
        eq("the generator is vendored beside it",
                java.nio.file.Files.exists(
                    java.nio.file.Paths.get("tools/build_npc_data.py")), true);
        eq("and so is its licence",
                java.nio.file.Files.exists(
                    java.nio.file.Paths.get("tools/npc_data_LICENSE_aggro-tag.txt")), true);

        // OFF BY DEFAULT. Josh: "ensure its toggleable on or off. Because my
        // flippers etc wont need access to it". A default-on setting would
        // parse 110KB on every flipping VM at the first attack reading.
        eq("the npc_data setting exists and defaults to OFF",
                cfgSrc.contains("default boolean npcStyleDataEnabled() { return false; }"), true);
        eq("and nothing reads the table unless it is on",
                tSrc.contains("if (npcData && byAttackable && byId >= 0)"), true);
        // MEASURED: 146ms to parse, 137ns a lookup. The lookup is free; the
        // parse is a quarter of a game tick and would land on the first attack
        // of the session if it happened in onGameTick. So it is warmed on a
        // daemon thread at startUp - safe off the client thread because it
        // touches no scene, widget or varbit, only a bundled resource.
        eq("the parse is warmed off the client thread, not done in onGameTick",
                tSrc.contains("new Thread(NpcStyleData::ensureLoaded, \"gevisualaid-npcdata\")"), true);
        eq("and even the warm-up is behind the setting",
                tSrc.contains("if (config.npcStyleDataEnabled())"), true);

        // ---- 2.130: THE HOLD, so adjacency stops filling the gap ----------
        //
        // Josh, on tormented gorillas: "they would attack with mage, id pray
        // mage, then back to melee, then mage etc... it never did a melee
        // attack whilst it was maging or ranging me." MEASURED ARITHMETIC:
        // the window was 1500ms and a gorilla attacks every 4 ticks =
        // 2400ms, so ~900ms of every cycle had no projectile reading left and
        // adjacency can only ever say MELEE.
        eq("the hold window clears one full attack cycle",
                ATT_PROJ_HOLD_MS_from(tSrc) >= 2400, true);
        eq("and it is longer than the in-flight window",
                ATT_PROJ_HOLD_MS_from(tSrc) > ATT_PROJ_MS_from(tSrc), true);
        eq("the held style is its own named basis",
                tSrc.contains("conf = \"projectile_held\""), true);
        // TIED TO THE ATTACKER. A gorilla dying and a melee monster taking
        // over must not inherit the gorilla's magic hold - praying magic at
        // something punching you is worse than the flap this fixes.
        eq("the hold only applies to the SAME attacker",
                tSrc.contains("byId == attProjHoldId"), true);
        // ORDER: below a live projectile (it tracks a switch), above npc_data
        // (an observation of this fight beats a catalogue entry), above
        // adjacency (the whole reason it exists).
        int iPj = tSrc.indexOf("conf = \"projectile\"");
        int iHd = tSrc.indexOf("conf = \"projectile_held\"");
        int iNd = tSrc.indexOf("conf = \"npc_data_sole_style\"");
        eq("ranked below a live projectile and above the npc table",
                iPj > 0 && iHd > iPj && iNd > iHd, true);
        // The number stays checkable against a real attack interval, the same
        // discipline as mlm_sack_settle_ticks being published.
        // THE APPEND, NOT THE NAME. The bare string also appears in the
        // comment above the constant ("Published as att_projectile_hold_ms so
        // the number stays checkable"), so contains() stayed green with the
        // append deleted. FOURTH weak key of this kind in two sessions, and
        // always the same shape: a field name that also occurs in prose.
        eq("the window is published, not buried",
                tSrc.contains("sb.append(\"att_projectile_hold_ms=\")"), true);
        eq("with its age and which attacker it belongs to",
                tSrc.contains("sb.append(\"att_projectile_hold_age_ms=\")")
             && tSrc.contains("sb.append(\"att_projectile_hold_attacker=\")"), true);

        // ---- 2.131: THE ATTACK LOG RECORDS, IT NEVER DECIDES --------------
        //
        // Josh: "is there anyway of just identifying the NPC and seeing what
        // attack style he is using... Because some rarely bosses have a ranged
        // melee attack." Checked with javap against runelite-api 1.13.4:
        // Actor exposes NO attack style, so the game never labels one. What is
        // observable is the attack - its ANIMATION - and that is logged raw.
        eq("the animation subscriber exists",
                tSrc.contains("public void onAnimationChanged(AnimationChanged event)"), true);
        eq("and only for an NPC that is interacting with US",
                tSrc.contains("if (a.getInteracting() != client.getLocalPlayer()) return;"), true);
        eq("and only when the setting is on",
                tSrc.contains("if (!config.attackStyleEnabled()) return;"), true);
        // anim == -1 is the attack ENDING. Logging it would double every row.
        eq("an animation ending is not an attack",
                tSrc.contains("if (anim == -1) return;"), true);

        // THE WHOLE POINT: nothing maps an animation to a style. The moment
        // something does, it is a per-monster table nobody has measured, and
        // this repo's rule is that such a thing mislabels rather than fails.
        //
        // THE FIRST VERSION OF THIS WAS A NEGATIVE REGEX AND IT DID NOT WORK.
        // It looked for `anim == <number>` next to a style assignment, and the
        // prove-red mutation wrote `attSeenAnim[0] == 7855` - which the regex
        // never saw, because it only matched a variable literally called
        // `anim`. A negative assertion over free-form code is unenforceable:
        // there are unlimited spellings of the thing being forbidden.
        //
        // So it is a POSITIVE instead. Every branch of the style decision
        // names its basis, so pinning the exact SET of bases makes any new
        // branch - animation-derived or otherwise - fail here until it is
        // added deliberately. One number to update, and no way past it.
        java.util.Set<String> bases = new java.util.TreeSet<>();
        java.util.regex.Matcher bm = java.util.regex.Pattern
                .compile("conf = \"([a-z_]+)\"").matcher(tSrc);
        while (bm.find()) bases.add(bm.group(1));
        eq("the style decision has exactly the seven known bases and no others",
                bases.toString(),
                "[adjacent_attackable_no_projectile, attacker_out_of_melee_range,"
              + " facing_but_not_attackable, no_attacker, npc_data_sole_style,"
              + " projectile, projectile_held]");

        // The projectile is paired BACKWARDS onto the newest row, because the
        // animation starts first. A "first sight of this projectile id" guard
        // would be wrong - a boss reusing one projectile would never pair.
        eq("a projectile is paired onto the row that has none yet",
                tSrc.contains("if (attSeenProj[ix] != -1) return;"), true);
        // 2.134 - the staleness guard moved onto the row matched BY NPC, and
        // widened to four ticks because that row is now definitely the right
        // monster's and only needs to exclude a genuinely old animation.
        eq("a stale animation is not paired to",
                tSrc.contains("tick - attSeenTick[ix] > 4"), true);
        eq("the pairing is called from the projectile handler",
                tSrc.contains("attLinkProjectile(p, id, tick);"), true);
        // Newest first, so the row for the attack just taken is att_seen_0.
        eq("the log is published newest first",
                tSrc.contains("(attSeenPos - 1 - i + 2 * ATT_SEEN_MAX) % ATT_SEEN_MAX"), true);
        eq("and says when nothing has attacked yet",
                tSrc.contains("nothing_has_attacked_yet"), true);
        eq("the cap is published, so a truncated log cannot look complete",
                tSrc.contains("sq.append(\"att_seen_cap=\")"), true);

        // ---- 2.132: THE CUMULATIVE CATALOGUE ------------------------------
        //
        // Josh: "can I leave it running for all NPCS and we just collate them
        // over time? If im reading it right, i can just fight an NPC, map out
        // all of their attacks, then that is saved?" - the ring buffer was
        // neither cumulative nor saved, which is what this adds.
        String aSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                    "src/main/java/com/ge/gevisualaid/NpcAttackLog.java")),
                java.nio.charset.StandardCharsets.UTF_8);

        // THE SAMPLE FLOOR IS THE WHOLE HONESTY OF IT. "Never seen with a
        // projectile" is worth nothing after one sample. Called against the
        // real compiled class, not a second copy of the rule.
        NpcAttackLog.Row few = new NpcAttackLog.Row();
        few.seen = 1; few.withProj = 0;
        eq("one sample draws no conclusion",
                NpcAttackLog.verdict(few).startsWith("too_few_samples"), true);
        NpcAttackLog.Row many = new NpcAttackLog.Row();
        many.seen = NpcAttackLog.MIN_SAMPLES; many.withProj = 0;
        eq("a well-sampled row with no projectile says exactly that, and no style",
                NpcAttackLog.verdict(many), "never_projectiled");
        // A mixed row must NOT be averaged into one answer - an animation that
        // sometimes throws something is a real finding.
        NpcAttackLog.Row mixed = new NpcAttackLog.Row();
        mixed.seen = 20; mixed.withProj = 9; mixed.projIds.add(1302);
        eq("sometimes-a-projectile stays unresolved and says so",
                NpcAttackLog.verdict(mixed).startsWith("sometimes_projectile:9_of_20"), true);
        // 2.136 - ALWAYS a projectile but SEVERAL ids is not ambiguous, and
        // was being reported as though it were. Josh's Zulrah anim 5069:
        // ten of ten, ids [1044, 1046], and the table knows both - 1044
        // RANGED, 1046 MAGIC. One animation for two attacks, told apart by
        // the projectile, which is entirely actionable.
        NpcAttackLog.Row multi = new NpcAttackLog.Row();
        multi.seen = 20; multi.withProj = 20;
        multi.projIds.add(1044); multi.projIds.add(1046);
        eq("always-a-projectile with several ids resolves each one",
                NpcAttackLog.verdict(multi),
                "always_projectile_multi:1044:RANGED+1046:MAGIC");
        // And the two must not share a label, because they need opposite
        // handling: one is "pray by what is in flight", the other is
        // "this cannot be resolved from here".
        eq("the two are different verdicts",
                NpcAttackLog.verdict(multi).equals(NpcAttackLog.verdict(mixed)), false);
        // THE CHAIN WITH NO GUESS: always one projectile, and the projectile
        // table knows that id. 1304 is Demonic Gorilla magic in 2.128.
        NpcAttackLog.Row always = new NpcAttackLog.Row();
        always.seen = 30; always.withProj = 30; always.projIds.add(1304);
        eq("an animation always paired with one known projectile resolves to its style",
                NpcAttackLog.verdict(always), "always_projectile:1304:MAGIC");
        NpcAttackLog.Row unk = new NpcAttackLog.Row();
        unk.seen = 30; unk.withProj = 30; unk.projIds.add(999999);
        eq("and an unlisted projectile says not_in_table rather than guessing",
                NpcAttackLog.verdict(unk), "always_projectile:999999:not_in_table");

        // IT MUST ACCUMULATE. Loading before writing is the one thing that
        // separates a catalogue from a per-session log that overwrites itself.
        eq("the file is read back at startup",
                tSrc.contains("NpcAttackLog.load(f)"), true);
        eq("and the load is idempotent, so a slow read cannot run twice",
                aSrc.contains("if (loaded) return;")
             && aSrc.contains("loaded = true;"), true);
        // Written via a temp file and replaced: a truncated accumulation is
        // worse than never having saved it.
        eq("the write is atomic, not a truncating overwrite",
                aSrc.contains("StandardCopyOption.REPLACE_EXISTING"), true);
        // Flushed off the client thread, and FORCED synchronously on shutdown
        // or up to 30s of observations die with the JVM.
        eq("the periodic flush is off the client thread",
                tSrc.contains("\"gevisualaid-attacklog\""), true);
        eq("and shutDown forces it synchronously, before anything else",
                tSrc.matches("(?s).*protected void shutDown\\(\\)\\s*\\{"
                           + "[^}]{0,600}?attLogFlush\\(true\\);.*"), true);
        // BOTH switches. A feature that writes to disk because an unrelated
        // box is ticked is the trap this file keeps recording.
        eq("the catalogue needs its own setting as well as the attack one",
                tSrc.contains("config.attackStyleEnabled() && config.npcAttackLogEnabled()"), true);
        eq("and that setting defaults to OFF",
                cfgSrc.contains("default boolean npcAttackLogEnabled() { return false; }"), true);
        // A count alone cannot tell "24 of 50 parsed" from "24 were written".
        eq("unreadable lines in the file are named, not silently dropped",
                aSrc.contains("ok_with_"), true);

        // ---- 2.133: THE BUGS JOSH'S FIRST TEST FOUND ----------------------
        //
        // He ticked the setting after RuneLite was running, so startUp's
        // one-shot load never fired and the first flush would have written
        // ONLY that session's rows over the file. att_log_file read "(none)"
        // while rows were being written, which is how it surfaced.
        eq("the load is ensured on every record, not only at startUp",
                aSrc.contains("ensureLoaded();"), true);
        eq("and ensureLoaded does NOT latch when there is no file yet",
                aSrc.contains("if (loaded || source == null) return;"), true);
        eq("the path is recorded by the writer too, so it cannot report (none)",
                aSrc.matches("(?s).*static void write\\(File f, String body\\).*"
                           + "path = f\\.getAbsolutePath\\(\\);.*"), true);
        // Once a tick, not once per animation - attLogFile() reads config and
        // calls mkdirs.
        eq("the source is handed over once a tick, not on every swing",
                tSrc.contains("NpcAttackLog.setSource(attLogFile());")
             && !tSrc.contains("NpcAttackLog.setSource(attLogFile());\n                NpcAttackLog.record"), true);

        // THE GAP, which is how an attack is told from a BLOCK animation - a
        // defend animation passes every gate this log has and would otherwise
        // land as a mystery never_projectiled attack, which is the one verdict
        // that matters most.
        eq("the gap between an NPC's animations is recorded",
                tSrc.contains("+ \",gap=\" + gap"), true);
        eq("and kept per row as a range",
                aSrc.contains("r.minGap") && aSrc.contains("r.maxGap"), true);
        // SCOPED TO verdict(), NOT THE WHOLE FILE. The first version asserted
        // that "minGap <" appears nowhere, and it failed a correct file: that
        // text is the min-TRACKING in record(), which is exactly what the gap
        // is for. A negative over a whole file is the same mistake as the
        // animation-mapping assertion earlier today - what is actually wanted
        // is that the VERDICT does not read the gap, so that is what is
        // checked, in the one method that draws conclusions.
        java.util.regex.Matcher vm = java.util.regex.Pattern
                .compile("static String verdict\\(Row r\\)\\s*\\{(.*?)\\n    \\}",
                         java.util.regex.Pattern.DOTALL).matcher(aSrc);
        eq("verdict() is found", vm.find(), true);
        String vBody = vm.reset().find() ? vm.group(1) : "";
        eq("nothing concludes from the gap - verdict never reads it",
                !vBody.contains("Gap"), true);

        // A v1 FILE MUST STILL LOAD. The name column moved from 7 to 9, so a
        // fixed index would have put a NUMBER in every old row's name and
        // lost the gaps on every new one.
        eq("the loader decides by column count, so an older file still reads",
                aSrc.contains("if (p.length >= 10)") && aSrc.contains("r.name = p[7];"), true);
        eq("and the file carries its format version",
                aSrc.contains("VERSION = \"2\""), true);

        // The measured entry from that run: Frost Nagua animation 11566 threw
        // projectile 2948 fifteen times out of fifteen, and Josh named the
        // style. The first row in this table from our OWN observation.
        eq("Frost Nagua's magic projectile is in the table, measured here",
                ProjectileStyles.styleOf(2948), "MAGIC");
        eq("and its never-projectiled animation was NOT guessed at",
                tSrc.contains("11568") || aSrc.contains("11568"), false);

        // ---- 2.134: ASK THE PROJECTILE WHO FIRED IT ------------------------
        //
        // 2.131-2.133 stamped a projectile onto whichever NPC animated most
        // recently. Right with one attacker, nonsense in a crowd - Josh's
        // first Zulrah trip produced npc=Zulrah,anim=5069,proj=[1044, 1046,
        // 2487, 2489] because snakelings were firing at the same time. The
        // rows looked authoritative and were cross-contaminated.
        eq("the projectile's own source actor decides the pairing",
                tSrc.contains("Actor src = p.getSourceActor();"), true);
        // NO SOURCE, NO PAIRING. Falling back to "the newest row" is the bug.
        eq("an unsourced projectile is counted and dropped, never guessed onto a row",
                tSrc.contains("if (srcId < 0) { attProjUnsourced++; return; }"), true);

        // ---- 2.135: getSourceActor() IS OFTEN NULL ------------------------
        //
        // Measured on Josh's second Zulrah trip, after 2.134: every row came
        // back with_proj=0 including Zulrah at dist 6-10. 2.134 traded a wrong
        // pairing for NO pairing and called it safe - the same data loss in a
        // humbler coat. getSourcePoint() places it by tile instead.
        eq("there is a spatial fallback when the source actor is null",
                tSrc.contains("WorldPoint sp = p.getSourcePoint();"), true);
        // A LARGE NPC OCCUPIES A FOOTPRINT. getWorldLocation() is the
        // south-west corner and Zulrah is 3x3, so an exact tile match would
        // make this dead code for every boss that matters.
        eq("and it matches the NPC's whole footprint, not one tile",
                tSrc.contains("if (dx < 0 || dy < 0 || dx >= size || dy >= size) continue;"), true);
        // EXACTLY ONE CANDIDATE OR NOTHING - ambiguity is how 2.134's rows
        // got contaminated and must not be resolved by picking.
        eq("two candidates is ambiguous and pairs nothing",
                tSrc.contains("if (matches == 1 && found >= 0)")
             && tSrc.contains("else if (matches > 1) attProjAmbiguous++;"), true);
        // 2.134 counted the failures and never emitted them, which is how a
        // whole trip read as melee-only with nothing saying otherwise.
        for (String pf : new String[]{"att_proj_paired", "att_proj_unsourced",
                                      "att_proj_ambiguous"})
            eq("the feed publishes " + pf, tSrc.contains("sq.append(\"" + pf + "=\")"), true);
        eq("and each row says how its projectile was placed",
                tSrc.contains("+ \",proj_src=\" + how"), true);
        eq("and the row it pairs to is matched BY NPC, not by being newest",
                tSrc.contains("if (attSeenNpcId[ix] != srcId) continue;"), true);
        // The old correlation window and its field are gone: a number nobody
        // needs is a number someone will later trust.
        // THE EMITTED FIELD, not the word. The first form asserted the bare
        // name was absent and failed a CORRECT file - the comment explaining
        // its removal mentions it. FIFTH weak key of this exact shape, and
        // this one is a negative as well, so it failed in the direction that
        // blocks a true statement rather than passing a false one.
        eq("the two-tick correlation window is gone",
                tSrc.contains("\",proj_after_ticks=\""), false);

        // The gap was per-SESSION-last-NPC, which is -1 on every row in a
        // crowd - and it was, on all ten of Josh's.
        eq("the animation gap is tracked per NPC, not in one slot",
                tSrc.contains("attLastAnimTickByNpc"), true);
        eq("and that map has a runaway stop",
                tSrc.contains("attLastAnimTickByNpc.size() > 256"), true);

        // Destructive, so it must be spelled out and must report what it did.
        eq("the catalogue can be reset, but only by an explicit reset=yes",
                tSrc.contains("q.contains(\"reset=yes\")"), true);
        eq("and the reset says what it discarded rather than nothing",
                aSrc.contains("return \"discarded \" + n + \" row(s)\";"), true);

        // THE ORDER. A live projectile must outrank the table, because it is
        // the only thing that can follow a switcher; the table must outrank
        // adjacency, because adjacency can only ever say MELEE.
        int iProj = tSrc.indexOf("conf = \"projectile\"");
        int iTbl  = tSrc.indexOf("conf = \"npc_data_sole_style\"");
        int iAdj  = tSrc.indexOf("conf = \"adjacent_attackable_no_projectile\"");
        eq("projectile outranks the table, and the table outranks adjacency",
                iProj > 0 && iTbl > iProj && iAdj > iTbl, true);

        // ONE SPELLING OF STYLE -> PRAYER, so a consumer never re-derives it.
        eq("att_overhead is published for whatever decided the style",
                tSrc.contains("sb.append(\"att_overhead=\")"), true);
        eq("and RANGED_OR_MAGIC maps to no prayer at all",
                NpcStyleData.overheadFor("RANGED_OR_MAGIC"), "");
        eq("as do NONE and UNKNOWN",
                NpcStyleData.overheadFor("NONE") + NpcStyleData.overheadFor("UNKNOWN"), "");

        // ---- 2.137: THE BUILT-IN INTERFACE WIDGETS ------------------------
        // Josh wanted the widgets the suite clicks hard-coded so nobody has to
        // push ids to a VM. They are spelled from InterfaceID SYMBOLS, which
        // means a renamed component fails the build - but a symbol that still
        // exists and has been RENUMBERED would compile and point somewhere else
        // in silence, which is the whole varbit-4398 failure again.
        //
        // So the real compiled method is called here and its output compared
        // against the ids Josh read out of the Widget Inspector and I confirmed
        // live on Gumps-Credit, 2026-10-29. This is the only thing standing
        // between a future renumber and a confident click on the wrong glyph.
        String bi = GEVisualAidPlugin.wgBuiltinInterface();

        String[][] wantPairs = {
                // the three the flipper actually asks for
                { "UI_Tabs_Stats_Tab_Click_Box",     "164.53" },
                { "UI_Tabs_Inventory_Tab_Click_Box", "164.55" },
                { "UI_Tabs_Friends_Tab_Click_Box",   "164.40" },
                // the two whose SYMBOL NAMES look wrong and are right.
                // ICON8=Account, ICON7=Clan. Drawn order is Friends, Account,
                // Clan - not id order. If these two ever swap, the idle tab
                // click lands on the wrong panel and nothing errors.
                { "UI_Tabs_Account_Tab_Click_Box",   "164.45" },
                { "UI_Tabs_Clan_Tab_Click_Box",      "164.44" },
                { "Orbs_Prayer_Orb",                 "160.25" },
                { "Orbs_Run_Orb",                    "160.30" },
                { "Orbs_Health_Orb",                 "160.11" },
                { "Compass_Compass_Click_Area",      "164.31" },
                // the insets are part of the id and must survive
                { "Logout_Panel_Logout_Button_Click_Area", "182.8@50" },
                { "Logout_Panel_Logout_X_Button",    "164.34@80" },
                // a BRACKETED CHILD, not a component
                { "GE_Buttons_Bank_X_Button",        "12.2.11" },
                { "Bank_Bank_Deposit_Inventory",     "12.47" },
                { "GE_Buttons_History_Exchange_Button", "465.3" },
                { "GE_Buttons_Exchange_Back_Button", "383.2" },
                // PRAYER13/14/15 are magic/missiles/melee in that order
                { "Prayers_Protect_from_Magic",      "541.21" },
                { "Prayers_Protect_from_Missiles",   "541.22" },
                { "Prayers_Protect_from_Melee",      "541.23" },
                { "Prayers_Rigour",                  "541.25" },
                { "Prayers_Augury",                  "541.33" },
                { "Prayers_Piety",                   "541.35" },
        };
        for (String[] wp : wantPairs)
            eq("built-in widget " + wp[0] + " resolves to the id read off the live client",
                    countOccurrences(bi, wp[0] + "=" + wp[1]), 1);

        String[] entries = bi.split(",");
        eq("the built-in set is 29 widgets", entries.length, 29);

        // A DUPLICATE LABEL IS SILENTLY DROPPED by selectResults, so the second
        // one matches nothing for ever while looking configured. Counted, not
        // contained - a label could legitimately appear twice in this file.
        java.util.Set<String> seenLabels = new java.util.HashSet<>();
        boolean dupLabel = false;
        for (String e : entries)
        {
            int q = e.indexOf('=');
            if (q <= 0) { dupLabel = true; break; }
            if (!seenLabels.add(e.substring(0, q))) { dupLabel = true; break; }
        }
        eq("no built-in label is duplicated, and every entry has an id", dupLabel, false);

        // Exactly two insets. An inset silently applied to the other 27 would
        // shrink every target about its centre - still inside the rect, so it
        // would look fine and quietly stop hitting small glyphs at the edges.
        eq("only the two measured insets are present", countOccurrences(bi, "@"), 2);

        // ---- the call site, not just the method -------------------------
        // A correct list nobody merges is the same as no list. Same reason
        // filterNameMatches asserts its caller.
        eq("rebuildEntitySets appends the built-in interface widgets",
                src.contains("wgBuiltinInterface().split(\",\")"), true);
        eq("and it is gated on widgetBuiltinsEnabled, because 29 widgets is not free",
                src.contains("config.widgetBuiltinsEnabled()"), true);
        eq("appended PER LABEL, so hand-writing one tab cannot suppress the other 28",
                src.contains("if (wgts.indexOf(entry.substring(0, eq) + \"=\") >= 0) continue;"), true);

        // high_alch and inv_1..28 must NOT fall inside the new gate - they ship
        // unconditionally today and a VM that turns the built-ins off still
        // needs its inventory, which Flipping.ahk V11.2 now asks for by inv_N.
        int iHigh  = src.indexOf("wgts.append(WG_BUILTIN_HIGH_ALCH);");
        int iInv   = src.indexOf("wgts.append(wgBuiltinInventory());");
        int iGate  = src.indexOf("boolean wantBuiltinInterface");
        eq("high_alch is appended before the built-in gate, so it stays unconditional",
                iHigh > 0 && iGate > iHigh, true);
        eq("inv_1..28 are appended before the built-in gate too",
                iInv > 0 && iGate > iInv, true);

        // ---- the merge runs even with Scene & Tiles off -------------------
        // rebuildEntitySets had ONE caller, inside buildSceneBlock, so with
        // scene tracking off esWidgets stayed "" and /widgets blamed the
        // operator for ids that were present. ORDER asserted, not presence:
        // after updateSceneState it would be a tick late every tick.
        int iTickFn = src.indexOf("public void onGameTick(GameTick tick)");
        int iRebuild = src.indexOf("try { rebuildEntitySets(); } catch (Throwable ignored) { }", iTickFn);
        int iScene   = src.indexOf("updateSceneState();", iTickFn);
        eq("onGameTick rebuilds the entity sets itself", iTickFn > 0 && iRebuild > iTickFn, true);
        // AFTER the scene build, not before. Putting it before pushed work in
        // between stampGameTick() and updateSceneState() and turned
        // test_tick_toggle red, correctly: nothing belongs in front of the
        // scene work but the tick stamp. Nothing is lost by running after -
        // buildSceneBlock calls this itself before its own appenders, so the
        // scene path is never stale, and the scene-OFF path (the only reason
        // this call exists) does not care when in the tick it runs.
        eq("and does it AFTER updateSceneState, so the tick stamp stays the first thing in onGameTick",
                iRebuild > 0 && iScene > 0 && iRebuild > iScene, true);

        // ---- the blank says which blank it is ----------------------------
        eq("/widgets names Scene & Tiles when that is why there is no reading",
                src.contains("SCENE & TILES IS OFF"), true);
        eq("and tells 'not yet' from 'not ever' by reading the toggle",
                src.contains("sceneOn = config.sceneTrackingEnabled()"), true);
        eq("the empty-list message no longer blames the operator on its own",
                src.contains("the built-ins are switched off AND nothing is"), true);

        System.out.println(fails == 0 ? "MlmCheck: PASS" : ("MlmCheck: " + fails + " FAILURE(S)"));
        if (fails != 0) System.exit(1);
    }

    /**
     * A REAL id out of the bundled table with exactly this style mask.
     *
     * Hardcoding an id here would be a second table, and one that goes stale
     * the first time the wiki data is rebuilt - so the id is DISCOVERED from
     * the file the code under test actually reads. Returns -1 if the file has
     * no such row, which fails the assertion rather than passing it quietly.
     */
    /**
     * The two projectile windows, read out of the SOURCE rather than
     * hardcoded here. A copy of the number in the checker would agree with
     * itself for ever while the plugin drifted - the second-spelling trap.
     * Returns -1 when the constant cannot be found, which fails the
     * assertion rather than passing it.
     */
    private static long constMs(String src, String name)
    {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "\\s*=\\s*(\\d+)").matcher(src);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static long ATT_PROJ_HOLD_MS_from(String src)
    { return constMs(src, "ATT_PROJ_HOLD_MS"); }

    private static long ATT_PROJ_MS_from(String src)
    { return constMs(src, "ATT_PROJ_MS"); }

    private static int soleIdWithMask(int mask)
    {
        for (int id = 0; id < 60000; id++)
            if (NpcStyleData.styleMask(id) == mask) return id;
        return -1;
    }
}

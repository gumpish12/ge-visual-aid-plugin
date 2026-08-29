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

        // ---- 6. the version string actually moved -----------------------
        f = c.getDeclaredField("PLUGIN_OUTPUT_VERSION"); f.setAccessible(true);
        eq("PLUGIN_OUTPUT_VERSION", f.get(null), "2.91");

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

        System.out.println(fails == 0 ? "MlmCheck: PASS" : ("MlmCheck: " + fails + " FAILURE(S)"));
        if (fails != 0) System.exit(1);
    }
}

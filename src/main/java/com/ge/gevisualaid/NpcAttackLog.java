package com.ge.gevisualaid;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Every attack animation ever seen aimed at the player, COLLATED and kept.
 *
 * <h3>What this is for</h3>
 *
 * Josh, 2026-10-28: <em>"Does this just have to be a boss, or can I leave it
 * running for all NPCS and we just collate them over time? If im reading it
 * right, i can just fight an NPC, map out all of their attacks, then that is
 * saved?"</em>
 *
 * <p>Half right, and the half that was wrong is why this class exists. 2.131's
 * {@code att_seen_N} covers every NPC already - there was never a boss
 * restriction - but it is a 24-row ring in memory and it is gone on restart.
 * It answers "what just hit me", not "what can this monster do".
 *
 * <h3>The key is the PAIR, not the NPC</h3>
 *
 * Rows are keyed {@code npcId:animId}, because one monster has several attacks
 * and the whole question is which of them is which. A Tormented Demon with
 * three animations is three rows, and the row that never carries a projectile
 * is the one Josh is hunting.
 *
 * <h3>It records observations and never a style</h3>
 *
 * The only things stored are counts and ids actually seen:
 *
 * <ul>
 *   <li>{@code seen} - how many times this animation has been played at us</li>
 *   <li>{@code withProj} - how many of those had a projectile follow</li>
 *   <li>{@code projIds} - which projectiles, as a set</li>
 *   <li>{@code minDist} / {@code maxDist} - the range it was used from</li>
 * </ul>
 *
 * <p><b>"Never seen with a projectile" is worth nothing after one sample and a
 * great deal after fifty</b>, so {@link #verdict} refuses to say anything until
 * {@link #MIN_SAMPLES} is reached and names the shortfall instead. Same rule as
 * {@code MLM_ETA_MIN_SAMPLES} and {@code mlm_sack_witnessed}: never conclude
 * from a number you have barely watched.
 *
 * <p>An animation ALWAYS paired with one projectile id is a real measurement,
 * and the projectile table already knows that id's style - so the chain
 * animation to projectile to style contains no guess at any link. An animation
 * never paired with anything stays UNKNOWN, and that is the honest answer:
 * melee and a ranged-styled attack with no projectile are indistinguishable
 * from here, which is exactly what Josh has to tell us by watching the fight.
 */
final class NpcAttackLog
{
    /** Below this, verdict() refuses to draw a conclusion. */
    static final int MIN_SAMPLES = 12;

    private static final String FILE    = "npc_attacks.txt";
    private static final String VERSION = "2";   // 2.133 added min_gap/max_gap

    static final class Row
    {
        int    npcId, anim;
        String name = "";
        int    seen, withProj;
        int    minDist = -1, maxDist = -1;
        // 2.133 — the cadence this animation appears on. An attack fires on
        // the monster's own interval; a BLOCK fires when our weapon lands, so
        // its gaps are ragged and can be shorter than any attack speed. Raw,
        // and nothing concludes from it: "which cadence is an attack" is per
        // monster and per weapon and nobody here has measured one.
        int    minGap = -1, maxGap = -1;
        final TreeSet<Integer> projIds = new TreeSet<>();
    }

    // LinkedHashMap so the file keeps a stable order between writes and a
    // diff of two runs is readable.
    private static final Map<String, Row> ROWS = new LinkedHashMap<>();

    // 2.133 — the file to load from, handed over by the plugin so this class
    // never has to know about config. Set once; ensureLoaded() uses it so a
    // load can happen on first use rather than only at startUp.
    private static volatile File source = null;

    static void setSource(File f)
    {
        if (f != null) source = f;
    }

    /**
     * Idempotent, and safe to call on every record.
     *
     * WITH NO SOURCE YET IT DOES NOTHING AND DOES NOT LATCH. Calling
     * load(null) here would set loaded=true and state=no_output_folder, and
     * the catalogue would then never read the file for the rest of the
     * session - which is the exact truncation bug this method was added to
     * fix, reintroduced one line further down.
     */
    static synchronized void ensureLoaded()
    {
        if (loaded || source == null) return;
        load(source);
    }

    private static boolean loaded = false;
    private static volatile boolean dirty = false;
    private static volatile String  state = "not_loaded";
    private static volatile String  path  = "";

    private NpcAttackLog() { }

    static String state() { return state; }
    static String path()  { return path; }

    static synchronized int rows() { return ROWS.size(); }

    private static String key(int npcId, int anim) { return npcId + ":" + anim; }

    // -----------------------------------------------------------------------
    // Recording. Called on the client thread from onAnimationChanged.
    // -----------------------------------------------------------------------
    static synchronized void record(int npcId, int anim, String name, int dist, int gap)
    {
        if (npcId < 0 || anim < 0) return;
        // 2.133 - ENSURE-LOADED, NOT LOAD-AT-STARTUP. Josh's first test found
        // this: he ticked the setting after RuneLite was already running, so
        // startUp's load never fired, `loaded` stayed false, and the first
        // flush would have written ONLY this session's rows over whatever was
        // on disk. The class exists to accumulate and it would have silently
        // truncated instead. A one-shot load at startup is wrong for anything
        // behind a setting that can be ticked mid-session.
        ensureLoaded();
        Row r = ROWS.get(key(npcId, anim));
        if (r == null)
        {
            r = new Row();
            r.npcId = npcId;
            r.anim  = anim;
            ROWS.put(key(npcId, anim), r);
        }
        if (name != null && !name.isEmpty()) r.name = name;
        r.seen++;
        if (dist >= 0)
        {
            if (r.minDist < 0 || dist < r.minDist) r.minDist = dist;
            if (dist > r.maxDist) r.maxDist = dist;
        }
        if (gap >= 0)
        {
            if (r.minGap < 0 || gap < r.minGap) r.minGap = gap;
            if (gap > r.maxGap) r.maxGap = gap;
        }
        dirty = true;
    }

    /**
     * A projectile followed that animation. SEPARATE from record() because the
     * projectile arrives a tick later - the caller owns the correlation and
     * its window, this only stores what it is told.
     */
    static synchronized void recordProjectile(int npcId, int anim, int projId)
    {
        Row r = ROWS.get(key(npcId, anim));
        if (r == null || projId < 0) return;
        r.withProj++;
        r.projIds.add(projId);
        dirty = true;
    }

    // -----------------------------------------------------------------------
    // What can honestly be said about a row.
    // -----------------------------------------------------------------------
    static String verdict(Row r)
    {
        if (r.seen < MIN_SAMPLES)
            return "too_few_samples:" + r.seen + "_of_" + MIN_SAMPLES;
        if (r.withProj == 0)
            return "never_projectiled";          // melee, or a ranged-styled
                                                 // attack with nothing in
                                                 // flight. NOT decided here.
        if (r.withProj == r.seen && r.projIds.size() == 1)
        {
            int pid = r.projIds.first();
            String st = ProjectileStyles.styleOf(pid);
            // THE CHAIN WITH NO GUESS IN IT: this animation has only ever
            // thrown this projectile, and the projectile table knows that id.
            return ProjectileStyles.UNKNOWN.equals(st)
                    ? ("always_projectile:" + pid + ":not_in_table")
                    : ("always_projectile:" + pid + ":" + st);
        }
        // Mixed. One animation that sometimes throws something and sometimes
        // does not is a real finding and must not be averaged away.
        return "mixed:" + r.withProj + "_of_" + r.seen
             + ":projectiles=" + r.projIds;
    }

    // -----------------------------------------------------------------------
    // The published block, newest-busiest first so the thing being fought now
    // is near the top.
    // -----------------------------------------------------------------------
    static synchronized String block(int cap)
    {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("att_log_state=").append(state).append("\n");
        sb.append("att_log_file=").append(path.isEmpty() ? "(none)" : path).append("\n");
        sb.append("att_log_rows=").append(ROWS.size()).append("\n");
        sb.append("att_log_min_samples=").append(MIN_SAMPLES).append("\n");
        sb.append("att_log_cap=").append(cap).append("\n");

        // 2.133 — SHARE OF THAT NPC'S ATTACKS. Josh: "the frost nagua did a
        // special attack every so often, which i had to avoid. im happy to
        // manually deal with those."
        //
        // A rare animation and a main attack look identical in a seen count
        // until you know the total, and the difference is the whole point: a
        // special is something to AVOID and a main attack is something to
        // pray. share=3% beside share=94% says which is which at a glance,
        // where seen=2 beside seen=15 does not - that reads as "needs more
        // sampling" when it may already be fully sampled and simply rare.
        Map<Integer, Integer> totals = new LinkedHashMap<>();
        for (Row r : ROWS.values())
        {
            Integer t = totals.get(r.npcId);
            totals.put(r.npcId, (t == null ? 0 : t) + r.seen);
        }

        List<Row> all = new ArrayList<>(ROWS.values());
        Collections.sort(all, (a, b) -> b.seen - a.seen);
        int i = 0;
        for (Row r : all)
        {
            if (i >= cap) break;
            sb.append("att_log_").append(i).append('=')
              .append("npc=").append(r.name)
              .append(",id=").append(r.npcId)
              .append(",anim=").append(r.anim)
              .append(",seen=").append(r.seen)
              .append(",share=").append(sharePct(r, totals)).append('%')
              .append(",with_proj=").append(r.withProj)
              .append(",proj=").append(r.projIds.isEmpty() ? "none" : r.projIds)
              .append(",dist=").append(r.minDist).append('-').append(r.maxDist)
              .append(",gap=").append(r.minGap).append('-').append(r.maxGap)
              .append(",verdict=").append(verdict(r))
              .append('\n');
            i++;
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Disk. Tab separated, one line per pair, with a header that carries the
    // format version - so a future column can be added without the old file
    // being read as though it had it.
    // -----------------------------------------------------------------------
    static synchronized void load(File f)
    {
        if (loaded) return;
        loaded = true;                      // FIRST: a throw must not retry
        if (f == null) { state = "no_output_folder"; return; }
        path = f.getAbsolutePath();
        try
        {
            if (!f.exists()) { state = "new_file"; return; }
            int bad = 0;
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
            {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 8) { bad++; continue; }
                try
                {
                    Row r = new Row();
                    r.npcId    = Integer.parseInt(p[0].trim());
                    r.anim     = Integer.parseInt(p[1].trim());
                    r.seen     = Integer.parseInt(p[2].trim());
                    r.withProj = Integer.parseInt(p[3].trim());
                    if (!p[4].trim().isEmpty())
                        for (String s : p[4].split(","))
                            if (!s.trim().isEmpty()) r.projIds.add(Integer.parseInt(s.trim()));
                    r.minDist = Integer.parseInt(p[5].trim());
                    r.maxDist = Integer.parseInt(p[6].trim());
                    // 2.133 ADDED TWO COLUMNS, AND A v1 FILE MUST STILL LOAD.
                    // The name moved from index 7 to index 9. Reading it by a
                    // fixed index would have put a NUMBER in the name column
                    // for every old row and lost the gaps for every new one -
                    // so the column count decides, which is what the version
                    // header in the file was written for.
                    if (p.length >= 10)
                    {
                        r.minGap = Integer.parseInt(p[7].trim());
                        r.maxGap = Integer.parseInt(p[8].trim());
                        r.name   = p[9];
                    }
                    else
                    {
                        r.name = p[7];      // v1: no gaps were recorded
                    }
                    ROWS.put(key(r.npcId, r.anim), r);
                }
                catch (NumberFormatException e) { bad++; }
            }
            // A COUNT ALONE CANNOT TELL "24 of 50 parsed" FROM "24 WERE
            // WRITTEN" - the 2.80 widget-list lesson. So bad lines are named.
            state = bad == 0 ? "ok" : ("ok_with_" + bad + "_unreadable_lines");
        }
        catch (Throwable t)
        {
            state = "read_error:" + t.getClass().getSimpleName();
        }
    }

    /** A snapshot under the lock; the caller writes it off the client thread. */
    static synchronized String snapshotIfDirty()
    {
        if (!dirty) return null;
        dirty = false;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# GEVisualAid npc_attacks v").append(VERSION).append('\n');
        sb.append("# npc_id\tanim\tseen\twith_proj\tproj_ids\tmin_dist\tmax_dist"
                + "\tmin_gap\tmax_gap\tname\n");
        for (Row r : ROWS.values())
        {
            StringBuilder ids = new StringBuilder();
            for (Integer p : r.projIds)
            {
                if (ids.length() > 0) ids.append(',');
                ids.append(p);
            }
            sb.append(r.npcId).append('\t').append(r.anim).append('\t')
              .append(r.seen).append('\t').append(r.withProj).append('\t')
              .append(ids).append('\t')
              .append(r.minDist).append('\t').append(r.maxDist).append('\t')
              .append(r.minGap).append('\t').append(r.maxGap).append('\t')
              .append(r.name == null ? "" : r.name.replace('\t', ' ').replace('\n', ' '))
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * WRITE TO A TEMPORARY FILE AND REPLACE. A long accumulation truncated by
     * a crash mid-write would be worse than never having saved it, and this
     * file is only valuable because it is cumulative.
     */
    static void write(File f, String body)
    {
        if (f == null || body == null) return;
        try
        {
            File tmp = new File(f.getParentFile(), FILE + ".tmp");
            Files.write(tmp.toPath(), body.getBytes(StandardCharsets.UTF_8));
            // 2.133 - the path is recorded HERE as well as in load(). Josh's
            // first test showed att_log_file=(none) while rows were being
            // written: path was only ever set by load(), which had not run.
            // A page that cannot name the file it is writing is the "configured
            // but not working" reading all over again - it looked like nothing
            // was being saved when in fact something was.
            path = f.getAbsolutePath();
            try { Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (Throwable mv)
            {
                // Some filesystems refuse an atomic replace. A direct write is
                // still better than losing the session's observations.
                Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
            }
            state = "ok";
        }
        catch (Throwable t)
        {
            state = "write_error:" + t.getClass().getSimpleName();
        }
    }

    static String fileName() { return FILE; }

    /**
     * This animation's share of everything that NPC has been seen to do.
     *
     * NOT STORED, DERIVED. Storing it would be a second copy of a number that
     * is already the sum of the rows, and the two would drift the first time
     * one row was updated without the other - the portfolio_items lesson, that
     * a total which is a sum should be the terms taken apart rather than a
     * separate calculation.
     */
    private static int sharePct(Row r, Map<Integer, Integer> totals)
    {
        Integer t = totals.get(r.npcId);
        if (t == null || t <= 0) return -1;
        return (int) Math.round(100.0 * r.seen / t);
    }
}

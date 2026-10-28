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
    private static final String VERSION = "1";

    static final class Row
    {
        int    npcId, anim;
        String name = "";
        int    seen, withProj;
        int    minDist = -1, maxDist = -1;
        final TreeSet<Integer> projIds = new TreeSet<>();
    }

    // LinkedHashMap so the file keeps a stable order between writes and a
    // diff of two runs is readable.
    private static final Map<String, Row> ROWS = new LinkedHashMap<>();

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
    static synchronized void record(int npcId, int anim, String name, int dist)
    {
        if (npcId < 0 || anim < 0) return;
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
              .append(",with_proj=").append(r.withProj)
              .append(",proj=").append(r.projIds.isEmpty() ? "none" : r.projIds)
              .append(",dist=").append(r.minDist).append('-').append(r.maxDist)
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
                    r.name    = p[7];
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
        sb.append("# npc_id\tanim\tseen\twith_proj\tproj_ids\tmin_dist\tmax_dist\tname\n");
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
}

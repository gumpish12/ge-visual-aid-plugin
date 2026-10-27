package com.ge.gevisualaid;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-NPC attack style, max hit and aggression, keyed by NPC id.
 *
 * <h3>Why this exists, and why it is a table at all</h3>
 *
 * Josh, 2026-10-27: <em>"cant it just show npcs attacking me with mage, range
 * or melee? we have the bosses attacks covered"</em>. The 2.128 projectile
 * table answers ranged-vs-magic for 32 bosses. For an ordinary monster there
 * is no projectile in it, so the reading is UNKNOWN and nothing is prayed -
 * correct, and useless.
 *
 * <p>CLAUDE.md forbids lookup tables because <em>they rot silently and
 * mislabel rather than fail loudly</em>. Three things answer that objection
 * here, and all three are load-bearing:
 *
 * <ol>
 *   <li><b>IT CAN BE REBUILT.</b> {@code tools/build_npc_data.py} is the
 *       generator, and it queries the OSRS Wiki API. A table that regenerates
 *       from its source is a CACHE; a table nobody can rebuild is folklore.
 *       The file's own build date is published as
 *       {@code att_npc_data_version}, so staleness is visible rather than
 *       inferred.</li>
 *   <li><b>AMBIGUOUS IS NOT A GUESS.</b> The style field is a BITMASK, so a
 *       hybrid says so. Measured over the 4,024 rows: 3,084 are a single
 *       style and 732 carry two or three. {@link #soleStyle(int)} returns
 *       null for every one of those 732 - a Tormented Demon reads
 *       melee+ranged+magic and is therefore NOT answered from here. The
 *       projectile remains the only thing that disambiguates a switcher.</li>
 *   <li><b>MISSING IS A NAMED STATE.</b> An id absent from the file returns
 *       mask 0, which is reported as {@code unknown} and acted on by nobody.
 *       The failure mode is silence, not a wrong overhead.</li>
 * </ol>
 *
 * <h3>Provenance</h3>
 *
 * {@code npc_data.json} and {@code tools/build_npc_data.py} are taken from
 * Bellefeu's aggro-tag RuneLite plugin, BSD 2-Clause, copy of the licence in
 * {@code tools/npc_data_LICENSE_aggro-tag.txt}. Josh dropped the source in
 * {@code osrs-suite/incoming/}. The encoding was read out of its own
 * {@code NpcDataLoader} javadoc rather than inferred from the numbers:
 * {@code "m"} max hit, {@code "a"} aggressive, {@code "s"} style bitmask with
 * {@code 1}=melee {@code 2}=ranged {@code 4}=magic, 0 or absent = unknown.
 *
 * <h3>Cost</h3>
 *
 * Nothing is read until {@link #ensureLoaded()} is called, and that only
 * happens when the setting is on. A flipping VM never opens the file. Once
 * loaded it is one {@code HashMap} lookup per tick on the attacker's id.
 */
final class NpcStyleData
{
    static final int MELEE  = 1;
    static final int RANGED = 2;
    static final int MAGIC  = 4;

    private static final String RESOURCE = "/com/ge/gevisualaid/npc_data.json";

    // THE PARSE IS DONE ONCE AND ITS OUTCOME IS REMEMBERED, INCLUDING FAILURE.
    // Retrying a broken resource every tick would turn one problem into a
    // per-tick cost, and the state has to survive so /attack can say which
    // kind of nothing it is.
    private static volatile boolean loaded = false;
    private static volatile String  state  = "not_loaded";
    private static volatile int     rows   = 0;

    // id -> {maxHit, aggressive, styleMask}
    private static Map<Integer, int[]> data = new HashMap<>();

    private NpcStyleData() { }

    static synchronized void ensureLoaded()
    {
        if (loaded) return;
        loaded = true;                       // set FIRST: a throw must not retry
        try (InputStream in = NpcStyleData.class.getResourceAsStream(RESOURCE))
        {
            if (in == null)
            {
                state = "resource_missing";
                return;
            }
            Map<Integer, int[]> m = new HashMap<>(6000);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
            {
                parse(r, m);
            }
            data  = m;
            rows  = m.size();
            state = rows > 0 ? "ok" : "empty";
        }
        catch (Throwable t)
        {
            state = "read_error:" + t.getClass().getSimpleName();
        }
    }

    /**
     * A hand parser, deliberately.
     *
     * <p>The file is one flat object of {@code "id":{"m":n,"a":n,"s":n,"f":"x"}}
     * and nothing else, so this needs no JSON library on the plugin's
     * classpath and cannot be broken by one changing version. It reads keys by
     * NAME rather than by position - {@code "f"} appears on only 57 rows and
     * {@code "s"} is absent wherever the wiki lists no style, so anything
     * positional would silently shear the values across.
     */
    private static void parse(Reader r, Map<Integer, int[]> out) throws Exception
    {
        StringBuilder sb = new StringBuilder(128 * 1024);
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        String s = sb.toString();

        int i = 0, len = s.length();
        while (i < len)
        {
            int ks = s.indexOf('"', i);
            if (ks < 0) break;
            int ke = s.indexOf('"', ks + 1);
            if (ke < 0) break;
            String key = s.substring(ks + 1, ke);
            int brace = s.indexOf('{', ke);
            if (brace < 0) break;
            int end = s.indexOf('}', brace);
            if (end < 0) break;

            int id;
            try { id = Integer.parseInt(key); }
            catch (NumberFormatException e) { i = end + 1; continue; }

            String rec = s.substring(brace + 1, end);
            out.put(id, new int[]{ field(rec, 'm'), field(rec, 'a'), field(rec, 's') });
            i = end + 1;
        }
    }

    // -1 when the key is absent, which is NOT the same as 0. A max hit of 0 is
    // a real reading for something that cannot hurt you.
    private static int field(String rec, char name)
    {
        String needle = "\"" + name + "\":";
        int p = rec.indexOf(needle);
        if (p < 0) return -1;
        p += needle.length();
        int q = p;
        while (q < rec.length() && (Character.isDigit(rec.charAt(q)) || rec.charAt(q) == '-')) q++;
        if (q == p) return -1;
        try { return Integer.parseInt(rec.substring(p, q)); }
        catch (NumberFormatException e) { return -1; }
    }

    static String state()  { ensureLoaded(); return state; }
    static int    rows()   { ensureLoaded(); return rows; }

    /** The style bitmask, or 0 for "this id is not in the file". */
    static int styleMask(int npcId)
    {
        ensureLoaded();
        int[] rec = data.get(npcId);
        if (rec == null) return 0;
        return rec[2] < 0 ? 0 : rec[2];
    }

    /** Max hit, or -1 when unknown. */
    static int maxHit(int npcId)
    {
        ensureLoaded();
        int[] rec = data.get(npcId);
        return rec == null ? -1 : rec[0];
    }

    /** 1 aggressive, 0 not, -1 unknown. */
    static int aggressive(int npcId)
    {
        ensureLoaded();
        int[] rec = data.get(npcId);
        return rec == null ? -1 : rec[1];
    }

    /**
     * The one style this NPC has, or null if it has none listed or more than
     * one. THE null IS THE POINT: 732 of the 4,024 rows are hybrids, and a
     * hybrid answered from this file would be a coin toss with a citation.
     */
    static String soleStyle(int npcId)
    {
        int m = styleMask(npcId);
        if (m == MELEE)  return "MELEE";
        if (m == RANGED) return "RANGED";
        if (m == MAGIC)  return "MAGIC";
        return null;
    }

    /** Every style listed, for the human view. "" when the id is absent. */
    static String styleNames(int npcId)
    {
        int m = styleMask(npcId);
        if (m == 0) return "";
        StringBuilder sb = new StringBuilder(18);
        if ((m & MELEE)  != 0) sb.append("melee");
        if ((m & RANGED) != 0) { if (sb.length() > 0) sb.append('+'); sb.append("ranged"); }
        if ((m & MAGIC)  != 0) { if (sb.length() > 0) sb.append('+'); sb.append("magic"); }
        return sb.toString();
    }

    /** The overhead prayer for a sole style, spelled as the Prayer enum does. */
    static String overheadFor(String style)
    {
        if ("MELEE".equals(style))  return "PROTECT_FROM_MELEE";
        if ("RANGED".equals(style)) return "PROTECT_FROM_MISSILES";
        if ("MAGIC".equals(style))  return "PROTECT_FROM_MAGIC";
        return "";
    }
}

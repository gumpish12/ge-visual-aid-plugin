package com.ge.gevisualaid;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 2.128 - WHICH STYLE AN INBOUND PROJECTILE IS, AND THE ONLY TABLE IN THIS
 * PLUGIN THAT IS ALLOWED TO EXIST.
 *
 * CLAUDE.md forbids lookup tables, and it is right about why: they rot
 * silently and MISLABEL rather than failing loudly. An animation-id table
 * calls the wrong skill; a rune-cost table charges the wrong price; both look
 * authoritative while being wrong.
 *
 * This one is different for one reason only: THERE IS NOTHING ELSE TO READ.
 * The client does not label a projectile's attack style - {@code Projectile}
 * carries an id, a target and a flight time, and nothing about whether it is
 * an arrow or a spell. No varbit, no widget, no API field. 2.126 therefore
 * published {@code att_style=RANGED_OR_MAGIC} and
 * {@code osrs-suite/docs/LEARNINGS.md} recorded "acting on RANGED_OR_MAGIC
 * for prayer switching" as DELIBERATELY REJECTED, because a wrong overhead
 * drains prayer points while protecting against nothing.
 *
 * So the table is the mechanism, and the rot is made harmless instead:
 *
 *   - AN ID THAT IS NOT IN HERE RETURNS UNKNOWN, NEVER A GUESS. If Jagex
 *     renumbers a projectile the entry stops matching and the consumer is
 *     told it does not know - it does not start praying the other style.
 *     That turns the forbidden failure (mislabel) into the allowed one
 *     (say nothing), which is the whole reason this is safe.
 *   - NO ID MAY CARRY TWO STYLES. {@link #conflicts()} counts them and
 *     {@code mlmCheck} asserts the count is zero, so a careless edit that
 *     made 2487 both ranged and magic fails the CHECK rather than deciding
 *     it at random.
 *   - THE BOSS NAME GOES OUT BESIDE THE STYLE. A reader that can see
 *     "RANGED, because this is Leviathan's 2487" can tell a correct match
 *     from a coincidence; "RANGED" alone cannot be checked against anything.
 *
 * SOURCE: the ids are transcribed from the Projectile Override plugin
 * (Loze-Put, plugin hub "projectile-override"), whose own
 * {@code BossProjectiles} enum groups each boss's projectiles by style so it
 * can recolour them. Transcribed from its source in
 * {@code osrs-suite/incoming/Loze-Put-projectile-override-eb637e9}, not from
 * the wiki: that plugin is maintained against the live game by someone who
 * has to look at these projectiles, which is a better authority than a wiki
 * page and a far better one than memory. 32 bosses, 76 distinct ids, checked
 * for cross-style conflicts before it was written down (there are none).
 *
 * COVERAGE IS BOSSES ONLY, AND THE FEED SAYS SO. An ordinary slayer monster's
 * projectile is not in here and reads UNKNOWN. That is a gap, not a bug - the
 * alternative was inventing a style for it.
 */
final class ProjectileStyles
{
    static final String MAGIC   = "MAGIC";
    static final String RANGED  = "RANGED";
    static final String MELEE   = "MELEE";
    static final String UNKNOWN = "UNKNOWN";

    /** id -> {style, boss}. Immutable after class init. */
    private static final Map<Integer, String[]> BY_ID;

    /** How many ids were handed two different styles while building. */
    private static final int CONFLICTS;

    static
    {
        Map<Integer, String[]> m = new HashMap<>();
        int clash = 0;

        // Each row is one boss: magic ids, then ranged ids, then melee ids.
        // Written out long-hand rather than parsed from a string, so a typo
        // is a compile error and not a silently dropped entry.
        clash += put(m, "Akkha",           new int[]{2253}, new int[]{2255}, new int[]{});
        clash += put(m, "Bork",            new int[]{4057}, new int[]{4056}, new int[]{});
        clash += put(m, "Cerberus",        new int[]{1242}, new int[]{1245}, new int[]{});
        clash += put(m, "DKS",             new int[]{162},  new int[]{475},  new int[]{});
        clash += put(m, "Dagon'hai Elder", new int[]{1459}, new int[]{4071}, new int[]{});
        clash += put(m, "Demonic Gorilla", new int[]{1304}, new int[]{1302}, new int[]{});
        clash += put(m, "Doom",            new int[]{3379}, new int[]{3380}, new int[]{3378});
        clash += put(m, "Doom rocks",      new int[]{3385}, new int[]{3384}, new int[]{});
        clash += put(m, "Hueycoatl",       new int[]{2975}, new int[]{2972}, new int[]{2969});
        clash += put(m, "Hunllef",         new int[]{1707}, new int[]{1711}, new int[]{});
        clash += put(m, "Hunllef (c)",     new int[]{1708}, new int[]{1712}, new int[]{});
        clash += put(m, "Hydra",           new int[]{1662}, new int[]{1663}, new int[]{});
        clash += put(m, "Inferno",         new int[]{1380}, new int[]{1378}, new int[]{});
        clash += put(m, "Kalphite Queen",  new int[]{280},  new int[]{288, 289}, new int[]{});
        clash += put(m, "Kree Arra",       new int[]{1200}, new int[]{1199}, new int[]{});
        clash += put(m, "Leviathan",       new int[]{2489}, new int[]{2487}, new int[]{2488});
        clash += put(m, "Maggot King",     new int[]{3445}, new int[]{1555}, new int[]{});
        clash += put(m, "Manticore",       new int[]{2681}, new int[]{2683}, new int[]{2685});
        clash += put(m, "Moia",            new int[]{4213}, new int[]{4214}, new int[]{});
        clash += put(m, "Ocellus",         new int[]{4229}, new int[]{4232}, new int[]{});
        clash += put(m, "Olm",             new int[]{1341}, new int[]{1343}, new int[]{1345});
        clash += put(m, "Scurrius",        new int[]{2640}, new int[]{2642}, new int[]{});
        clash += put(m, "Sotetseg",        new int[]{1606}, new int[]{1607}, new int[]{});
        clash += put(m, "Tormented Demon", new int[]{2853}, new int[]{2857}, new int[]{});
        clash += put(m, "Vardorvis",       new int[]{2520}, new int[]{2521}, new int[]{});
        clash += put(m, "Wardens",         new int[]{2224}, new int[]{2241}, new int[]{});
        clash += put(m, "Wardens divine",  new int[]{2208}, new int[]{2206}, new int[]{2204});
        clash += put(m, "Whisperer",       new int[]{2445}, new int[]{2444}, new int[]{});
        clash += put(m, "Xarth",           new int[]{4034}, new int[]{4374}, new int[]{});
        // MEASURED HERE, NOT LIFTED FROM THE PROJECTILE OVERRIDE PLUGIN.
        // 2.133, from the attack catalogue's first real run on Josh's account,
        // 2026-10-28: Frost Nagua (npc 13788), animation 11566, fifteen
        // samples, projectile 2948 on every single one -
        //   verdict=always_projectile:2948:not_in_table
        // and Josh fighting it: "then a mage frost nagua". So the id is its
        // magic attack. This is the first entry in this table that came from
        // the suite's own observation rather than someone else's source, and
        // it is exactly the chain the catalogue was built to produce: an
        // animation always paired with one projectile, plus the operator
        // naming the style once.
        //
        // Its OTHER animation, 11568, carried no projectile across 2 samples
        // and is deliberately NOT here. Two samples is nothing, and a melee
        // attack and a block animation look identical from this distance.
        clash += put(m, "Frost Nagua",     new int[]{2948}, new int[]{},     new int[]{});
        clash += put(m, "Zebak",           new int[]{2181}, new int[]{2187, 2188, 2189, 2190}, new int[]{});
        clash += put(m, "Zebak rocks",     new int[]{2176, 2177}, new int[]{2178, 2179}, new int[]{});
        clash += put(m, "Zulrah",          new int[]{1046}, new int[]{1044}, new int[]{});

        BY_ID     = Collections.unmodifiableMap(m);
        CONFLICTS = clash;
    }

    private ProjectileStyles() { }

    private static int put(Map<Integer, String[]> m, String boss,
                           int[] magic, int[] ranged, int[] melee)
    {
        int clash = 0;
        clash += putAll(m, boss, MAGIC,  magic);
        clash += putAll(m, boss, RANGED, ranged);
        clash += putAll(m, boss, MELEE,  melee);
        return clash;
    }

    private static int putAll(Map<Integer, String[]> m, String boss,
                              String style, int[] ids)
    {
        int clash = 0;
        for (int id : ids)
        {
            String[] prev = m.get(id);
            // Only a DIFFERENT style is a conflict. The same id listed twice
            // under the same style is harmless and left as it is.
            if (prev != null && !prev[0].equals(style))
            {
                clash++;
                continue;   // first writer wins, and the count says it happened
            }
            if (prev == null) m.put(id, new String[]{ style, boss });
        }
        return clash;
    }

    /**
     * MAGIC / RANGED / MELEE for an id in the table, UNKNOWN for anything
     * else - including a negative id, which is what the plugin publishes
     * when it has never seen a projectile. Package-visible and static so
     * {@code mlmCheck} calls the REAL rule rather than a second copy of it,
     * the same reason {@code portfolioGeShare} and {@code filterNameMatches}
     * are shaped this way.
     */
    static String styleOf(int projectileId)
    {
        String[] hit = BY_ID.get(projectileId);
        return hit == null ? UNKNOWN : hit[0];
    }

    /** The boss whose projectile this is, or "" when the id is unknown. */
    static String bossOf(int projectileId)
    {
        String[] hit = BY_ID.get(projectileId);
        return hit == null ? "" : hit[1];
    }

    /** Distinct ids carried. Published so a VM can say which table it has. */
    static int size()
    {
        return BY_ID.size();
    }

    /** Ids that were handed two different styles. Asserted == 0 by mlmCheck. */
    static int conflicts()
    {
        return CONFLICTS;
    }

    /**
     * The overhead prayer that answers this style, as
     * {@code client.isPrayerActive} / the Prayer enum names it - so a
     * consumer can compare it with {@code pray_overhead} directly instead of
     * mapping the style itself. "" when there is nothing to pray.
     */
    static String overheadFor(String style)
    {
        if (MAGIC.equals(style))  return "PROTECT_FROM_MAGIC";
        if (RANGED.equals(style)) return "PROTECT_FROM_MISSILES";
        if (MELEE.equals(style))  return "PROTECT_FROM_MELEE";
        return "";
    }
}

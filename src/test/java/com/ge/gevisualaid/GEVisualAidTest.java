package com.ge.gevisualaid;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GEVisualAidTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(GEVisualAidPlugin.class);
        RuneLite.main(args);
    }
}
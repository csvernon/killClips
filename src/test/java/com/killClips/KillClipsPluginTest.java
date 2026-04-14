package com.killClips;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class KillClipsPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(KillClipsPlugin.class);
        RuneLite.main(args);
    }
}

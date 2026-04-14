package com.killClips;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import com.killClips.video.VideoFps;
import com.killClips.video.VideoResolution;

@ConfigGroup("killclips")
public interface KillClipsConfig extends Config
{
    @ConfigSection(
        name = "Video Settings",
        description = "Configure local video recording",
        position = 0
    )
    String videoSection = "video";

    @ConfigSection(
        name = "Streamable",
        description = "Auto-upload clips to Streamable.com",
        position = 1
    )
    String streamableSection = "streamable";

    // -- Video settings --

    @ConfigItem(
        keyName = "videoResolution",
        name = "Resolution",
        description = "Video resolution. Lower = smaller files.",
        section = videoSection,
        position = 0
    )
    default VideoResolution videoResolution()
    {
        return VideoResolution.RES_720P;
    }

    @ConfigItem(
        keyName = "videoFps",
        name = "Frame Rate",
        description = "Frames per second. Lower = smaller files.",
        section = videoSection,
        position = 1
    )
    default VideoFps videoFps()
    {
        return VideoFps.FPS_25;
    }

    @Range(min = 10, max = 60)
    @ConfigItem(
        keyName = "recordingDuration",
        name = "Recording Duration (seconds)",
        description = "Total clip length in seconds (pre-event buffer + post-event). Min 10, max 60.",
        section = videoSection,
        position = 2
    )
    default int recordingDuration()
    {
        return 10;
    }

    // -- Streamable integration --

    @ConfigItem(
        keyName = "streamableEnabled",
        name = "Enable Streamable Upload",
        description = "Automatically upload clips to Streamable.com after saving",
        section = streamableSection,
        position = 0
    )
    default boolean streamableEnabled()
    {
        return false;
    }

    @ConfigItem(
        keyName = "streamableEmail",
        name = "Email / Username",
        description = "Your Streamable account email or username",
        section = streamableSection,
        position = 1
    )
    default String streamableEmail()
    {
        return "";
    }

    @ConfigItem(
        keyName = "streamablePassword",
        name = "Password",
        description = "Your Streamable account password",
        secret = true,
        section = streamableSection,
        position = 2
    )
    default String streamablePassword()
    {
        return "";
    }
}

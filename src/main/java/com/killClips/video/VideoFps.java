package com.killClips.video;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum VideoFps
{
    FPS_15("15 FPS", 15),
    FPS_20("20 FPS", 20),
    FPS_25("25 FPS", 25),
    FPS_30("30 FPS", 30);

    private final String displayName;
    private final int fps;

    @Override
    public String toString()
    {
        return displayName;
    }
}

package com.killClips.video;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum VideoResolution
{
    RES_480P("480p", 480, 0.45f),
    RES_720P("720p", 720, 0.55f),
    RES_1080P("1080p", 1080, 0.65f);

    private final String displayName;
    private final int maxHeight;
    private final float jpegQuality;

    @Override
    public String toString()
    {
        return displayName;
    }
}

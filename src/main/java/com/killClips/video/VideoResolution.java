package com.killClips.video;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum VideoResolution
{
    RES_480P("480p", 480, 0.35f),
    RES_720P("720p", 720, 0.45f),
    RES_1080P("1080p", 1080, 0.55f);

    private final String displayName;
    private final int maxHeight;
    private final float jpegQuality;

    @Override
    public String toString()
    {
        return displayName;
    }
}

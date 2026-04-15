package com.killClips.death;

import com.google.gson.JsonObject;
import com.killClips.KillClipsConfig;
import com.killClips.api.ApiClient;
import com.killClips.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;

// Records the local player's own deaths
@Slf4j
@Singleton
public class DeathTracker
{
    private static final int POST_DEATH_DELAY_MS = 5000;

    private final Client gameClient;
    private final ApiClient storage;
    private final KillClipsConfig cfg;
    private final VideoRecorder recorder;

    @Inject
    public DeathTracker(Client gameClient, ApiClient storage, KillClipsConfig cfg, VideoRecorder recorder)
    {
        this.gameClient = gameClient;
        this.storage = storage;
        this.cfg = cfg;
        this.recorder = recorder;
    }

    public void processActorDeath(Actor who)
    {
        Player me = gameClient.getLocalPlayer();
        if (me == null || who != me)
        {
            return;
        }

        log.debug("Local player died");
        captureAndStore();
    }

    private void captureAndStore()
    {
        recorder.captureEventVideo(
            (screenshotData, videoBytes) ->
            {
                String rsn = resolvePlayerName();

                JsonObject info = new JsonObject();
                info.addProperty("playername", rsn);
                info.addProperty("timestamp", Instant.now().toString());

                storage.sendEventToApi(
                    "/api/webhooks/death",
                    info.toString(),
                    "death",
                    videoBytes
                );
            },
            null,
            POST_DEATH_DELAY_MS
        );
    }

    private String resolvePlayerName()
    {
        Player me = gameClient.getLocalPlayer();
        if (me != null && me.getName() != null)
        {
            return me.getName();
        }
        return "unknown";
    }
}

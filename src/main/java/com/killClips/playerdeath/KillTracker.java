package com.killClips.playerdeath;

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

@Slf4j
@Singleton
public class KillTracker
{
    private static final int POST_KILL_DELAY_MS = 5000;

    private final Client gameClient;
    private final ApiClient storage;
    private final KillClipsConfig cfg;
    private final VideoRecorder recorder;
    private final CombatTracker combatTracker;

    @Inject
    public KillTracker(Client gameClient, ApiClient storage,
                                   KillClipsConfig cfg, VideoRecorder recorder,
                                   CombatTracker combatTracker)
    {
        this.gameClient = gameClient;
        this.storage = storage;
        this.cfg = cfg;
        this.recorder = recorder;
        this.combatTracker = combatTracker;
    }

    public void processActorDeath(Actor who)
    {
        Player me = gameClient.getLocalPlayer();
        if (me == null || who == me)
        {
            return;
        }

        if (!(who instanceof Player))
        {
            return;
        }

        // Only record kills for players we were actually fighting
        if (!combatTracker.isOurOpponent(who))
        {
            return;
        }

        String victimName = who.getName() != null ? who.getName() : "someone";
        log.info("Kill recorded: {}", victimName);
        captureAndStore(victimName);
    }

    private void captureAndStore(String victim)
    {
        recorder.captureEventVideo(
            (screenshotData, videoData) ->
            {
                String myName = resolvePlayerName();

                JsonObject info = new JsonObject();
                info.addProperty("playername", myName);
                info.addProperty("killed_player", victim);
                info.addProperty("timestamp", Instant.now().toString());

                storage.sendEventToApi(
                    "/api/webhooks/kills",
                    info.toString(),
                    "kill",
                    videoData
                );
            },
            null,
            POST_KILL_DELAY_MS
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

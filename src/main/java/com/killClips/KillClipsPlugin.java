package com.killClips;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.killClips.api.StreamableUploader;
import com.killClips.death.DeathTracker;
import com.killClips.playerdeath.CombatTracker;
import com.killClips.playerdeath.KillTracker;
import com.killClips.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@Slf4j
@PluginDescriptor(
    name = "Kill Clips",
    description = "Automatically captures deaths and kills to local video files with Streamable upload",
    tags = {"kill", "clips", "deaths", "video", "streamable"}
)
@PluginDependency(GpuPlugin.class)
public class KillClipsPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private KillClipsConfig config;
    @Inject private DeathTracker deathTracker;
    @Inject private KillTracker killTracker;
    @Inject private CombatTracker combatTracker;
    @Inject private VideoRecorder videoRecorder;
    @Inject private StreamableUploader streamableUploader;
    @Inject private ClientToolbar clientToolbar;
    @Inject private EventBus eventBus;
    @Inject private Gson gson;

    private NavigationButton navBtn;
    private KillClipsPanel sidePanel;
    private volatile boolean inSession = false;

    private EventBus.Subscriber gameStateSub;
    private EventBus.Subscriber interactingSub;
    private EventBus.Subscriber actorDeathSub;
    private EventBus.Subscriber gameTickSub;

    @Override
    protected void startUp() throws Exception
    {
        log.debug("Kill Clips starting");
        videoRecorder.startRecording();

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            inSession = true;
        }

        sidePanel = new KillClipsPanel(gson);
        streamableUploader.setOnClipUploaded((desc, url) -> sidePanel.addClip(desc, url));

        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D gfx = icon.createGraphics();
        gfx.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        gfx.setColor(new java.awt.Color(220, 40, 40));
        gfx.fillOval(2, 2, 12, 12);
        gfx.dispose();

        navBtn = NavigationButton.builder()
            .tooltip("Kill Clips")
            .icon(icon)
            .priority(10)
            .panel(sidePanel)
            .build();
        clientToolbar.addNavigation(navBtn);

        gameStateSub = eventBus.register(GameStateChanged.class, this::onGameStateChanged, 0);
        interactingSub = eventBus.register(InteractingChanged.class, this::onInteractingChanged, 0);
        actorDeathSub = eventBus.register(ActorDeath.class, this::onActorDeath, 0);
        gameTickSub = eventBus.register(GameTick.class, this::onGameTick, 0);
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.debug("Kill Clips stopping");

        if (gameStateSub != null) eventBus.unregister(gameStateSub);
        if (interactingSub != null) eventBus.unregister(interactingSub);
        if (actorDeathSub != null) eventBus.unregister(actorDeathSub);
        if (gameTickSub != null) eventBus.unregister(gameTickSub);
        gameStateSub = interactingSub = actorDeathSub = gameTickSub = null;

        if (navBtn != null)
        {
            clientToolbar.removeNavigation(navBtn);
        }
        streamableUploader.setOnClipUploaded(null);
        try
        {
            videoRecorder.shutdown();
        }
        catch (Exception ex)
        {
            log.warn("VideoRecorder shutdown threw", ex);
        }
        inSession = false;
    }

    // All @Subscribe handlers below are wrapped in try/catch. An uncaught exception here
    // would detach the subscriber from the event bus for the rest of the session.

    private void onGameStateChanged(GameStateChanged evt)
    {
        try
        {
            GameState gs = evt.getGameState();
            if (gs == GameState.LOGGED_IN && !inSession)
            {
                inSession = true;
            }
            else if (gs == GameState.LOGIN_SCREEN && inSession)
            {
                inSession = false;
                combatTracker.reset();
            }
            else if (gs == GameState.HOPPING && inSession)
            {
                inSession = false;
                combatTracker.reset();
            }
        }
        catch (Exception ex)
        {
            log.warn("onGameStateChanged threw", ex);
        }
    }

    private void onInteractingChanged(InteractingChanged evt)
    {
        try
        {
            combatTracker.onInteractingChanged(evt.getSource(), evt.getTarget());
        }
        catch (Exception ex)
        {
            log.warn("onInteractingChanged threw", ex);
        }
    }

    private void onActorDeath(ActorDeath evt)
    {
        try
        {
            Actor who = evt.getActor();
            if (!(who instanceof Player))
            {
                return;
            }
            deathTracker.processActorDeath(who);
            killTracker.processActorDeath(who);
        }
        catch (Exception ex)
        {
            log.warn("onActorDeath threw", ex);
        }
    }

    private void onGameTick(GameTick tick)
    {
        try
        {
            videoRecorder.updateCaptureRateIfNeeded();
            combatTracker.onGameTick();
        }
        catch (Exception ex)
        {
            log.warn("onGameTick threw", ex);
        }
    }

    @Provides
    KillClipsConfig provideConfig(ConfigManager cfgMgr)
    {
        return cfgMgr.getConfig(KillClipsConfig.class);
    }
}

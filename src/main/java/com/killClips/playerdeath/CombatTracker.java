package com.killClips.playerdeath;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Tracks the local player's current PvP opponent by monitoring interactions.
 * A player is considered "our opponent" if:
 *   1. We are targeting them, OR they are targeting us
 *   2. Both parties are Players (not NPCs)
 *
 * The opponent reference is cleared after a timeout of no interaction.
 */
@Slf4j
@Singleton
public class CombatTracker
{
    private static final int FIGHT_TIMEOUT_TICKS = 20; // ~12 seconds

    private final Client client;

    private Player opponent;
    private int lastInteractionTick;

    @Inject
    public CombatTracker(Client client)
    {
        this.client = client;
    }

    /**
     * Called when any actor's interaction target changes.
     * Updates the tracked opponent if the local player is involved.
     */
    public void onInteractingChanged(Actor source, Actor target)
    {
        if (!(source instanceof Player) || !(target instanceof Player))
        {
            return;
        }

        Player me = client.getLocalPlayer();
        if (me == null)
        {
            return;
        }

        if (source != me && target != me)
        {
            return;
        }

        Player other = (Player) (source == me ? target : source);
        if (other.getName() == null)
        {
            return;
        }

        // Update or set the opponent
        if (opponent == null || opponent != other)
        {
            opponent = other;
            log.debug("Now fighting: {}", other.getName());
        }
        lastInteractionTick = client.getTickCount();
    }

    /**
     * Called every game tick to check for fight timeout.
     */
    public void onGameTick()
    {
        if (opponent == null)
        {
            return;
        }

        int elapsed = client.getTickCount() - lastInteractionTick;
        if (elapsed > FIGHT_TIMEOUT_TICKS)
        {
            log.debug("Fight timed out vs {}", opponent.getName());
            opponent = null;
        }
    }

    /**
     * Checks if the given actor is the player we're currently fighting.
     */
    public boolean isOurOpponent(Actor actor)
    {
        return opponent != null && actor == opponent;
    }

    /**
     * Returns the current opponent's name, or null if not in a fight.
     */
    public String getOpponentName()
    {
        return opponent != null ? opponent.getName() : null;
    }

    /**
     * Clears fight state (e.g., on logout).
     */
    public void reset()
    {
        opponent = null;
    }
}

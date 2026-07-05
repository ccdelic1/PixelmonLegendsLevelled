package com.pixelmonlegendslevelled;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.api.storage.PlayerPartyStorage;
import com.pixelmonmod.pixelmon.api.storage.StorageProxy;
import com.pixelmonmod.pixelmon.entities.pixelmon.PixelmonEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Overrides the level of any wild Legendary or Ultra Beast the moment it joins the world,
 * matching it to the strongest party Pokemon of the nearest player.
 *
 * <p>This listens to the universal {@link EntityJoinLevelEvent} rather than any single
 * spawn mechanism, so it catches every spawn path Pixelmon has (natural spawns, boss
 * spawns, spawner blocks, ritual summons such as the Timespace Altar/Bell/Mystery Box,
 * commands, etc.) — anything that ends up adding the Pokemon entity to the world.
 *
 * <p><b>Thread safety:</b> {@code EntityJoinLevelEvent} can fire from chunk-generation
 * worker threads. Pixelmon's APIs are not thread-safe, so all Pokemon interaction is
 * deferred to the main server thread via {@code ServerLevel.getServer().execute()}.
 * The event handler body performs only safe {@code instanceof} checks and captures
 * the entity UUID for later re-resolution.
 */
public class SpawnLevelHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PROCESSED_TAG = "pll_level_synced";

    /** Maximum level a legendary/UB can be set to, as a safety clamp. */
    private static final int MAX_TARGET_LEVEL = 100;

    /**
     * Maximum distance (in blocks) to search for a player whose party level
     * determines the spawn level.  Using a reasonable cap prevents iterating
     * every player in the dimension on each spawn.
     */
    private static final double MAX_PLAYER_SEARCH_DISTANCE = 128.0;

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof PixelmonEntity pixelmonEntity)) {
            return;
        }

        // Capture the entity UUID while we're still on the firing thread.
        // Entity.getUUID() reads a final field and is safe from any thread.
        // All further work is deferred to the main server thread, where
        // Pixelmon's APIs are safe to call.
        UUID entityUUID = pixelmonEntity.getUUID();
        serverLevel.getServer().execute(() -> handleSpawn(serverLevel, entityUUID));
    }

    /**
     * Runs on the main server thread.  Re-resolves the entity from its UUID
     * (it may have been removed between the event firing and this task
     * executing), then performs all the Pixelmon API interaction that is
     * unsafe from worker threads.
     */
    private void handleSpawn(ServerLevel serverLevel, UUID entityUUID) {
        Entity entity = serverLevel.getEntity(entityUUID);
        if (!(entity instanceof PixelmonEntity pixelmonEntity)) {
            return;
        }

        if (!pixelmonEntity.isAlive() || pixelmonEntity.isRemoved()) {
            return;
        }

        Pokemon pokemon = pixelmonEntity.getPokemon();
        if (pokemon == null) {
            return;
        }

        if (!(pokemon.isLegendary() || pokemon.isUltraBeast())) {
            return;
        }

        // Only wild, un-owned, un-stored Pokemon — never touch a player's own
        // party or PC members.
        if (pokemon.getOwnerPlayerUUID() != null || pokemon.getStorage() != null) {
            return;
        }

        // Chunk reloads and dimension changes re-fire EntityJoinLevelEvent for
        // the same entity.  Because tag check-and-set now runs entirely on the
        // main thread (serialized by the server's execute queue), there is no
        // longer a race condition between worker threads.
        CompoundTag persistentData = pokemon.getPersistentData();
        if (persistentData.getBoolean(PROCESSED_TAG)) {
            return;
        }
        persistentData.putBoolean(PROCESSED_TAG, true);

        try {
            applyLevel(pixelmonEntity, pokemon);
        } catch (Exception e) {
            LOGGER.warn(
                "[Pixelmon Legends Levelled] Failed to apply level to entity {} (UUID: {})",
                entityUUID, e
            );
        }
    }

    private void applyLevel(PixelmonEntity pixelmonEntity, Pokemon pokemon) {
        // Use a capped search distance so we don't scan every player in the
        // dimension on each spawn.
        Player nearestPlayer = pixelmonEntity.level()
            .getNearestPlayer(pixelmonEntity, MAX_PLAYER_SEARCH_DISTANCE);
        if (nearestPlayer == null) {
            return;
        }

        int targetLevel = getHighestPartyLevel(nearestPlayer.getUUID());
        if (targetLevel <= 0) {
            return;
        }

        // Clamp: don't allow levels beyond MAX_TARGET_LEVEL even if a player
        // somehow has a higher-level party member.
        targetLevel = Math.min(targetLevel, MAX_TARGET_LEVEL);

        // Re-verify the entity is still valid before mutating it.  Enough time
        // may have passed during getNearestPlayer / getHighestPartyLevel for
        // the entity to be despawned, killed, or unloaded.
        if (!pixelmonEntity.isAlive() || pixelmonEntity.isRemoved()) {
            return;
        }

        pokemon.setLevel(targetLevel);

        // Pokemon.setLevel() updates the Pokemon's own stats/level fields, but
        // the client-visible nameplate is driven by a separate synced entity-data
        // value (Pokemon.SYNC_LEVEL) that is only pushed to the entity via
        // resetDataWatchers().  Without this, the nameplate keeps showing the
        // entity's original spawn level until something else (e.g. a battle
        // starting) forces a resync.
        pixelmonEntity.resetDataWatchers();
    }

    private int getHighestPartyLevel(UUID playerUUID) {
        PlayerPartyStorage party = StorageProxy.getPartyNow(playerUUID);
        if (party == null) {
            return 0;
        }

        int highest = 0;
        for (Pokemon partyMember : party.getAll()) {
            if (partyMember != null && partyMember.getPokemonLevel() > highest) {
                highest = partyMember.getPokemonLevel();
            }
        }
        return highest;
    }
}

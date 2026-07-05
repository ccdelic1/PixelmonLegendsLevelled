package com.pixelmonlegendslevelled;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Entry point for Pixelmon Legends Levelled.
 *
 * <p>Legendaries and Ultra Beasts always spawn at the level of the nearest player's
 * (or triggering player's) strongest party Pokemon, no matter how they were spawned.
 */
@Mod("pixelmonlegendslevelled")
public class PixelmonLegendsLevelledMod {
    private static final Logger LOGGER = LogManager.getLogger();

    public PixelmonLegendsLevelledMod(IEventBus modEventBus, ModContainer container) {
        NeoForge.EVENT_BUS.register(new SpawnLevelHandler());
        LOGGER.info("[Pixelmon Legends Levelled] Spawn level handler registered");
    }
}

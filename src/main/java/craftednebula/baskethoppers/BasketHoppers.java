package craftednebula.baskethoppers;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import turniplabs.halplibe.HalpLibe;
import turniplabs.halplibe.event.defs.CommonEvents;
import turniplabs.halplibe.util.dependency.Key;

public class BasketHoppers implements ModInitializer {
	public static final String MOD_ID = HalpLibe.registerMod("baskethoppers", true);
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommonEvents.BEFORE_GAME_START.listen(Key.of(MOD_ID), this::beforeGameStart);
		CommonEvents.AFTER_GAME_START.listen(Key.of(MOD_ID), this::afterGameStart);
		LOGGER.info("[BasketHoppers] Basket hopper automation initialized!");
	}

	public void beforeGameStart() {
		// Run any pre-game setup or config loading here if needed in the future
	}

	public void afterGameStart() {
		// Run post-game startup logic here
	}
}

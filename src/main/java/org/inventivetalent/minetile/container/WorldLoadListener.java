package org.inventivetalent.minetile.container;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.inventivetalent.minetile.TileData;

public class WorldLoadListener implements Listener {

	private ContainerPlugin plugin;

	public WorldLoadListener(ContainerPlugin plugin) {
		this.plugin = plugin;
	}


	@EventHandler
	public void on(WorldLoadEvent event) {
		if (plugin.worldLoaded) { return; }
		plugin.worldLoaded = true;

		plugin.defaultWorld = Bukkit.getWorlds().get(0);
		plugin.getLogger().info("Using " + plugin.defaultWorld.getName() + " as default world");

		int centerX = plugin.config.getInt("center.x", 0);
		int centerZ = plugin.config.getInt("center.z", 0);

		/// Tile Data
		int tileX = plugin.config.getInt("tile.x", 0);
		int tileZ = plugin.config.getInt("tile.z", 0);

		plugin.getLogger().info("================================================");
		plugin.getLogger().info("= ");
		plugin.getLogger().info("=  MineTile Container @ " + tileX + "  " + tileZ);
		plugin.getLogger().info("= ");
		plugin.getLogger().info("================================================");

		plugin.tileData = new TileData(tileX, tileZ, 0);
		plugin.worldCenter = new Location(plugin.defaultWorld, centerX, 0, centerZ);

		WorldBorder worldBorder = plugin.defaultWorld.getWorldBorder();
		worldBorder.setCenter(plugin.worldCenter);
		worldBorder.setSize(plugin.tileSize * 2 * 16 + 32 * 16);// Set world border to tile size + 16 chunks padding since the surrounding chunks are also included

		plugin.teleportTopic = plugin.redisson.getTopic("MineTile:Teleports");
		plugin.customTeleportSet = plugin.redisson.getSet("MineTile:CustomTeleports");


		plugin.discoverServer();
	}

}

package org.inventivetalent.minetile.container;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.inventivetalent.minetile.CoordinateConverter;
import org.inventivetalent.minetile.TeleportRequest;
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
		plugin.worldCenter = new Location(plugin.defaultWorld, plugin.tileSizeBlocks + (plugin.tileSizeBlocks * 2 * (plugin.localIsGlobal ? tileX : 1)) - plugin.offsetX, 0, plugin.tileSizeBlocks + (plugin.tileSizeBlocks * 2 * (plugin.localIsGlobal ? tileZ : 1)));

		plugin.getLogger().info("Tile Center is at " + plugin.worldCenter.getX() + "  " + plugin.worldCenter.getZ());
		plugin.getLogger().info("Calculated tile location is " + CoordinateConverter.tile(plugin.worldCenter.getX(), plugin.tileSize, plugin.offsetX) + "  " + CoordinateConverter.tile(plugin.worldCenter.getZ(), plugin.tileSize, plugin.offsetZ));

		WorldBorder worldBorder = plugin.defaultWorld.getWorldBorder();
		worldBorder.setCenter(plugin.worldCenter);
		worldBorder.setSize(plugin.tileSize * 2 * 16 + 32 * 16);// Set world border to tile size + 16 chunks padding since the surrounding chunks are also included

		plugin.teleportTopic = plugin.redisson.getTopic("MineTile:Teleports");
		plugin.customTeleportSet = plugin.redisson.getSet("MineTile:CustomTeleports");

		plugin.teleportTopic.addListener(TeleportRequest.class, (channel, teleportRequest) -> {
			if (teleportRequest.x == plugin.tileData.x && teleportRequest.z == plugin.tileData.z) {
				Player player = Bukkit.getPlayer(teleportRequest.player);
				if (player != null && player.isOnline()) {
					plugin.playerListener.doJoinTeleport(player, false, false);
				}
			}
		});

		plugin.discoverServer();
	}

}

package org.inventivetalent.minetile.container;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.inventivetalent.minetile.PlayerData;
import org.inventivetalent.minetile.PlayerLocation;
import org.inventivetalent.minetile.TeleportRequest;
import org.redisson.api.RTopic;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

import static org.inventivetalent.minetile.CoordinateConverter.globalToLocal;
import static org.inventivetalent.minetile.CoordinateConverter.localToGlobal;

public class PlayerListener implements Listener {

	private ContainerPlugin plugin;

	//	RMap<UUID, PlayerLocation> positionMap;
	//	RMap<UUID, PlayerData>     playerDataMap;
	RTopic teleportTopic;
	//	RSet<CustomTeleport>       customTeleportSet;

	public PlayerListener(ContainerPlugin plugin) {
		this.plugin = plugin;

		//		positionMap = plugin.redisson.getMap("MineTile:Positions");
		//		playerDataMap = plugin.redisson.getMap("MineTile:PlayerData");
		teleportTopic = plugin.redisson.getTopic("MineTile:Teleports");
		//		customTeleportSet = plugin.redisson.getSet("MineTile:CustomTeleports");
	}

	@EventHandler
	public void on(PlayerJoinEvent event) {
		plugin.teleportTimeout.put(event.getPlayer().getUniqueId(), ContainerPlugin.TELEPORT_TIMEOUT);

		plugin.getSQL().execute(() -> {
			try {
				PreparedStatement stmt = plugin.getSQL().stmt("SELECT * FROM `" + plugin.getSQL().prefix + "positions` WHERE `uuid`=?;");
				stmt.setString(1, event.getPlayer().getUniqueId().toString());
				ResultSet resultSet = stmt.executeQuery();
				if (resultSet.next()) {
					PlayerLocation position = PlayerLocation.fromSQL(resultSet);
					double localX = globalToLocal(position.x, plugin.tileData.x, plugin.tileSize, plugin.worldCenter.getX());
					double localZ = globalToLocal(position.z, plugin.tileData.z, plugin.tileSize, plugin.worldCenter.getZ());

					double y = 100;
					if (position.y == -1) {
						y = plugin.defaultWorld.getHighestBlockYAt((int) localX, (int) localZ) + 2;
					} else if (position.y > 0) {
						y = position.y;
					}

					Location loc = new Location(plugin.defaultWorld, localX, y, localZ, position.yaw, position.pitch);
					System.out.println(loc);

					Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().teleport(loc));
				} else {
					Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().teleport(new Location(plugin.defaultWorld, plugin.worldCenter.getBlockX(), plugin.defaultWorld.getHighestBlockYAt(plugin.worldCenter.getBlockX(), plugin.worldCenter.getBlockZ()) + 2, plugin.worldCenter.getBlockZ())));
				}

				stmt = plugin.getSQL().stmt("SELECT * FROM `" + plugin.getSQL().prefix + "player_data` WHERE `uuid`=?;");
				stmt.setString(1, event.getPlayer().getUniqueId().toString());
				resultSet = stmt.executeQuery();
				if (resultSet.next()) {
					PlayerData playerData = PlayerData.fromSQL(resultSet);
					Bukkit.getScheduler().runTask(plugin, () -> restorePlayerData(event.getPlayer(), playerData));
				}
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		});
	}

	public Location convertLocation(World world, PlayerLocation position) {
		return new Location(world, position.x, position.y, position.z, position.yaw, position.pitch);
	}

	public PlayerLocation convertLocation(Location location) {
		return new PlayerLocation(location.getX(), location.getY(), location.getZ(), location.getPitch(), location.getYaw());
	}

	@EventHandler(priority = EventPriority.MONITOR,
				  ignoreCancelled = true)
	public void on(PlayerMoveEvent event) {
		Location playerLocation = event.getPlayer().getLocation();

		if (!plugin.worldCenter.getWorld().getUID().equals(playerLocation.getWorld().getUID())) {
			return;
		}

		Location locationDiff = plugin.worldCenter.clone().subtract(playerLocation);

		boolean leaving = false;
		boolean outside = false;

		if (locationDiff.getX() > plugin.tileSizeBlocks) {
			System.out.println(event.getPlayer().getName() + " leaving in +X direction");
			leaving = true;
			if (locationDiff.getX() > (plugin.tileSizeBlocks + 32)) {
				outside = true;
			}
		} else if (locationDiff.getX() < -plugin.tileSizeBlocks) {
			System.out.println(event.getPlayer().getName() + " leaving in -X direction");
			leaving = true;
			if (locationDiff.getX() < -(plugin.tileSizeBlocks + 32)) {
				outside = true;
			}
		}

		if (locationDiff.getZ() > plugin.tileSizeBlocks) {
			System.out.println(event.getPlayer().getName() + " leaving in +Z direction");
			leaving = true;
			if (locationDiff.getZ() > (plugin.tileSizeBlocks + 32)) {
				outside = true;
			}
		} else if (locationDiff.getZ() < -plugin.tileSizeBlocks) {
			System.out.println(event.getPlayer().getName() + " leaving in -Z direction");
			leaving = true;
			if (locationDiff.getZ() < -(plugin.tileSizeBlocks + 32)) {
				outside = true;
			}
		}

		// TODO: maybe support Y-Direction at some point

		boolean finalOutside = outside;
		boolean finalLeaving = leaving;

		if (finalOutside && plugin.timeoutCounter % 10 == 0) {
			event.getPlayer().sendMessage("§cUh oh! Looks like you're somehow outside of the playable area! Attempting to get you back...");
		}

		if (finalLeaving && plugin.timeoutCounter % 2 == 0) {
			double globalX = localToGlobal(event.getTo().getX(), plugin.tileData.x, plugin.tileSize, plugin.worldCenter.getX());
			double globalZ = localToGlobal(event.getTo().getZ(), plugin.tileData.z, plugin.tileSize, plugin.worldCenter.getZ());
			showWall(event.getPlayer(), (int) globalX, (int) globalZ);

			///TODO
			//			if (customTeleports.size() > 0) {
			//				for (CustomTeleport tp : customTeleportSet) {
			//					if (tp.applies((int) globalX, (int) globalZ)) {
			//						if (tp.action.hasX) { globalX = tp.action.coordinateX; }
			//						if (tp.action.hasZ) { globalZ = tp.action.coordinateZ; }
			//					}
			//				}
			//			}

			plugin.getSQL().execute(() -> {
				plugin.getSQL().updatePosition(event.getPlayer().getUniqueId(), globalX, event.getTo().getY(), globalZ, event.getTo().getPitch(), event.getTo().getYaw());

				PlayerData playerData = storePlayerData(event.getPlayer());
				plugin.getSQL().updatePlayerData(event.getPlayer().getUniqueId(), playerData);
			});

			if (plugin.teleportTimeout.containsKey(event.getPlayer().getUniqueId())) { return; }
			plugin.teleportTimeout.put(event.getPlayer().getUniqueId(), ContainerPlugin.TELEPORT_TIMEOUT);

			teleportTopic.publishAsync(new TeleportRequest(event.getPlayer().getUniqueId(), plugin.serverData.serverId, globalX / 16, event.getTo().getY() / 16, globalZ / 16));
		} else if (!finalLeaving) {
			plugin.teleportTimeout.remove(event.getPlayer().getUniqueId());
		}
	}

	public void showWall(Player player, int globalX, int globalZ) {
		//		System.out.println(tileSizeBlocks + 16);

		final BlockData glassBlockData = Bukkit.getServer().createBlockData(Material.RED_STAINED_GLASS);
		final BlockData barrierBlockData = Bukkit.getServer().createBlockData(Material.BARRIER);
		for (int y = -8; y < 8; y++) {
			int finalY = y;
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				for (int x = -12; x < 12; x++) {
					for (int z = -12; z < 12; z++) {
						int aX = x + player.getLocation().getBlockX();
						int aZ = z + player.getLocation().getBlockZ();
						int aY = finalY + player.getLocation().getBlockY();

						int xDiff = aX - plugin.worldCenter.getBlockX();
						int zDiff = aZ - plugin.worldCenter.getBlockZ();

						int threshold1 = plugin.tileSizeBlocks + 14;
						if ((xDiff == -threshold1 ||
								xDiff == threshold1 ||
								zDiff == -threshold1 ||
								zDiff == threshold1) ||
								(x == plugin.worldEdge.east - 2 || x == plugin.worldEdge.west - 2 ||
										x == plugin.worldEdge.north - 2 || x == plugin.worldEdge.south + 2)) {
							Location location = new Location(player.getWorld(), aX, aY, aZ);
							if (!player.getWorld().getBlockAt(location).getType().isSolid()) {
								player.sendBlockChange(location, glassBlockData);
							}
						}
					}
				}
			}, Math.abs(y));
		}
	}

	public PlayerData storePlayerData(Player player) {

		PlayerData playerData = new PlayerData();
		playerData.allowFlight = player.getAllowFlight();
		playerData.flying = player.isFlying();
		playerData.flySpeed = player.getFlySpeed();
		playerData.walkSpeed = player.getWalkSpeed();
		playerData.exhaustion = player.getExhaustion();
		playerData.exp = player.getExp();
		playerData.level = player.getLevel();
		playerData.totalExperience = player.getTotalExperience();
		playerData.saturation = player.getSaturation();
		playerData.foodLevel = player.getFoodLevel();
		playerData.gameMode = player.getGameMode().ordinal();
		playerData.sprinting = player.isSprinting();
		playerData.sneaking = player.isSneaking();

		ItemStack[] items = player.getInventory().getContents();
		try {
			playerData.inventory = itemsToBase64(items);
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING, "Failed to convert player inventory to Base64", e);
		}

		playerData.remainingAir = player.getRemainingAir();
		playerData.maximumAir = player.getMaximumAir();
		playerData.gliding = player.isGliding();
		playerData.swimming = player.isSwimming();

		playerData.health = player.getHealth();

		playerData.fallDistance = player.getFallDistance();
		playerData.glowing = player.isGlowing();
		playerData.invulnerable = player.isInvulnerable();

		return playerData;
	}

	public void restorePlayerData(Player player, PlayerData playerData) {

		player.setAllowFlight(playerData.allowFlight);
		player.setFlying(playerData.flying);
		player.setFlySpeed(playerData.flySpeed);
		player.setWalkSpeed(playerData.walkSpeed);
		player.setExhaustion(playerData.exhaustion);
		player.setExp(playerData.exp);
		player.setLevel(playerData.level);
		player.setTotalExperience(playerData.totalExperience);
		player.setSaturation(playerData.saturation);
		player.setFoodLevel(playerData.foodLevel);
		player.setGameMode(GameMode.values()[playerData.gameMode]);
		player.setSprinting(playerData.sprinting);
		player.setSneaking(playerData.sneaking);

		try {
			ItemStack[] items = itemsFromBase64(playerData.inventory);
			player.getInventory().setContents(items);
		} catch (IOException | ClassNotFoundException e) {
			plugin.getLogger().log(Level.WARNING, "Failed to load player items from Base64", e);
		}

		player.setRemainingAir(playerData.remainingAir);
		player.setMaximumAir(playerData.maximumAir);
		player.setGliding(playerData.gliding);
		player.setSwimming(playerData.swimming);

		player.setHealth(playerData.health);

		player.setFallDistance(playerData.fallDistance);
		player.setGlowing(playerData.glowing);
		player.setInvulnerable(playerData.invulnerable);
	}

	public String itemsToBase64(ItemStack[] itemStacks) throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

		dataOutput.writeInt(itemStacks.length);
		for (ItemStack stack : itemStacks) {
			dataOutput.writeObject(stack);
		}
		String string = new String(Base64Coder.encode(outputStream.toByteArray()));
		dataOutput.close();
		return string;
	}

	public ItemStack[] itemsFromBase64(String base64) throws IOException, ClassNotFoundException {
		ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decode(base64));
		BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
		ItemStack[] stacks = new ItemStack[dataInput.readInt()];

		for (int i = 0; i < stacks.length; i++) {
			stacks[i] = (ItemStack) dataInput.readObject();
		}
		dataInput.close();

		return stacks;
	}

}

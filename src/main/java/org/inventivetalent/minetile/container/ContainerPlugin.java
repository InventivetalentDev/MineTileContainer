package org.inventivetalent.minetile.container;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.inventivetalent.minetile.*;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class ContainerPlugin extends JavaPlugin implements Listener, PluginMessageListener {

	static int TELEPORT_TIMEOUT = 80;

	FileConfiguration config;

	public ServerData serverData;
	public TileData   tileData;
	public Location   worldCenter;

	Redisson redisson;

	public int     tileSize       = 16;
	public int     tileSizeBlocks = 256;
	public boolean syncPlayerData = true;

	public boolean disablePlace    = true;
	public boolean disableBreak    = true;
	public boolean disableFade     = true;
	public boolean disableIgnite   = true;
	public boolean disableBurn     = true;
	public boolean disableGrow     = true;
	public boolean disableDecay    = true;
	public boolean disableSponge   = true;
	public boolean disablePiston   = true;
	public boolean disableRedstone = true;

	World              defaultWorld;
	Map<UUID, Integer> teleportTimeout = new HashMap<>();
	int                timeoutCounter  = 0;

	RMap<String, Object>       settingsMap;
	RMap<UUID, PlayerLocation> positionMap;
	RMap<UUID, PlayerData>     playerDataMap;
	RTopic                     teleportTopic;

	@Override
	public void onEnable() {
		Bukkit.getPluginManager().registerEvents(this, this);
		Bukkit.getPluginManager().registerEvents(new ProtectionListener(this), this);

		getServer().getMessenger().registerOutgoingPluginChannel(this, "minetile:minetile");
		getServer().getMessenger().registerIncomingPluginChannel(this, "minetile:minetile", this);

		saveDefaultConfig();
		config = getConfig();

		/// Server ID
		String serverIdString = config.getString("serverId");
		UUID serverId;
		if (serverIdString == null || serverIdString.length() == 0) {
			serverId = UUID.randomUUID();
			config.set("serverId", serverId.toString());
			getLogger().info("Assigned new Server UUID: " + serverId);
			saveConfig();
		} else {
			serverId = UUID.fromString(serverIdString);
			getLogger().info("Server UUID is " + serverId);
		}
		String serverHost = config.getString("server.host", "127.0.0.1");
		serverData = new ServerData(serverId, serverHost, getServer().getPort());

		/// Redis
		Config redisConfig = new Config();
		String address = config.getString("redis.host") + ":" + config.getInt("redis.port");
		SingleServerConfig singleServerConfig = redisConfig
				.setCodec(new RedissonGsonCodec())
				.useSingleServer()
				.setAddress("redis://" + address);
		if (config.getString("redis.password", "").length() > 0) {
			singleServerConfig.setPassword(config.getString("redis.password"));
		} else {
			getLogger().warning("No password set for redis");
		}

		redisson = (Redisson) Redisson.create(redisConfig);
		getLogger().info("Connected to Redis @ " + address);

		settingsMap = redisson.getMap("MineTile:Settings");
		getLogger().info("Got settings from Redis");
		settingsMap.forEach((key, value) -> getLogger().info(key + ": " + value));

		try {
			tileSize = (int) settingsMap.getOrDefault("tileSize", 16);
			tileSizeBlocks = tileSize * 16;
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Failed to get tileSize setting from redis", e);
		}
		try {
			syncPlayerData = (boolean) settingsMap.getOrDefault("syncPlayerData", true);
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Failed to get syncPlayerData setting from redis", e);
		}

		disableBreak = config.getBoolean("protection.blocks.break", true);
		disablePlace = config.getBoolean("protection.blocks.place", true);
		disableFade = config.getBoolean("protection.blocks.fade", true);
		disableIgnite = config.getBoolean("protection.blocks.ignite", true);
		disableBurn = config.getBoolean("protection.blocks.burn", true);
		disableGrow = config.getBoolean("protection.blocks.grow", true);
		disableDecay = config.getBoolean("protection.blocks.decay", true);
		disableSponge = config.getBoolean("protection.blocks.sponge", true);
		disablePiston = config.getBoolean("protection.blocks.piston", true);
		disableRedstone = config.getBoolean("protection.blocks.redstone", true);

		RTopic controlTopic = redisson.getTopic("MineTile:ServerControl");
		controlTopic.addListener(ControlRequest.class, (channel, controlRequest) -> {
			getLogger().info("Received Control Request: " + controlRequest.action);
			if (controlRequest.action == ControlAction.RESTART) {
				getServer().spigot().restart();
			} else if (controlRequest.action == ControlAction.SHUTDOWN) {
				getServer().shutdown();
			} else if (controlRequest.action == ControlAction.REDISCOVER) {
				discoverServer();
			}
		});

		Bukkit.getScheduler().runTaskTimer(this, () -> {
			Iterator<Map.Entry<UUID, Integer>> iterator = teleportTimeout.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<UUID, Integer> entry = iterator.next();
				if (entry.getValue() <= 0) {
					iterator.remove();
				} else {
					entry.setValue(entry.getValue() - 1);
				}
			}
			timeoutCounter++;
		}, 1, 1);

	}

	@Override
	public void onDisable() {
		RMap<UUID, TileData> tileMap = redisson.getMap("MineTile:Tiles");
		tileMap.remove(serverData.serverId);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (sender instanceof Player) {
			Player player = (Player) sender;
			if (command.getName().equalsIgnoreCase("locationinfo")) {
				if (sender.hasPermission("minetile.locationinfo")) {
					if (args.length > 0 && "target".equalsIgnoreCase(args[0])) {
						Set<Material> transparent = new HashSet<>();
						transparent.add(Material.AIR);
						transparent.add(Material.CAVE_AIR);
						transparent.add(Material.VOID_AIR);
						transparent.add(Material.GLASS);

						sender.sendMessage("(For Target Block)");
						printLocationInfo(player, player.getTargetBlock(transparent, 128).getLocation());
					} else {
						printLocationInfo(player, player.getLocation());
					}

					return true;
				}
			}

			if (command.getName().equalsIgnoreCase("pauseteleport")) {
				if (sender.hasPermission("minetile.pauseteleport")) {
					teleportTimeout.put(player.getUniqueId(), TELEPORT_TIMEOUT);
					sender.sendMessage("Teleport paused for " + TELEPORT_TIMEOUT + "ticks / " + (TELEPORT_TIMEOUT / 20) + "s");
					return true;
				}
			}

			if (command.getName().equalsIgnoreCase("globalteleport")) {
				if (sender.hasPermission("minetile.globalteleport")) {
					UUID uuid = player.getUniqueId();
					if (args.length != 3) {
						return false;
					}
					double x = Double.parseDouble(args[0]);
					double y = Double.parseDouble(args[1]);
					double z = Double.parseDouble(args[2]);
					positionMap.put(uuid, new PlayerLocation(x, y, z, 0, 0));
					teleportTopic.publish(new TeleportRequest(uuid, serverData.serverId, (int) x / 16, (int) y / 16, (int) z / 16));

					sender.sendMessage("Teleport queued");
				}
			}
		}
		return true;
	}

	public void printLocationInfo(Player sender, Location location) {
		sender.sendMessage("Tile:  " + tileData.x + "," + tileData.z);
		sender.sendMessage(" ");
		sender.sendMessage("Local Coordinates:  " + location.getX() + "," + location.getZ());
		sender.sendMessage("Global Coordinates: " + (location.getX() - worldCenter.getX() + (tileData.x * tileSize * 2 * 16)) + "," + (location.getZ() - worldCenter.getZ() + (tileData.z * tileSize * 2 * 16)));
		sender.sendMessage(" ");
		sender.sendMessage("Current Chunk:  " + location.getChunk().getX() + "," + location.getChunk().getZ());
		sender.sendMessage("Chunk File:     r" + (location.getChunk().getX() >> 5) + "." + (location.getChunk().getZ() >> 5) + ".mca");
	}

	boolean worldLoaded = false;

	@EventHandler
	public void on(WorldLoadEvent event) {
		if (worldLoaded) { return; }
		worldLoaded = true;

		defaultWorld = Bukkit.getWorlds().get(0);
		getLogger().info("Using " + defaultWorld.getName() + " as default world");

		int centerX = config.getInt("center.x", 0);
		int centerZ = config.getInt("center.z", 0);

		/// Tile Data
		int tileX = config.getInt("tile.x", 0);
		int tileZ = config.getInt("tile.z", 0);

		getLogger().info("================================================");
		getLogger().info("= ");
		getLogger().info("=  MineTile Container @ " + tileX + "  " + tileZ);
		getLogger().info("= ");
		getLogger().info("================================================");

		tileData = new TileData(tileX, tileZ, 0);
		worldCenter = new Location(defaultWorld, centerX, 0, centerZ);

		WorldBorder worldBorder = defaultWorld.getWorldBorder();
		worldBorder.setCenter(worldCenter);
		worldBorder.setSize(tileSize * 2 * 16 + 32 * 16);// Set world border to tile size + 16 chunks padding since the surrounding chunks are also included

		RMap<UUID, TileData> tileMap = redisson.getMap("MineTile:Tiles");
		tileMap.put(serverData.serverId, tileData);

		positionMap = redisson.getMap("MineTile:Positions");
		playerDataMap = redisson.getMap("MineTile:PlayerData");
		teleportTopic = redisson.getTopic("MineTile:Teleports");

		discoverServer();
	}

	public void discoverServer() {
		if (!worldLoaded) { return; }

		RTopic serverTopic = redisson.getTopic("MineTile:ServerDiscovery");
		System.out.println(serverData);
		serverTopic.publish(serverData);
	}

	@EventHandler
	public void on(PlayerJoinEvent event) {
		teleportTimeout.put(event.getPlayer().getUniqueId(), TELEPORT_TIMEOUT);

		Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
			PlayerLocation position = positionMap.get(event.getPlayer().getUniqueId());
			System.out.println("TP To " + position);
			if (position != null) {
				// subtract here as well, so we're spitting out the player on the "opposite" side instead of the same location shifted over
				double localX = position.x - (tileData.x * tileSize * 2 * 16) + worldCenter.getX();
				double localZ = position.z - (tileData.z * tileSize * 2 * 16) + worldCenter.getZ();
				Location loc = new Location(event.getPlayer().getWorld(), localX, position.y, localZ, position.yaw, position.pitch);
				System.out.println(loc);

				Bukkit.getScheduler().runTask(ContainerPlugin.this, () -> {
					event.getPlayer().teleport(loc);
				});
			}

			PlayerData data = playerDataMap.get(event.getPlayer().getUniqueId());
			if (data != null) {
				Bukkit.getScheduler().runTask(ContainerPlugin.this, () -> {
					restorePlayerData(event.getPlayer(), data);
				});
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

		if (!worldCenter.getWorld().getUID().equals(playerLocation.getWorld().getUID())) {
			return;
		}

		Location locationDiff = worldCenter.clone().subtract(playerLocation);

		boolean leaving = false;
		boolean outside = false;

		if (locationDiff.getX() > tileSizeBlocks) {
			System.out.println(event.getPlayer().getName() + " leaving in +X direction");
			leaving = true;
			if (locationDiff.getX() > (tileSizeBlocks + 32)) {
				outside = true;
			}
		} else if (locationDiff.getX() < -tileSizeBlocks) {
			System.out.println(event.getPlayer().getName() + " leaving in -X direction");
			leaving = true;
			if (locationDiff.getX() < -(tileSizeBlocks + 32)) {
				outside = true;
			}
		}

		if (locationDiff.getZ() > tileSizeBlocks) {
			System.out.println(event.getPlayer().getName() + " leaving in +Z direction");
			leaving = true;
			if (locationDiff.getZ() > tileSizeBlocks + 32) {
				outside = true;
			}
		} else if (locationDiff.getZ() < -tileSizeBlocks) {
			System.out.println(event.getPlayer().getName() + " leaving in -Z direction");
			leaving = true;
			if (locationDiff.getZ() < -tileSizeBlocks + 32) {
				outside = true;
			}
		}

		double globalX = event.getPlayer().getLocation().getX() - worldCenter.getX() + (tileData.x * tileSize * 2 * 16);
		double globalZ = event.getPlayer().getLocation().getZ() - worldCenter.getZ() + (tileData.z * tileSize * 2 * 16);

		// TODO: maybe support Y-Direction at some point

		if (outside && timeoutCounter % 10 == 0) {
			event.getPlayer().sendMessage("§cUh oh! Looks like you're somehow outside of the playable area! Attempting to get you back...");
		}

		if (leaving && timeoutCounter % 2 == 0) {
			showWall(event.getPlayer());
			Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
				positionMap.put(event.getPlayer().getUniqueId(), new PlayerLocation(globalX, event.getTo().getY(), globalZ, event.getTo().getPitch(), event.getTo().getYaw()));
			});

			//			System.out.println(teleportTimeout);

			if (teleportTimeout.containsKey(event.getPlayer().getUniqueId())) { return; }
			teleportTimeout.put(event.getPlayer().getUniqueId(), TELEPORT_TIMEOUT);

			Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
				teleportTopic.publish(new TeleportRequest(event.getPlayer().getUniqueId(), serverData.serverId, (int) globalX / 16, (int) event.getTo().getY() / 16, (int) globalZ / 16));

				double gX = event.getPlayer().getLocation().getX() - worldCenter.getX() + (tileData.x * tileSize * 2 * 16);
				double gZ = event.getPlayer().getLocation().getZ() - worldCenter.getZ() + (tileData.z * tileSize * 2 * 16);
				positionMap.put(event.getPlayer().getUniqueId(), new PlayerLocation(gX, event.getPlayer().getLocation().getY(), gZ, event.getPlayer().getLocation().getPitch(), event.getPlayer().getLocation().getYaw()));

				PlayerData playerData = storePlayerData(event.getPlayer());
				playerDataMap.put(event.getPlayer().getUniqueId(), playerData);
			});
		} else if (!leaving) {
			teleportTimeout.remove(event.getPlayer().getUniqueId());
		}
	}

	public void showWall(Player player) {
		//		System.out.println(tileSizeBlocks + 16);

		BlockData glassBlockData = getServer().createBlockData(Material.RED_STAINED_GLASS);
		for (int x = -8; x < 8; x++) {
			for (int z = -8; z < 8; z++) {
				for (int y = -8; y < 8; y++) {
					int aX = x + player.getLocation().getBlockX();
					int aZ = z + player.getLocation().getBlockZ();
					int aY = y + player.getLocation().getBlockY();

					if (aX - worldCenter.getBlockX() == -(tileSizeBlocks + 14) || aX - worldCenter.getBlockX() == (tileSizeBlocks + 14) || aZ - worldCenter.getBlockZ() == -(tileSizeBlocks + 14) || aZ - worldCenter.getBlockZ() == (tileSizeBlocks + 14)) {
						Location location = new Location(player.getWorld(), aX, aY, aZ);
						if (player.getWorld().getBlockAt(location).getType() == Material.AIR) {
							player.sendBlockChange(location, glassBlockData);
						}
					}
				}
			}
		}
	}

	public PlayerData storePlayerData(Player player) {
		//		//TODO: reflectify
		//		CompoundTag compoundTag = new CompoundTag();
		//		try {
		//			Object nmsCompoundTag = compoundTag.toNMS();
		//			((CraftPlayer)player).getHandle().b((NBTTagCompound) nmsCompoundTag);
		//			compoundTag = compoundTag.fromNMS(nmsCompoundTag);
		//		} catch (ReflectiveOperationException e) {
		//			e.printStackTrace();
		//		}

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

		ItemStack[] items = player.getInventory().getContents();
		try {
			playerData.inventory = itemsToBase64(items);
		} catch (IOException e) {
			getLogger().log(Level.WARNING, "Failed to convert player inventory to Base64", e);
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
		//		//TODO: reflectify
		//		CompoundTag compoundTag = playerData.nbt;
		//		try {
		//			Object nmsCompoundTag = compoundTag.toNMS();
		//			((CraftPlayer)player).getHandle().a((NBTTagCompound) nmsCompoundTag);
		//		} catch (ReflectiveOperationException e) {
		//			e.printStackTrace();
		//		}

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

		try {
			ItemStack[] items = itemsFromBase64(playerData.inventory);
			player.getInventory().setContents(items);
		} catch (IOException | ClassNotFoundException e) {
			getLogger().log(Level.WARNING, "Failed to load player items from Base64", e);
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

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
		if (!"MineTile".equals(channel)) { return; }
		ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
		String subChannel = in.readUTF();
		byte hasData = in.readByte();
		ByteArraySerializable<?> data = null;
		if (hasData > 0) {
			String dataClass = in.readUTF();
			try {
				data = (ByteArraySerializable<?>) Class.forName(dataClass).newInstance();
			} catch (ReflectiveOperationException e) {
				getLogger().log(Level.SEVERE, "Failed to instantiate data class " + dataClass, e);
				return;
			}
			data.readFromByteArray(in);
		}

		handleBungeecordData(subChannel, player, data);
	}

	public void handleBungeecordData(String subChannel, Player receiver, ByteArraySerializable data) {
		if ("TileDataRequest".equals(subChannel)) {
			sendToBungeecord("TileData", this.tileData);
		}
	}

	public void sendToBungeecord(String subChannel, ByteArraySerializable data) {
		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF(subChannel);
		out.writeByte(data != null ? 1 : 0);
		if (data != null) {
			out.writeUTF(data.getClass().getName());
			data.writeToByteArray(out);
		}

		Player player = Bukkit.getOnlinePlayers().iterator().next();
		if (player == null) {
			getLogger().warning("No online players to send plugin message (" + subChannel + ")");
			return;
		}
		player.sendPluginMessage(this, "MineTile", out.toByteArray());
	}

	@Override
	public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
		//TODO: get this working
		getLogger().info("Returning EmptyWorldGenerator for " + worldName + "/" + id);
		return new EmptyWorldGenerator();
	}

}

package org.inventivetalent.minetile.container;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.inventivetalent.minetile.*;
import org.redisson.Redisson;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.*;

import static org.inventivetalent.minetile.CoordinateConverter.globalToLocal;
import static org.inventivetalent.minetile.CoordinateConverter.localToGlobal;

public class ContainerPlugin extends JavaPlugin implements MineTilePlugin, Listener, PluginMessageListener {

	static int TELEPORT_TIMEOUT = 80;

	FileConfiguration config;

	public ServerData serverData;
	public TileData   tileData;
	public Location   worldCenter;

	Redisson redisson;
	MySQL    sql;

	public int     tileSize       = 16;
	public int     tileSizeBlocks = 256;
	public boolean syncPlayerData = true;

	public boolean disablePlace  = true;
	public boolean disableBreak  = true;
	public boolean disableFade   = true;
	public boolean disableIgnite = true;
	public boolean disableBurn   = true;
	public boolean disableGrow   = true;
	public boolean disableDecay  = true;
	public boolean disableSponge = true;
	public boolean disablePiston = true;

	public boolean disableEntitySpawn       = true;
	public boolean disableEntityDamage      = true;
	public boolean disableEntityChangeBlock = true;
	public boolean disableEntityTarget      = true;
	public boolean disablePlayerHunger=true;

	public boolean forceWeather = true;
	public boolean weatherState = false;// false for clear, true for rain

	World              defaultWorld;
	Map<UUID, Integer> teleportTimeout = new HashMap<>();
	int                timeoutCounter  = 0;

	//	RMap<String, Object>       settingsMap;
	//	RMap<UUID, PlayerLocation> positionMap;
	//	RMap<UUID, PlayerData>     playerDataMap;
	RTopic               teleportTopic;
	RSet<CustomTeleport> customTeleportSet;
	//	RBucket<WorldEdge>         worldEdgeBucket;

	boolean   worldLoaded;
	WorldEdge worldEdge = new WorldEdge();

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
		String serverHost = config.getString("server.host", "");
		String detectedIp = tryGetLocalAddress();
		if (serverHost == null || serverHost.length() == 0) {
			getLogger().info("Configured host IP is empty. Using detected IP: " + detectedIp);
			serverHost = detectedIp;
		} else if (!serverHost.equals(detectedIp)) {
			getLogger().warning("Configured IP (" + serverHost + ") does not match detected IP (" + detectedIp + ").");
		}
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
		try {
			redisson = (Redisson) Redisson.create(redisConfig);
		} catch (Exception e) {
			throw new RuntimeException("Failed to connect to Redis", e);
		}
		getLogger().info("Connected to Redis @ " + address);

		/// SQL
		sql = new MySQL();
		try {
			sql.connect(config.getString("sql.host", "127.0.0.1"), config.getInt("sql.port", 3306), config.getString("sql.user"), config.getString("sql.pass"), config.getString("sql.db"), config.getString("sql.prefix", "minetile_"));
		} catch (SQLException e) {
			throw new RuntimeException("Failed to connect to MySQL", e);
		}
		getLogger().info("Connected to MySQL");
		try {
			sql.initTables();
		} catch (SQLException e) {
			throw new RuntimeException("Failed to init tables", e);
		}

		getSQL().execute(() -> {
			try {
				tileSize = Integer.parseInt(getSQL().getSetting("tileSize"));
			} catch (NumberFormatException ignored) {
				tileSize = config.getInt("defaults.tileSize", 16);
			}
			tileSizeBlocks = tileSize * 16;

			syncPlayerData = !"false".equals(getSQL().getSetting("syncPlayerData"));

			worldEdge = new WorldEdge(
					Integer.parseInt(getSQL().getSetting("WorldEdge:north")),
					Integer.parseInt(getSQL().getSetting("WorldEdge:east")),
					Integer.parseInt(getSQL().getSetting("WorldEdge:south")),
					Integer.parseInt(getSQL().getSetting("WorldEdge:west"))
			);
		});

		disableBreak = config.getBoolean("protection.blocks.break", true);
		disablePlace = config.getBoolean("protection.blocks.place", true);
		disableFade = config.getBoolean("protection.blocks.fade", true);
		disableIgnite = config.getBoolean("protection.blocks.ignite", true);
		disableBurn = config.getBoolean("protection.blocks.burn", true);
		disableGrow = config.getBoolean("protection.blocks.grow", true);
		disableDecay = config.getBoolean("protection.blocks.decay", true);
		disableSponge = config.getBoolean("protection.blocks.sponge", true);
		disablePiston = config.getBoolean("protection.blocks.piston", true);

		disableEntitySpawn = config.getBoolean("protection.entities.spawn", true);
		disableEntityDamage = config.getBoolean("protection.entities.damage", true);
		disableEntityChangeBlock = config.getBoolean("protection.entities.changeBlock", true);
		disableEntityTarget = config.getBoolean("protection.entities.target", true);
		disablePlayerHunger = config.getBoolean("protection.entities.hunger", true);

		String weatherString = config.getString("protection.weather.force", "clear");
		weatherState = "rain".equalsIgnoreCase(weatherString);
		forceWeather = !"false".equalsIgnoreCase(weatherString);

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

		RTopic commandTopic = redisson.getTopic("MineTile:CommandSync");
		commandTopic.addListener(GlobalCommand.class, (channel, command) -> {
			getLogger().info("Received Global Command: " + command.command);
			if (command.command.length() > 0) {
				Bukkit.getScheduler().runTask(ContainerPlugin.this, () -> getServer().dispatchCommand(Bukkit.getConsoleSender(), command.command));
			}
		});

		Bukkit.getPluginManager().registerEvents(new WorldLoadListener(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

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

	public Redisson getRedis() {
		return this.redisson;
	}

	public MySQL getSQL() {
		return this.sql;
	}

	@Override
	public void onDisable() {
		getSQL().execute(() -> {
			getSQL().removeTile(serverData.serverId);
		});
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
					if (args.length != 3) {
						return false;
					}
					double x = Double.parseDouble(args[0]);
					double y = Double.parseDouble(args[1]);
					double z = Double.parseDouble(args[2]);

					globalTeleport(player, x, y, z);
					sender.sendMessage("Teleport queued");
				}
			}
		}
		return true;
	}

	String tryGetLocalAddress() {
		try (final DatagramSocket socket = new DatagramSocket()) {
			socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
			return socket.getLocalAddress().getHostAddress();
		} catch (IOException e) {
			return null;
		}
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

	public void globalTeleport(Player player, double x, double y, double z) {
		globalTeleport(player, x, y, z, 0, 0);
	}

	public void globalTeleport(Player player, double x, double y, double z, float yaw, float pitch) {
		int tX = (int) Math.round((x / 16) / (double) (tileSize * 2));
		int tZ = (int) Math.round((z / 16) / (double) (tileSize * 2));

		updateGlobalLocation(player);
		if (tX == tileData.x && tZ == tileData.z) {
			double localX = globalToLocal(x, tileData.x, tileSize, worldCenter.getX());
			double localZ = globalToLocal(z, tileData.x, tileSize, worldCenter.getZ());

			defaultWorld.getChunkAtAsync((int)localX>>4,(int)localZ>>4).thenAccept(chunk -> {
				Bukkit.getScheduler().runTask(ContainerPlugin.this, () -> player.teleport(new Location(
						defaultWorld,
						localX,
						y,
						localZ,
						yaw,
						pitch
				)));
			});
			return;
		}
		teleportTopic.publishAsync(new TeleportRequest(player.getUniqueId(), serverData.serverId, x / 16, y / 16, z / 16));
	}

	public PlayerLocation getGlobalLocation(Player player) {
		double globalX = localToGlobal(player.getLocation().getX(), tileData.x, tileSize, worldCenter.getX());
		double globalZ = localToGlobal(player.getLocation().getZ(), tileData.z, tileSize, worldCenter.getZ());

		return new PlayerLocation(globalX, player.getLocation().getY(), globalZ, player.getLocation().getPitch(), player.getLocation().getYaw());
	}

	public PlayerLocation updateGlobalLocation(Player player) {
		PlayerLocation globalLocation = getGlobalLocation(player);
		getSQL().execute(() -> getSQL().updatePosition(player.getUniqueId(), globalLocation));
		return globalLocation;
	}

	public void discoverServer() {
		if (!worldLoaded) { return; }

		getSQL().execute(() -> getSQL().updateTile(serverData.serverId, tileData.x, tileData.z));

		RTopic serverTopic = redisson.getTopic("MineTile:ServerDiscovery");
		System.out.println(serverData);
		serverTopic.publish(serverData);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
		if (!"minetile:minetile".equals(channel)) { return; }
		ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
		String subChannel = in.readUTF();
		//
		//		if ("LocationRequest".equals(subChannel)) {
		//			ByteArrayDataOutput out = ByteStreams.newDataOutput();
		//			out.writeUTF("PlayerLocation");
		//			out.writeDouble(player.getLocation().getX());
		//			out.writeDouble(player.getLocation().getY());
		//			out.writeDouble(player.getLocation().getZ());
		//			player.sendPluginMessage(this, "minetile:minetile", out.toByteArray());
		//		}

		//		ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
		//		String subChannel = in.readUTF();
		//		byte hasData = in.readByte();
		//		ByteArraySerializable<?> data = null;
		//		if (hasData > 0) {
		//			String dataClass = in.readUTF();
		//			try {
		//				data = (ByteArraySerializable<?>) Class.forName(dataClass).newInstance();
		//			} catch (ReflectiveOperationException e) {
		//				getLogger().log(Level.SEVERE, "Failed to instantiate data class " + dataClass, e);
		//				return;
		//			}
		//			data.readFromByteArray(in);
		//		}
		//
		//		handleBungeecordData(subChannel, player, data);
	}

	public void handleBungeecordData(String subChannel, Player receiver, ByteArraySerializable data) {

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

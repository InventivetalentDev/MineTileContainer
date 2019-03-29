package org.inventivetalent.minetile.container;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EmptyWorldGenerator extends ChunkGenerator {

	private static List<BlockPopulator> EMPTY_POPULATOR_LIST = new ArrayList<>();

	public EmptyWorldGenerator() {
		super();
	}

	@Override
	public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
		ChunkData data = createChunkData(world);
		data.setRegion(0, 0, 0, 16, 16, 16, Material.AIR);
		return data;
	}

	@Override
	public boolean canSpawn(World world, int x, int z) {
		// Disable spawns
		return false;
	}

	@Override
	public List<BlockPopulator> getDefaultPopulators(World world) {
		return EMPTY_POPULATOR_LIST;
	}

	@Override
	public Location getFixedSpawnLocation(World world, Random random) {
		return new Location(world, 0, 100, 0);
	}
}

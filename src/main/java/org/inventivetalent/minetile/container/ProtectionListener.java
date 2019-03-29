package org.inventivetalent.minetile.container;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;

public class ProtectionListener implements Listener {

	private ContainerPlugin plugin;

	public ProtectionListener(ContainerPlugin plugin) {
		this.plugin = plugin;
	}

	//////// Blocks

	@EventHandler(priority = EventPriority.HIGH)
	public void on(BlockBreakEvent event) {
		if (plugin.disableBreak) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(BlockPlaceEvent event) {
		if (plugin.disablePlace) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(BlockFormEvent event) {
		if (plugin.disableFade) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(BlockIgniteEvent event) {
		if (plugin.disableIgnite) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(BlockBurnEvent event) {
		if (plugin.disableBurn) {
			event.setCancelled(true);
		}
	}
	@EventHandler(priority = EventPriority.HIGH)
	public void on(BlockGrowEvent event) {
		if (plugin.disableGrow) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(LeavesDecayEvent event) {
		if (plugin.disableDecay) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(SpongeAbsorbEvent event) {
		if (plugin.disableSponge) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(BlockPistonExtendEvent event) {
		if (plugin.disablePiston) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(BlockPistonRetractEvent event) {
		if (plugin.disablePiston) {
			event.setCancelled(true);
		}
	}





}

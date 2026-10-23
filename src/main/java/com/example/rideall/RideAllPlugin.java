package com.example.rideall;

import com.example.rideall.carry.CarryManager;
import com.example.rideall.command.RideCommand;
import com.example.rideall.listener.CarryListener;
import com.example.rideall.util.CarryData;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RideAll —— 让任意实体骑乘任意实体。
 */
public final class RideAllPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CarryData.setDefaultMode(getConfig().getString("default-mode", CarryData.MODE_APPEND));
        RideCommand.register(this);
        getServer().getPluginManager().registerEvents(new CarryListener(), this);
        getLogger().info("RideAll 已启用");
    }

    @Override
    public void onDisable() {
        CarryManager.releaseAllOnline();
        getLogger().info("RideAll 已停用");
    }
}

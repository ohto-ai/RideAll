package com.example.template;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Main plugin class.
 * <p>
 * Rename this class and update plugin.yml's {@code main} field to match.
 */
public final class TemplatePlugin extends JavaPlugin {

    private static TemplatePlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config if not present
        saveDefaultConfig();

        // Register commands, listeners, etc.
        // getCommand("template").setExecutor(new TemplateCommand(this));

        getLogger().log(Level.INFO, "TemplatePlugin v{0} enabled!", getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        getLogger().info("TemplatePlugin disabled.");
        instance = null;
    }

    /**
     * Get the singleton plugin instance.
     */
    public static TemplatePlugin getInstance() {
        return instance;
    }
}

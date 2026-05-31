package com.aermini.aerbedwarsreward;

import com.aermini.aerbedwarsreward.commands.VoteCommand;
import com.aermini.aerbedwarsreward.listeners.BedwarsGameListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public class AerBedwarsReward extends JavaPlugin {
    private static AerBedwarsReward instance;
    private Logger logger;

    @Override
    public void onEnable() {
        instance = this;
        logger = getLogger();
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new BedwarsGameListener(), this);
        getCommand("aerbedwarsreward").setExecutor(new VoteCommand(this));
        if (getConfig().getBoolean("debug", false)) logger.info("命令 aerbedwarsreward 已注册");

        logger.info("=================================");
        logger.info("  AerBedwarsReward 已启用!");
        logger.info("  作者: AerMini");
        logger.info("  版本: ");
        logger.info("=================================");

        if (getConfig().getBoolean("debug", false)) {
            logger.warning("调试模式已启用!");
        }
    }

    @Override
    public void onDisable() {
        logger.info("AerBedwarsReward 已禁用!");
    }

    /**
     * 获取插件实例
     *
     * @return 插件实例
     */
    public static AerBedwarsReward getInstance() {
        return instance;
    }

    /**
     * 获取日志记录器
     *
     * @return 日志记录器
     */
    public Logger getPluginLogger() {
        return logger;
    }
}

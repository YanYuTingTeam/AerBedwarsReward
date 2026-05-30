package com.aermini.aerbedwarsreward.commands;

import com.aermini.aerbedwarsreward.AerBedwarsReward;
import com.aermini.aerbedwarsreward.listeners.BedwarsGameEndListener;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class VoteCommand implements CommandExecutor {
    private final AerBedwarsReward plugin;
    public VoteCommand(AerBedwarsReward plugin) {
        this.plugin = plugin;
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getPluginLogger().info("[VoteCommand] VoteCommand 已初始化");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getPluginLogger().info("[VoteCommand] 命令被调用 - 发送者: " + sender.getName() + ", 参数数量: " + args.length);
            for (int i = 0; i < args.length; i++) {
                plugin.getPluginLogger().info("[VoteCommand] args[" + i + "] = " + args[i]);
            }
        }
        try {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家可以执行此命令");
                return true;
            }

            Player player = (Player) sender;
            if (args.length < 3) return true;
            String subCommand = args[0];
            if (!subCommand.equalsIgnoreCase("vote")) return true;
            String gameName = args[1];
            String buttonName = args[2];
            if (gameName == null || gameName.trim().isEmpty()) return true;
            if (buttonName == null || buttonName.trim().isEmpty()) return true;
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getPluginLogger().info(
                    String.format("[VoteCommand] 玩家 %s 执行投票命令: 游戏=%s, 选项=%s",
                        player.getName(), gameName, buttonName)
                );
            }
            BedwarsGameEndListener.handleVote(player, gameName, buttonName);
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getPluginLogger().info("[VoteCommand] 投票处理完成");
            }
            return true;
        } catch (Exception e) {
            plugin.getPluginLogger().severe("[VoteCommand] 执行命令时发生异常: " + e.getMessage());
            e.printStackTrace();
            if (sender instanceof Player) {
                sender.sendMessage(ChatColor.RED + "执行命令时发生错误");
            }
            return false;
        }
    }
}

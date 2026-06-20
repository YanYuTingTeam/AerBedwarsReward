package com.aermini.aerbedwarsreward.listeners;

import com.aermini.aerbedwarsreward.AerBedwarsReward;
import io.github.bedwarsrel.events.BedwarsGameStartedEvent;
import io.github.bedwarsrel.events.BedwarsGameOverEvent;
import io.github.bedwarsrel.events.BedwarsPlayerLeaveEvent;
import io.github.bedwarsrel.game.Game;
import io.github.bedwarsrel.game.Team;
import me.ram.bedwarsscoreboardaddon.Main;
import me.ram.bedwarsscoreboardaddon.arena.Arena;
import me.ram.bedwarsscoreboardaddon.storage.PlayerGameStorage;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.minecraft.server.v1_8_R3.IChatBaseComponent;
import net.minecraft.server.v1_8_R3.PacketPlayOutChat;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.*;

public class BedwarsGameListener implements Listener {
    private static final Map<String, Set<UUID>> votedPlayers = new HashMap<>();
    private static final Map<String, Map<UUID, String>> gamePlayers = new HashMap<>();
    private static final Set<String> processedGames = new HashSet<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameStarted(BedwarsGameStartedEvent event) {
        Game game = event.getGame();
        String gameName = game.getName();
        Map<UUID, String> playersMap = new HashMap<>();
        for (Player player : game.getPlayers()) {
            playersMap.put(player.getUniqueId(), player.getName());
        }
        gamePlayers.put(gameName, playersMap);
        processedGames.remove(gameName);
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getPluginLogger().info("游戏开始 " + gameName + "，记录 " + playersMap.size() + " 名玩家");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLeave(BedwarsPlayerLeaveEvent event) {
        Game game = event.getGame();
        String gameName = game.getName();
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        Map<UUID, String> playerDataMap = gamePlayers.get(gameName);
        if (playerDataMap != null) {
            String removed = playerDataMap.remove(playerId);
            if (removed != null) {
                AerBedwarsReward plugin = AerBedwarsReward.getInstance();
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getPluginLogger().info("玩家 " + player.getName()
                            + " 离开游戏 " + gameName + "，已从待分发奖励列表移除");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameEnd(BedwarsGameOverEvent event) {
        Game game = event.getGame();
        String gameName = game.getName();
        if (processedGames.contains(gameName)) return;

        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getPluginLogger().info("游戏结束: " + gameName);
        }

        processedGames.add(gameName);
        Map<Player, Integer> playerScores = getPlayerScores(game, event.getWinner());
        if (playerScores.isEmpty()) {
            plugin.getPluginLogger().warning("游戏 " + gameName + " 没有玩家数据，跳过奖励发放");
            sendEvalMessage(game);
            gamePlayers.remove(gameName);
            return;
        }

        List<Map.Entry<Player, Integer>> sortedPlayers = sortPlayersByScore(playerScores);
        if (plugin.getConfig().getBoolean("debug", false)) {
            printRankings(sortedPlayers);
        }

        distributeRewards(sortedPlayers, game);
        sendEvalMessage(game);
        gamePlayers.remove(gameName);
    }

    private Map<Player, Integer> getPlayerScores(Game game, Team winner) {
        Map<Player, Integer> scores = new HashMap<>();
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        int scoreKill = plugin.getConfig().getInt("scores.kill", 3);
        int scoreBedDestroy = plugin.getConfig().getInt("scores.bed-destroy", 5);
        String gameName = game.getName();
        Map<UUID, String> playerDataMap = gamePlayers.getOrDefault(gameName, new HashMap<>());
        if (playerDataMap.isEmpty()) {
            plugin.getPluginLogger().warning("游戏 " + gameName + " 没有记录玩家，跳过奖励发放");
            return scores;
        }

        Arena arena = Main.getInstance().getArenaManager().getArena(game.getName());
        if (arena != null) {
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getPluginLogger().info("成功从 BedwarsScoreboardAddon 获取 Arena");
            }

            Map<Player, Integer> scoresFromAddon = getScoresFromBWSBA(arena, playerDataMap, scoreKill, scoreBedDestroy);
            if (!scoresFromAddon.isEmpty()) {
                return scoresFromAddon;
            }
        } else {
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getPluginLogger().warning("从 BedwarsScoreboardAddon 中找不到 " + gameName + " 所属Arena");
            }
        }

        for (Map.Entry<UUID, String> entry : playerDataMap.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                scores.put(player, 0);
            }
        }
        return scores;
    }

    private Map<Player, Integer> getScoresFromBWSBA(Arena arena, Map<UUID, String> playerDataMap,
                                                    int scoreKill, int scoreBedDestroy) {
        Map<Player, Integer> scores = new HashMap<>();
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();

        PlayerGameStorage playerGameStorage = arena.getPlayerGameStorage();

        if (playerGameStorage == null) {
            plugin.getPluginLogger().warning("playerGameStorage 为 null");
            return scores;
        }

        for (Map.Entry<UUID, String> entry : playerDataMap.entrySet()) {
            UUID playerId = entry.getKey();
            String playerName = entry.getValue();
            Player player = Bukkit.getPlayer(playerId);

            int kills = playerGameStorage.getTotalKills(playerName);
            int beds = playerGameStorage.getBeds(playerName);
            int score = (kills * scoreKill) + (beds * scoreBedDestroy);

            if (player != null && player.isOnline()) {
                scores.put(player, score);
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getPluginLogger().info(String.format(
                            "玩家 %s (在线): 击杀=%d, 破坏床=%d, 分数=%d",
                            playerName, kills, beds, score
                    ));
                }
            }
        }

        return scores;
    }

    private List<Map.Entry<Player, Integer>> sortPlayersByScore(Map<Player, Integer> playerScores) {
        List<Map.Entry<Player, Integer>> sortedList = new ArrayList<>(playerScores.entrySet());
        sortedList.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue())); // 降序排序
        return sortedList;
    }

    private void printRankings(List<Map.Entry<Player, Integer>> sortedPlayers) {
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        plugin.getPluginLogger().info("=== 游戏排名 ===");
        int rank = 1;
        for (Map.Entry<Player, Integer> entry : sortedPlayers) {
            Player player = entry.getKey();
            int score = entry.getValue();
            plugin.getPluginLogger().info("第 " + rank + " 名: " + player.getName() + " - " + score + " 分");
            rank++;
        }
    }

    private void distributeRewards(List<Map.Entry<Player, Integer>> sortedPlayers, Game game) {
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        int rewardedCount = 0;
        if (plugin.getConfig().contains("rewards")) {
            ConfigurationSection rewardsSection = plugin.getConfig().getConfigurationSection("rewards");
            if (rewardsSection != null) {
                for (String rewardKey : rewardsSection.getKeys(false)) {
                    List<Integer> topRanks = plugin.getConfig().getIntegerList("rewards." + rewardKey + ".top");
                    List<String> commands = plugin.getConfig().getStringList("rewards." + rewardKey + ".cmd");
                    if (topRanks.isEmpty() || commands.isEmpty()) continue;
                    for (int playerRank = 1; playerRank <= sortedPlayers.size(); playerRank++) {
                        if (topRanks.contains(playerRank)) {
                            Player player = sortedPlayers.get(playerRank - 1).getKey();
                            int score = sortedPlayers.get(playerRank - 1).getValue();
                            executeCommands(player, commands);
                            if (plugin.getConfig().getBoolean("debug", false)) {
                                plugin.getPluginLogger().info(
                                        String.format("玩家 %s (第%d名, %d分) 在游戏 %s 中获得奖励",
                                                player.getName(), playerRank, score, game.getName())
                                );
                            }
                            rewardedCount++;
                        }
                    }
                }
            }
        }
        if (rewardedCount > 0 && plugin.getConfig().getBoolean("debug", false)) {
            plugin.getPluginLogger().info("本次共发放 " + rewardedCount + " 份奖励");
        }
    }

    private void sendEvalMessage(Game game) {
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        if (!plugin.getConfig().contains("eval")) return;
        String gameName = game.getName();
        if (!votedPlayers.containsKey(gameName)) votedPlayers.put(gameName, new HashSet<>());
        List<String> rawMessages = plugin.getConfig().getStringList("eval.raw");
        Map<String, Map<String, Object>> buttonsConfig = new HashMap<>();
        ConfigurationSection buttonsSection = plugin.getConfig().getConfigurationSection("eval.buttons");
        if (buttonsSection != null) {
            for (String buttonKey : buttonsSection.getKeys(false)) {
                buttonsConfig.put(buttonKey, buttonsSection.getConfigurationSection(buttonKey).getValues(false));
            }
        }
        String votedMessage = plugin.getConfig().getString("eval.voted");
        Map<UUID, String> playerDataMap = gamePlayers.getOrDefault(gameName, new HashMap<>());
        for (Map.Entry<UUID, String> entry : playerDataMap.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            if (votedPlayers.get(gameName).contains(player.getUniqueId())) {
                sendMessage(player, new BaseComponent[]{new TextComponent(ChatColor.translateAlternateColorCodes('&', votedMessage))});
                continue;
            }
            BaseComponent[] message = buildEvalMessage(rawMessages, buttonsConfig, player, game, gameName);
            sendMessage(player, message);
        }
    }

    private void sendMessage(Player player, BaseComponent[] message) {
        try {
            String json = ComponentSerializer.toString(message);
            CraftPlayer craftPlayer = (CraftPlayer) player;
            IChatBaseComponent chatComponent = IChatBaseComponent.ChatSerializer.a(json);
            PacketPlayOutChat packet = new PacketPlayOutChat(chatComponent, (byte) 0);
            craftPlayer.getHandle().playerConnection.sendPacket(packet);
            AerBedwarsReward plugin = AerBedwarsReward.getInstance();
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getPluginLogger().info("使用 NMS 发送 JSON 消息给 " + player.getName());
            }
        } catch (Exception e) {
            AerBedwarsReward.getInstance().getPluginLogger().warning("发送 JSON 消息失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private BaseComponent[] buildEvalMessage(List<String> rawMessages, Map<String, Map<String, Object>> buttonsConfig,
                                              Player player, Game game, String gameName) {
        List<BaseComponent> components = new ArrayList<>();
        for (int i = 0; i < rawMessages.size(); i++) {
            String line = rawMessages.get(i);
            String playerName = player.getName();
            String mapName = game.getName();
            line = line.replace("{player}", playerName).replace("{map}", mapName);
            if (line.contains("{_")) {
                processLineWithButtons(line, buttonsConfig, player, gameName, components);
            } else {
                components.add(new TextComponent(ChatColor.translateAlternateColorCodes('&', line)));
            }
            if (i < rawMessages.size() - 1) components.add(new TextComponent("\n"));
        }
        return components.toArray(new BaseComponent[0]);
    }

    private void processLineWithButtons(String line, Map<String, Map<String, Object>> buttonsConfig,
                                         Player player, String gameName, List<BaseComponent> components) {
        StringBuilder currentText = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (i < line.length() - 1 && c == '{' && line.charAt(i + 1) == '_') {
                if (currentText.length() > 0) {
                    String text = currentText.toString();
                    TextComponent textComp = new TextComponent(ChatColor.translateAlternateColorCodes('&', text));
                    components.add(textComp);
                    currentText.setLength(0);
                }
                int endPos = line.indexOf('}', i);
                if (endPos != -1) {
                    String buttonName = line.substring(i + 2, endPos);
                    addButton(buttonName, buttonsConfig, player, gameName, components);
                    i = endPos + 1;
                } else {
                    currentText.append(c);
                    i++;
                }
            } else {
                currentText.append(c);
                i++;
            }
        }
        if (currentText.length() > 0) {
            String text = currentText.toString();
            TextComponent textComp = new TextComponent(ChatColor.translateAlternateColorCodes('&', text));
            components.add(textComp);
        }
    }

    @SuppressWarnings("unchecked")
    private void addButton(String buttonName, Map<String, Map<String, Object>> buttonsConfig,
                           Player player, String gameName, List<BaseComponent> components) {
        Map<String, Object> buttonConfig = buttonsConfig.get(buttonName);
        if (buttonConfig == null || !buttonConfig.containsKey("display") || !buttonConfig.containsKey("cmd")) {
            return;
        }
        String displayText = ChatColor.translateAlternateColorCodes('&', buttonConfig.get("display").toString());
        Object cmdObj = buttonConfig.get("cmd");
        List<String> commands = new ArrayList<>();
        if (cmdObj instanceof List) {
            commands.addAll((List<String>) cmdObj);
        } else if (cmdObj instanceof String) {
            commands.add((String) cmdObj);
        }
        String hoverText = ChatColor.BLUE + "点击投票";
        if (buttonConfig.containsKey("float") && buttonConfig.get("float") != null) {
            hoverText = ChatColor.translateAlternateColorCodes('&', buttonConfig.get("float").toString());
        }
        TextComponent button = new TextComponent(displayText);
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/aerbedwarsreward vote " + gameName + " " + buttonName));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[]{new TextComponent(hoverText)}));

        components.add(button);
    }

    private static void executeCommands(Player player, List<String> commands) {
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        String playerName = player.getName();
        for (String command : commands) {
            String parsedCommand = command.replace("{player}", playerName);
            if (parsedCommand.startsWith("[CONSOLE]")) {
                String consoleCmd = parsedCommand.substring(10).trim();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCmd);
            } else if (parsedCommand.startsWith("[MESSAGE]")) {
                String message = parsedCommand.substring(10).trim();
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void handleVote(Player player, String gameName, String buttonName) {
        AerBedwarsReward plugin = AerBedwarsReward.getInstance();
        UUID playerId = player.getUniqueId();
        try {
            if (!votedPlayers.containsKey(gameName)) {
                String errorMsg = ChatColor.translateAlternateColorCodes('&',
                        plugin.getConfig().getString("eval.voted", "&c游戏不存在"));
                player.sendMessage(errorMsg);
                return;
            }

            if (votedPlayers.get(gameName).contains(playerId)) {
                String votedMessage = ChatColor.translateAlternateColorCodes('&',
                        plugin.getConfig().getString("eval.voted", "&b你已经投过票了"));
                player.sendMessage(votedMessage);
                return;
            }

            ConfigurationSection buttonsSection = plugin.getConfig().getConfigurationSection("eval.buttons");
            if (buttonsSection == null) {
                player.sendMessage(ChatColor.RED + "投票配置错误");
                return;
            }

            ConfigurationSection buttonSection = buttonsSection.getConfigurationSection(buttonName);
            if (buttonSection == null) {
                player.sendMessage(ChatColor.RED + "无效的投票选项: " + buttonName);
                return;
            }

            votedPlayers.get(gameName).add(playerId);
            List<String> commands = buttonSection.getStringList("cmd");

            if (!commands.isEmpty()) {
                executeCommands(player, commands);
            } else {
                player.sendMessage(ChatColor.RED + "按钮配置错误：没有命令");
            }
            
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getPluginLogger().info("玩家 " + player.getName() + " 在游戏 " + gameName + " 中投了 " + buttonName + " 票");
            }
        } catch (Exception e) {
            plugin.getPluginLogger().severe("处理投票时发生异常: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("投票失败");
        }
    }
}
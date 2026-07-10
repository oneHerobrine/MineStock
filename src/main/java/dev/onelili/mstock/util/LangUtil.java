package dev.onelili.mstock.util;

import dev.onelili.mstock.MineStock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LangUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private FileConfiguration lang;
    private String prefix;

    public LangUtil(MineStock plugin) {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        if (file.exists()) {
            // 用 loadFromString 才能真正捕获到 YAML 语法错误
            try {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                YamlConfiguration test = new YamlConfiguration();
                test.loadFromString(content);
                lang = test;
            } catch (InvalidConfigurationException e) {
                plugin.getLogger().warning("[MineStock] lang.yml 语法错误，用默认文件覆盖: " + e.getMessage());
                plugin.saveResource("lang.yml", true);
                lang = YamlConfiguration.loadConfiguration(file);
            } catch (Exception e) {
                plugin.getLogger().warning("[MineStock] lang.yml 读取失败，用默认文件覆盖: " + e.getMessage());
                plugin.saveResource("lang.yml", true);
                lang = YamlConfiguration.loadConfiguration(file);
            }
        } else {
            plugin.saveResource("lang.yml", false);
            lang = YamlConfiguration.loadConfiguration(file);
        }
        // 合并 jar 内默认值（填补缺失 key）
        InputStream defaultStream = plugin.getResource("lang.yml");
        if (defaultStream != null) {
            lang.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8)));
        }
        prefix = lang.getString("prefix", "<gold>[MineStock]</gold> ");
    }

    public String getRaw(String key) {
        return lang.getString(key, "<red>Missing lang key: " + key + "</red>");
    }

    public Component get(String key, TagResolver... resolvers) {
        String raw = prefix + getRaw(key);
        return MM.deserialize(raw, resolvers);
    }

    public Component getNoPrefix(String key, TagResolver... resolvers) {
        String raw = getRaw(key);
        return MM.deserialize(raw, resolvers);
    }

    public void send(Player player, String key, TagResolver... resolvers) {
        player.sendMessage(get(key, resolvers));
    }

    public void sendNoPrefix(Player player, String key, TagResolver... resolvers) {
        player.sendMessage(getNoPrefix(key, resolvers));
    }

    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    public static TagResolver placeholder(String key, String value) {
        return Placeholder.unparsed(key, value);
    }
}

package com.example.trashcandetector.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * 客户端入口点：监听垃圾桶刷新消息，自动打开垃圾桶 GUI，读取并导出内容
 */
public class TrashCanDetectorClient implements ClientModInitializer {

    public static final String MOD_ID = "trashcandetector";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int READ_DELAY_TICKS = 20;

    /** 已检测到垃圾桶消息，正在等待 /trash 打开容器 GUI */
    private static boolean waitingForTrashScreen;
    /** 容器 GUI 已打开，等待服务器同步槽位数据 */
    private static boolean pendingRead;
    private static HandledScreen<?> pendingScreen;
    private static int pendingTicks;

    @Override
    public void onInitializeClient() {
        LOGGER.info("TrashCan Detector 已加载，开始监听垃圾桶刷新消息");

        // 1) 聊天消息监听：检测到垃圾桶提示后自动发送 /trash
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;

            String text = message.getString();
            String stripped = stripPunctuation(text);

            if (containsTrashKeywords(stripped)) {
                LOGGER.info("检测到垃圾桶刷新消息: {}", text);
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal("[垃圾桶探测器] 检测到垃圾桶刷新，正在自动打开..."),
                        false
                    );
                    // 发送 /trash 指令打开垃圾桶插件 GUI
                    client.player.sendCommand("trash");
                    waitingForTrashScreen = true;
                }
            }

            return true;
        });

        // 2) GUI 打开监听：检测容器屏幕出现，准备读取
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!waitingForTrashScreen) return;
            if (!(screen instanceof HandledScreen<?> handled)) return;

            waitingForTrashScreen = false;
            pendingScreen = handled;
            pendingTicks = 0;
            pendingRead = true;
            LOGGER.info("检测到容器 GUI 已打开，等待槽位数据同步...");
        });

        // 3) 每 tick 检查：延迟后读取容器内容并导出
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!pendingRead || pendingScreen == null) return;

            pendingTicks++;
            if (pendingTicks < READ_DELAY_TICKS) return;

            // 延迟结束，开始读取槽位
            pendingRead = false;
            readAndExportContainer(client, pendingScreen);
            pendingScreen = null;
        });
    }

    /**
     * 从容器 GUI 中读取所有物品槽位，导出为 JSON 和 Markdown 文件
     */
    private static void readAndExportContainer(MinecraftClient client, HandledScreen<?> handled) {
        ScreenHandler handler = handled.getScreenHandler();
        List<Map<String, Object>> items = new ArrayList<>();
        int totalCount = 0;

        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("slot", slot.id);
                item.put("item", stack.getItem().toString());
                item.put("name", stack.getName().getString());
                item.put("count", stack.getCount());
                if (stack.contains(DataComponentTypes.CUSTOM_DATA)) {
                    item.put("custom_data", stack.get(DataComponentTypes.CUSTOM_DATA).toString());
                }
                items.add(item);
                totalCount += stack.getCount();
            }
        }

        if (items.isEmpty()) {
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("[垃圾桶探测器] 容器为空或数据未同步，无内容可导出"),
                    false
                );
            }
            return;
        }

        try {
            Path outDir = client.runDirectory.toPath().resolve("trashcan-detector");
            Files.createDirectories(outDir);

            // 两个文件共用同一时间戳，保证文件名一致
            String ts = timestamp();

            // 写入 JSON
            Path jsonPath = outDir.resolve("trashcan_" + ts + ".json");
            Files.writeString(jsonPath, toJson(items, totalCount), StandardCharsets.UTF_8);

            // 写入 Markdown 表格
            Path mdPath = outDir.resolve("trashcan_" + ts + ".md");
            Files.writeString(mdPath, toMarkdown(items, totalCount), StandardCharsets.UTF_8);

            LOGGER.info("垃圾桶内容已导出: {} / {}", jsonPath, mdPath);

            if (client.player != null) {
                sendClickableFileMessage(client, "JSON 文件", jsonPath);
                sendClickableFileMessage(client, "MD 表格", mdPath);
                client.player.sendMessage(
                    Text.literal("[垃圾桶探测器] 导出完成，请点击查看文件"),
                    false
                );
            }

        } catch (IOException e) {
            LOGGER.error("导出垃圾桶内容失败", e);
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("[垃圾桶探测器] 导出失败: " + e.getMessage()),
                    false
                );
            }
        }
    }

    /**
     * 生成 JSON 格式输出（供 AI 读取）
     */
    private static String toJson(List<Map<String, Object>> items, int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"type\": \"trashcan_contents\",\n");
        sb.append("  \"total_items\": ").append(totalCount).append(",\n");
        sb.append("  \"unique_items\": ").append(items.size()).append(",\n");
        sb.append("  \"items\": [\n");
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            sb.append("    { ");
            List<String> fields = new ArrayList<>();
            for (Map.Entry<String, Object> entry : item.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Number || v instanceof Boolean) {
                    fields.add("\"" + entry.getKey() + "\": " + v);
                } else {
                    fields.add("\"" + entry.getKey() + "\": \"" + escapeJson(v.toString()) + "\"");
                }
            }
            sb.append(String.join(", ", fields));
            sb.append(" }");
            if (i < items.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成 Markdown 表格（供人类阅读）
     */
    private static String toMarkdown(List<Map<String, Object>> items, int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 垃圾桶内容\n\n");
        sb.append("- **总物品数**: ").append(totalCount).append("\n");
        sb.append("- **物品种类**: ").append(items.size()).append("\n\n");
        sb.append("| 槽位 | 物品ID | 名称 | 数量 |\n");
        sb.append("|------|--------|------|------|\n");
        for (Map<String, Object> item : items) {
            sb.append("| ").append(item.get("slot"))
              .append(" | ").append(item.get("item"))
              .append(" | ").append(item.get("name"))
              .append(" | ").append(item.get("count"))
              .append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 发送可点击的文件路径消息到聊天栏
     */
    private static void sendClickableFileMessage(MinecraftClient client, String label, Path path) {
        String absPath = path.toAbsolutePath().toString();
        MutableText prefix = Text.literal("[垃圾桶探测器] " + label + ": ");

        MutableText link = Text.literal(absPath)
            .setStyle(Style.EMPTY
                .withColor(0x55FF55)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.OpenFile(absPath)));

        client.player.sendMessage(prefix.append(link), false);
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String stripPunctuation(String text) {
        return text.replaceAll("[\\p{P}\\p{S}\\s]", "");
    }

    private static boolean containsTrashKeywords(String text) {
        return text.contains("物品被意外清理")
            && text.contains("公共垃圾桶")
            && text.contains("领回");
    }

    // ==================== #pick 指令支持（供 Mixin 调用） ====================

    /**
     * 由 ChatScreenMixin 调用：触发自动打开垃圾桶
     * @param argument 用户输入的物品名称参数（可为空）
     */
    public static void triggerAutoPick(String argument) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (waitingForTrashScreen || pendingRead) {
            client.player.sendMessage(
                Text.literal("[垃圾桶探测器] 已在处理中，请稍候..."),
                false
            );
            return;
        }

        LOGGER.info("玩家通过 #pick 指令触发，参数: {}", argument);
        client.player.sendMessage(
            Text.literal("[垃圾桶探测器] 正在自动打开垃圾桶..."),
            false
        );
        client.player.sendCommand("trash");
        waitingForTrashScreen = true;
    }

    /**
     * 由 ChatScreenMixin 调用：返回所有注册物品 ID 用于 Tab 补全
     */
    public static Iterable<String> getItemIdSuggestions() {
        return () -> StreamSupport.stream(Registries.ITEM.spliterator(), false)
            .map(item -> Registries.ITEM.getId(item).toString())
            .iterator();
    }
}

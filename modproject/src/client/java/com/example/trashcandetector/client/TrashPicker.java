package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 垃圾桶自动翻页拾取状态机（由 //pick start 触发）：
 * 1. 在 0-35 正文格中查找命中搜索列表的物品，shift 点击优先进背包
 * 2. 若物品仍在原格（背包已满/被拒），改用 Ctrl+Q 整组丢到身边
 * 3. 每页处理完后点击“下一页”按钮；翻页前记住本页内容，
 *    若翻页后内容与翻页前相同则说明已到最后一页，遍历结束
 * 4. 结束后关闭 GUI、汇报成果，并以玩家身份在服务器聊天栏播报
 */
public final class TrashPicker {

    /** 垃圾桶 GUI 总槽位（6 行容器 54 + 玩家背包 36） */
    private static final int TOTAL_SLOTS = 90;
    /** 0-35 为正文内容 */
    private static final int CONTENT_SLOTS = 36;
    /** 36-53 为 UI 按钮区 */
    private static final int UI_START = 36;
    private static final int UI_END = 54;
    private static final String NEXT_PAGE_BUTTON = "下一页";

    /** 拾取/丢出点击后的确认等待（tick） */
    private static final int VERIFY_TICKS = 10;
    /** 翻页后等待内容同步（tick） */
    private static final int FLIP_TICKS = 20;
    /** 安全上限：最多翻多少页，防止极端情况死循环 */
    private static final int MAX_PAGES = 200;
    /** 连续拾取失败多少次后放弃 */
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    private enum Phase {
        SCAN, VERIFY_MOVE, VERIFY_THROW, WAIT_FLIP
    }

    private static boolean active;
    private static Phase phase = Phase.SCAN;
    private static int waitTicks;
    /** 当前扫描到的正文格（0-35） */
    private static int scanSlot;
    /** 正在等待确认的点击目标 */
    private static int actionSlot;
    private static String actionItemId;
    private static String actionName;
    private static int actionCount;
    /** 翻页前的整页内容签名，用于翻到底检测 */
    private static String pageSignature;
    private static int pagesFlipped;
    private static int consecutiveFailures;

    /** 拾取成果，key = 物品ID|显示名 */
    private static final Map<String, Tallied> RESULTS = new LinkedHashMap<>();

    private static final class Tallied {
        final String id;
        final String name;
        int invCount;
        int droppedCount;

        Tallied(String id, String name) {
            this.id = id;
            this.name = name;
        }

        int totalCount() {
            return invCount + droppedCount;
        }
    }

    private TrashPicker() {
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * 垃圾桶 GUI 已打开且首页数据同步完成后调用，开始拾取
     */
    public static void begin() {
        active = true;
        phase = Phase.SCAN;
        waitTicks = 0;
        scanSlot = 0;
        pagesFlipped = 0;
        consecutiveFailures = 0;
        pageSignature = null;
        RESULTS.clear();
        TrashCanDetectorClient.feedback("开始搜索垃圾桶，待搜索 " + PickList.size() + " 项物品...");
    }

    /**
     * 每帧调用（客户端主线程）
     */
    public static void tick(MinecraftClient client) {
        if (!active) {
            return;
        }
        if (client.player == null) {
            abort(client, "玩家已离线");
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
            abort(client, "垃圾桶界面被关闭");
            return;
        }
        ScreenHandler handler = handled.getScreenHandler();
        if (handler.slots.size() != TOTAL_SLOTS) {
            abort(client, "当前界面不是垃圾桶（6 行容器）");
            return;
        }

        switch (phase) {
            case SCAN -> tickScan(client, handler);
            case VERIFY_MOVE, VERIFY_THROW -> tickVerify(client, handler);
            case WAIT_FLIP -> tickFlip(client, handler);
        }
    }

    /**
     * 扫描当前页：先取正文格中命中列表的物品，取完后翻页
     */
    private static void tickScan(MinecraftClient client, ScreenHandler handler) {
        // 1) 在 0-35 正文格中查找下一个命中物品
        while (scanSlot < CONTENT_SLOTS) {
            ItemStack stack = handler.getSlot(scanSlot).getStack();
            if (!stack.isEmpty() && PickList.matches(stack)) {
                actionSlot = scanSlot;
                actionItemId = Registries.ITEM.getId(stack.getItem()).toString();
                actionName = stack.getName().getString();
                actionCount = stack.getCount();
                // shift 点击：优先进玩家背包
                click(client, handler, actionSlot, 0, SlotActionType.QUICK_MOVE);
                phase = Phase.VERIFY_MOVE;
                waitTicks = 0;
                return;
            }
            scanSlot++;
        }

        // 2) 本页处理完，准备翻页
        if (pagesFlipped >= MAX_PAGES) {
            finish(client, "已达到最大翻页数 " + MAX_PAGES + "，为安全起见提前结束");
            return;
        }
        int button = findNextPageButton(handler);
        if (button < 0) {
            finish(client, pagesFlipped == 0
                ? "本页未找到[下一页]按钮，单页遍历完成"
                : "未找到[下一页]按钮，遍历完成");
            return;
        }

        // 翻页前记住本页内容（用于翻到底检测）
        pageSignature = signature(handler);
        click(client, handler, button, 0, SlotActionType.PICKUP);
        pagesFlipped++;
        if (pagesFlipped % 10 == 0) {
            TrashCanDetectorClient.feedback("已翻 " + pagesFlipped + " 页...");
        }
        phase = Phase.WAIT_FLIP;
        waitTicks = 0;
    }

    /**
     * 确认拾取结果：shift 点击后物品仍在原格 → 背包已满，改为整组丢出
     */
    private static void tickVerify(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < VERIFY_TICKS) {
            return;
        }

        boolean stillPresent = isSameStackAt(handler, actionSlot, actionItemId);

        if (phase == Phase.VERIFY_MOVE) {
            if (!stillPresent) {
                // 已进背包
                tally(true);
                consecutiveFailures = 0;
                scanSlot++;
                phase = Phase.SCAN;
            } else {
                // 背包已满或被拒绝 → 整组丢到身边（等同 Ctrl+Q）
                click(client, handler, actionSlot, 1, SlotActionType.THROW);
                phase = Phase.VERIFY_THROW;
                waitTicks = 0;
            }
        } else {
            if (!stillPresent) {
                tally(false);
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
                TrashCanDetectorClient.feedback("拾取失败：" + actionName + " 仍在垃圾桶中，跳过");
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    abort(client, "连续 " + MAX_CONSECUTIVE_FAILURES + " 次拾取失败");
                    return;
                }
            }
            scanSlot++;
            phase = Phase.SCAN;
        }
    }

    /**
     * 等待翻页内容同步：与翻页前内容相同 → 已翻到底，遍历结束
     */
    private static void tickFlip(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < FLIP_TICKS) {
            return;
        }

        if (signature(handler).equals(pageSignature)) {
            finish(client, "已翻到最后一页，共遍历 " + pagesFlipped + " 页");
        } else {
            // 新的一页，从第 0 格重新扫描
            scanSlot = 0;
            phase = Phase.SCAN;
        }
    }

    private static void click(MinecraftClient client, ScreenHandler handler, int slotId, int button, SlotActionType type) {
        client.interactionManager.clickSlot(handler.syncId, slotId, button, type, client.player);
    }

    /**
     * 在 36-53 UI 按钮区查找[下一页]按钮所在格
     */
    private static int findNextPageButton(ScreenHandler handler) {
        for (int i = UI_START; i < UI_END; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && NEXT_PAGE_BUTTON.equals(stack.getName().getString())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSameStackAt(ScreenHandler handler, int slotId, String itemId) {
        if (slotId >= handler.slots.size()) {
            return false;
        }
        ItemStack stack = handler.getSlot(slotId).getStack();
        return !stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId);
    }

    private static void tally(boolean toInventory) {
        String key = actionItemId + "|" + actionName;
        Tallied tallied = RESULTS.computeIfAbsent(key, k -> new Tallied(actionItemId, actionName));
        if (toInventory) {
            tallied.invCount += actionCount;
        } else {
            tallied.droppedCount += actionCount;
        }
    }

    /**
     * 整页内容签名（0-53 的物品ID、数量、显示名），用于翻页前后对比
     */
    private static String signature(ScreenHandler handler) {
        StringBuilder sb = new StringBuilder(1024);
        for (int i = 0; i < UI_END; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                sb.append(i).append(":;");
            } else {
                sb.append(i).append(':')
                    .append(Registries.ITEM.getId(stack.getItem()))
                    .append('x').append(stack.getCount())
                    .append('|').append(stack.getName().getString())
                    .append(';');
            }
        }
        return sb.toString();
    }

    private static void abort(MinecraftClient client, String reason) {
        active = false;
        closeGui(client);
        report(client, "已中止：" + reason);
    }

    private static void finish(MinecraftClient client, String reason) {
        active = false;
        closeGui(client);
        report(client, reason);
    }

    private static void closeGui(MinecraftClient client) {
        if (client.player != null && client.currentScreen instanceof HandledScreen) {
            // closeHandledScreen 会发送关闭容器的封包并关闭界面
            client.player.closeHandledScreen();
        } else if (client.currentScreen != null) {
            client.setScreen(null);
        }
    }

    /**
     * 汇报搜垃圾成果，并以玩家身份在服务器聊天栏播报
     */
    private static void report(MinecraftClient client, String summary) {
        TrashCanDetectorClient.feedback("—— 搜垃圾成果 ——");
        TrashCanDetectorClient.feedback(summary);

        int kinds = RESULTS.size();
        if (kinds == 0) {
            TrashCanDetectorClient.feedback("本次没有找到搜索列表中的物品");
            return;
        }

        int totalItems = 0;
        for (Tallied tallied : RESULTS.values()) {
            totalItems += tallied.totalCount();
            TrashCanDetectorClient.feedback(tallied.name + " x" + tallied.totalCount()
                + "（背包 " + tallied.invCount + " / 丢出 " + tallied.droppedCount + "）");
        }
        TrashCanDetectorClient.feedback("共捡到 " + kinds + " 种 / " + totalItems + " 件物品");

        // 以玩家身份在服务器聊天栏播报（超出 256 字符时截断物品列表）
        String playerName = client.player != null ? client.player.getName().getString() : "";
        String tail = "，你也来试试吧！";
        StringBuilder sb = new StringBuilder("我").append(playerName)
            .append("在服务器垃圾桶百亿补贴活动中赢得了");
        int listed = 0;
        boolean truncated = false;
        for (Tallied tallied : RESULTS.values()) {
            String piece = tallied.name + "x" + tallied.totalCount();
            if (sb.length() + piece.length() + tail.length() + 8 > 256) {
                truncated = true;
                break;
            }
            if (listed > 0) {
                sb.append("、");
            }
            sb.append(piece);
            listed++;
        }
        if (truncated) {
            sb.append("等").append(kinds).append("种物品");
        }
        sb.append(tail);

        if (client.player != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage(sb.toString());
        }
    }
}

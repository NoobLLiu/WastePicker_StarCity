package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Safely takes the uniquely identified BUG item from a Slimefun menu. */
final class SlimefunBugManager {

    private static final String TARGET_ID = "minecraft:bone_meal";
    private static final String TARGET_NAME = "BUG";
    private static final int SCAN_DELAY_TICKS = 5;
    private static final int ACTION_DELAY_TICKS = 2;
    private static final int ACTION_TIMEOUT_TICKS = 40;

    private enum Phase { IDLE, WAIT_PICKUP, WAIT_PLACE }

    private static Phase phase = Phase.IDLE;
    private static int phaseTicks;
    private static int scanCooldown;
    private static int destinationSlot = -1;
    private static int baselineTargetCount;
    private static HandledScreen<?> activeScreen;
    private static Screen observedScreen;

    private SlimefunBugManager() {
    }

    static boolean isActive() {
        return phase != Phase.IDLE;
    }

    static void onScreenOpened(Screen screen) {
        if (screen != observedScreen) {
            observedScreen = screen;
            scanCooldown = SCAN_DELAY_TICKS;
        }
    }

    static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
            reset();
            observedScreen = null;
            return;
        }

        if (phase == Phase.IDLE) {
            if (!TrashCanDetectorConfigs.AUTO_TAKE_SLIMEFUN_BUG.getBooleanValue()
                || !(client.currentScreen instanceof HandledScreen<?> handled)
                || client.currentScreen != observedScreen || scanCooldown > 0
                || !isLikelySlimefunMenu(handled)) {
                if (scanCooldown > 0) scanCooldown--;
                return;
            }
            scanCooldown = SCAN_DELAY_TICKS;
            requestInternal(client, handled, false);
            return;
        }

        if (!(client.currentScreen instanceof HandledScreen<?> handled)
            || client.currentScreen != activeScreen) {
            fail(client, "粘液 BUG 菜单已关闭，操作已取消");
            return;
        }

        if (++phaseTicks > ACTION_TIMEOUT_TICKS) {
            fail(client, "粘液 BUG 取出等待服务器同步超时");
            return;
        }

        ScreenHandler handler = handled.getScreenHandler();
        ItemStack cursor = handler.getCursorStack();
        if (phase == Phase.WAIT_PICKUP) {
            if (phaseTicks < ACTION_DELAY_TICKS) return;
            if (!cursor.isEmpty()) {
                if (!isTarget(cursor)) {
                    fail(client, "检测到非目标鼠标物品，已停止粘液 BUG 取出");
                    return;
                }
                click(client, handler, destinationSlot);
                phase = Phase.WAIT_PLACE;
                phaseTicks = 0;
                return;
            }
            if (countPlayerTargets(client.player.getInventory()) > baselineTargetCount) {
                succeed(client);
            }
            return;
        }

        if (phase == Phase.WAIT_PLACE) {
            if (!cursor.isEmpty()) {
                if (!isTarget(cursor)) fail(client, "取出后检测到异常鼠标物品，已停止");
                return;
            }
            if (countPlayerTargets(client.player.getInventory()) > baselineTargetCount) {
                succeed(client);
            }
        }
    }

    static void requestManual() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
            TrashCanDetectorClient.tip("请先打开粘液菜单");
            return;
        }
        if (isActive()) {
            TrashCanDetectorClient.tip("粘液 BUG 正在处理中");
            return;
        }
        requestInternal(client, handled, true);
    }

    private static void requestInternal(MinecraftClient client, HandledScreen<?> handled, boolean manual) {
        if (isActive() || TrashCanDetectorClient.isBusy() || InfiniteFlightDeviceManager.isActive()) {
            if (manual) TrashCanDetectorClient.tip("当前有其他自动化操作正在运行");
            return;
        }
        ScreenHandler handler = handled.getScreenHandler();
        if (!handler.getCursorStack().isEmpty()) {
            if (manual) TrashCanDetectorClient.tip("鼠标上已有物品，未执行粘液 BUG 取出");
            return;
        }
        int source = findTargetSlot(handler, client.player.getInventory());
        if (source < 0) {
            if (manual) TrashCanDetectorClient.tip("当前菜单没有找到 BUG");
            return;
        }
        int destination = findEmptyPlayerSlot(handler, client.player.getInventory());
        if (destination < 0) {
            TrashCanDetectorClient.tip("背包没有空槽，未取出粘液 BUG");
            return;
        }
        destinationSlot = destination;
        baselineTargetCount = countPlayerTargets(client.player.getInventory());
        activeScreen = handled;
        phase = Phase.WAIT_PICKUP;
        phaseTicks = 0;
        click(client, handler, source);
        TrashCanDetectorClient.tip("正在取出粘液菜单中的 BUG");
    }

    private static int findTargetSlot(ScreenHandler handler, PlayerInventory inventory) {
        for (int index = 0; index < handler.slots.size(); index++) {
            Slot slot = handler.getSlot(index);
            if (slot.inventory != inventory && isTarget(slot.getStack())) return index;
        }
        return -1;
    }

    private static int findEmptyPlayerSlot(ScreenHandler handler, PlayerInventory inventory) {
        for (int index = 0; index < handler.slots.size(); index++) {
            Slot slot = handler.getSlot(index);
            if (slot.inventory == inventory && slot.getStack().isEmpty()
                && slot.getIndex() >= 0 && slot.getIndex() < PlayerInventory.OFF_HAND_SLOT) return index;
        }
        return -1;
    }

    private static void click(MinecraftClient client, ScreenHandler handler, int slot) {
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
    }

    private static boolean isTarget(ItemStack stack) {
        return !stack.isEmpty() && TARGET_ID.equals(Registries.ITEM.getId(stack.getItem()).toString())
            && TARGET_NAME.equals(stack.getName().getString());
    }

    private static int countPlayerTargets(PlayerInventory inventory) {
        int count = 0;
        for (int index = 0; index < PlayerInventory.OFF_HAND_SLOT; index++) {
            ItemStack stack = inventory.getStack(index);
            if (isTarget(stack)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isLikelySlimefunMenu(HandledScreen<?> screen) {
        String title = screen.getTitle().getString().toLowerCase(java.util.Locale.ROOT);
        return title.contains("slimefun") || title.contains("粘液")
            || title.contains("guide") || title.contains("指南");
    }

    private static void succeed(MinecraftClient client) {
        reset();
        if (client.player != null && client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
        TrashCanDetectorClient.tip("已取出粘液菜单中的 BUG，菜单已关闭");
    }

    private static void fail(MinecraftClient client, String message) {
        reset();
        TrashCanDetectorClient.tip(message);
    }

    private static void reset() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        destinationSlot = -1;
        baselineTargetCount = 0;
        activeScreen = null;
    }
}

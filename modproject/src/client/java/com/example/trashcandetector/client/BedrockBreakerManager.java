package com.example.trashcandetector.client;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/** Uses the Slimefun bedrock device on nearby bedrock without replacing inventory items. */
final class BedrockBreakerManager {

    private static final String TARGET_ID = "minecraft:piston";
    private static final String TARGET_NAME = "便捷式破基岩装置";
    private static final int PLAYER_HANDLER_SLOTS = 46;
    private static final int OFFHAND_HANDLER_SLOT = 45;
    private static final int PREPARE_DELAY_TICKS = 2;
    private static final int USE_WAIT_TICKS = 8;
    private static final int ACTION_TIMEOUT_TICKS = 40;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int MAX_USE_ATTEMPTS = 2;
    private static final double MAX_INTERACTION_DISTANCE_SQUARED = 20.25D;

    private enum Phase {
        IDLE,
        PREPARE,
        USE,
        WAIT_USE,
        RETURN
    }

    private static Phase phase = Phase.IDLE;
    private static int phaseTicks;
    private static int scanCooldown;
    private static int useAttempts;
    private static int sourceInventoryIndex = -1;
    private static int temporaryHotbarSlot = -1;
    private static boolean movedToTemporaryHotbar;
    private static int originalSelectedSlot = -1;
    private static boolean selectionChanged;
    private static Hand activeHand;
    private static BlockPos currentTarget;
    private static final Queue<BlockPos> targets = new ArrayDeque<>();
    private static final Set<BlockPos> queuedTargets = new HashSet<>();

    private BedrockBreakerManager() {
    }

    static boolean isActive() {
        return phase != Phase.IDLE;
    }

    static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
            resetConnectionState();
            return;
        }

        if (SlimefunBugManager.isActive() || ShulkerOrganizer.isActive()
            || InfiniteFlightDeviceManager.isActive()) {
            return;
        }

        if (phase == Phase.IDLE) {
            if (!TrashCanDetectorConfigs.AUTO_BREAK_BEDROCK.getBooleanValue()) {
                return;
            }
            if (client.currentScreen != null || scanCooldown > 0) {
                if (scanCooldown > 0) scanCooldown--;
                return;
            }
            if (!begin(client, false)) {
                scanCooldown = SCAN_INTERVAL_TICKS;
            }
            return;
        }

        if (client.currentScreen != null || client.interactionManager == null
            || client.player.currentScreenHandler == null
            || client.player.currentScreenHandler.slots.size() != PLAYER_HANDLER_SLOTS
            || !client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            abort(client, "当前界面或鼠标物品不安全，基岩处理已停止");
            return;
        }

        switch (phase) {
            case PREPARE -> tickPrepare(client);
            case USE -> tickUse(client);
            case WAIT_USE -> tickWaitUse(client);
            case RETURN -> tickReturn(client);
            case IDLE -> {
            }
        }
    }

    static void requestManual() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!begin(client, true)) {
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
                TrashCanDetectorClient.tip("请先进入游戏服务器");
            }
        }
    }

    private static boolean begin(MinecraftClient client, boolean manual) {
        if (phase != Phase.IDLE || client.player == null || client.world == null
            || client.getNetworkHandler() == null || client.currentScreen != null
            || client.interactionManager == null || isOtherAutomationActive()) {
            if (manual && isOtherAutomationActive()) {
                TrashCanDetectorClient.tip("当前有其他自动化操作正在运行");
            }
            return false;
        }

        if (!findTargets(client)) {
            if (manual) TrashCanDetectorClient.tip("附近配置的 Y 层没有可处理的基岩");
            return false;
        }

        if (!prepareSource(client)) {
            if (manual) TrashCanDetectorClient.tip("未找到安全的便捷式破基岩装置或空快捷栏");
            targets.clear();
            queuedTargets.clear();
            return false;
        }

        phase = Phase.PREPARE;
        phaseTicks = 0;
        if (manual) {
            TrashCanDetectorClient.tip("开始处理附近基岩");
        }
        return true;
    }

    private static boolean isOtherAutomationActive() {
        return TrashPicker.isActive() || TrashCleaner.isActive() || ShulkerOrganizer.isActive()
            || SlimefunBugManager.isActive() || InfiniteFlightDeviceManager.isActive();
    }

    private static void tickPrepare(MinecraftClient client) {
        if (++phaseTicks < PREPARE_DELAY_TICKS) {
            return;
        }
        selectWorkingSlot(client);
        if (!hasUsableTool(client)) {
            if (phaseTicks >= ACTION_TIMEOUT_TICKS) {
                abort(client, "便捷式破基岩装置未能安全放到手部");
            }
            return;
        }
        phase = Phase.USE;
        phaseTicks = 0;
    }

    private static void tickUse(MinecraftClient client) {
        while (!targets.isEmpty() && !isBedrockTarget(client, targets.peek())) {
            queuedTargets.remove(targets.remove());
        }
        if (targets.isEmpty()) {
            phase = Phase.RETURN;
            phaseTicks = 0;
            return;
        }

        currentTarget = targets.peek();
        if (!isWithinInteractionRange(client, currentTarget)) {
            targets.remove();
            queuedTargets.remove(currentTarget);
            currentTarget = null;
            return;
        }

        if (!hasUsableTool(client)) {
            abort(client, "便捷式破基岩装置已耗尽或同步异常");
            return;
        }

        BlockHitResult hit = createHitResult(client, currentTarget);
        client.interactionManager.interactBlock(client.player, activeHand, hit);
        useAttempts++;
        phase = Phase.WAIT_USE;
        phaseTicks = 0;
    }

    private static void tickWaitUse(MinecraftClient client) {
        phaseTicks++;
        if (!isBedrockTarget(client, currentTarget)) {
            targets.remove();
            queuedTargets.remove(currentTarget);
            currentTarget = null;
            useAttempts = 0;
            phase = Phase.USE;
            phaseTicks = 0;
            return;
        }

        if (phaseTicks < USE_WAIT_TICKS) {
            return;
        }
        if (useAttempts < MAX_USE_ATTEMPTS) {
            phase = Phase.USE;
            phaseTicks = 0;
            return;
        }
        abort(client, "基岩使用道具后未发生方块同步变化，已停止");
    }

    private static void tickReturn(MinecraftClient client) {
        if (temporaryHotbarSlot < 0) {
            finish(client, "附近基岩处理完成");
            return;
        }

        if (!movedToTemporaryHotbar) {
            restoreSelection(client.player.getInventory());
            finish(client, "附近基岩处理完成，道具保持原槽位");
            return;
        }

        PlayerInventory inventory = client.player.getInventory();
        ItemStack temporary = inventory.getStack(temporaryHotbarSlot);
        ItemStack source = sourceInventoryIndex >= 0 ? inventory.getStack(sourceInventoryIndex) : ItemStack.EMPTY;
        if (isTarget(source) && temporary.isEmpty()) {
            restoreSelection(inventory);
            finish(client, "附近基岩处理完成，道具已放回原槽位");
            return;
        }
        if (source.isEmpty() && isTarget(temporary)) {
            clickInventorySlot(client, sourceInventorySlot(), temporaryHotbarSlot, SlotActionType.SWAP);
            phase = Phase.RETURN;
            phaseTicks = 0;
            return;
        }
        if (++phaseTicks >= ACTION_TIMEOUT_TICKS) {
            abort(client, "基岩处理完成，但道具未能安全放回原槽位");
        }
    }

    private static boolean findTargets(MinecraftClient client) {
        targets.clear();
        queuedTargets.clear();
        int radius = TrashCanDetectorConfigs.bedrockScanRadius();
        int targetY = TrashCanDetectorConfigs.bedrockTargetY();
        BlockPos origin = client.player.getBlockPos();
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                BlockPos pos = new BlockPos(x, targetY, z);
                if (isBedrockTarget(client, pos) && isWithinInteractionRange(client, pos)) {
                    targets.add(pos);
                    queuedTargets.add(pos);
                }
            }
        }
        return !targets.isEmpty();
    }

    private static boolean isBedrockTarget(MinecraftClient client, BlockPos pos) {
        return pos != null && client.world.getBlockState(pos).isOf(Blocks.BEDROCK);
    }

    private static boolean isWithinInteractionRange(MinecraftClient client, BlockPos pos) {
        return client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos))
            <= MAX_INTERACTION_DISTANCE_SQUARED;
    }

    private static BlockHitResult createHitResult(MinecraftClient client, BlockPos pos) {
        Vec3d eye = client.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;
        Direction side;
        if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
            side = dx >= 0 ? Direction.EAST : Direction.WEST;
        } else if (Math.abs(dy) >= Math.abs(dz)) {
            side = dy >= 0 ? Direction.UP : Direction.DOWN;
        } else {
            side = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return new BlockHitResult(center, side, pos, false);
    }

    private static boolean prepareSource(MinecraftClient client) {
        PlayerInventory inventory = client.player.getInventory();
        originalSelectedSlot = inventory.getSelectedSlot();
        selectionChanged = false;
        activeHand = null;
        sourceInventoryIndex = -1;
        temporaryHotbarSlot = -1;
        movedToTemporaryHotbar = false;

        if (isTarget(inventory.getStack(originalSelectedSlot))) {
            activeHand = Hand.MAIN_HAND;
            sourceInventoryIndex = originalSelectedSlot;
            temporaryHotbarSlot = originalSelectedSlot;
            return true;
        }
        if (isTarget(inventory.getStack(PlayerInventory.OFF_HAND_SLOT))) {
            activeHand = Hand.OFF_HAND;
            sourceInventoryIndex = PlayerInventory.OFF_HAND_SLOT;
            return true;
        }
        for (int index = 0; index < PlayerInventory.HOTBAR_SIZE; index++) {
            if (isTarget(inventory.getStack(index))) {
                activeHand = Hand.MAIN_HAND;
                sourceInventoryIndex = index;
                temporaryHotbarSlot = index;
                return true;
            }
        }
        int emptyHotbar = findEmptyHotbarSlot(inventory);
        if (emptyHotbar < 0) {
            return false;
        }
        for (int index = PlayerInventory.HOTBAR_SIZE; index < PlayerInventory.MAIN_SIZE; index++) {
            if (isTarget(inventory.getStack(index))) {
                sourceInventoryIndex = index;
                temporaryHotbarSlot = emptyHotbar;
                movedToTemporaryHotbar = true;
                activeHand = Hand.MAIN_HAND;
                clickInventorySlot(client, inventoryScreenSlot(index), emptyHotbar, SlotActionType.SWAP);
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsableTool(MinecraftClient client) {
        return activeHand == Hand.OFF_HAND
            ? isTarget(client.player.getOffHandStack())
            : activeHand == Hand.MAIN_HAND && isTarget(client.player.getMainHandStack());
    }

    private static void selectWorkingSlot(MinecraftClient client) {
        if (activeHand != Hand.MAIN_HAND || temporaryHotbarSlot < 0) {
            return;
        }
        PlayerInventory inventory = client.player.getInventory();
        if (inventory.getSelectedSlot() != temporaryHotbarSlot) {
            inventory.setSelectedSlot(temporaryHotbarSlot);
            selectionChanged = true;
        }
    }

    private static int findEmptyHotbarSlot(PlayerInventory inventory) {
        for (int index = 0; index < PlayerInventory.HOTBAR_SIZE; index++) {
            if (inventory.getStack(index).isEmpty()) return index;
        }
        return -1;
    }

    private static boolean isTarget(ItemStack stack) {
        return !stack.isEmpty()
            && TARGET_ID.equals(Registries.ITEM.getId(stack.getItem()).toString())
            && TARGET_NAME.equals(stack.getName().getString());
    }

    private static int sourceInventorySlot() {
        return inventoryScreenSlot(sourceInventoryIndex);
    }

    private static int inventoryScreenSlot(int inventoryIndex) {
        return inventoryIndex == PlayerInventory.OFF_HAND_SLOT ? OFFHAND_HANDLER_SLOT
            : inventoryIndex < PlayerInventory.HOTBAR_SIZE ? 36 + inventoryIndex : inventoryIndex;
    }

    private static void clickInventorySlot(MinecraftClient client, int slot, int button,
                                           SlotActionType action) {
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler != null && handler.slots.size() == PLAYER_HANDLER_SLOTS) {
            client.interactionManager.clickSlot(handler.syncId, slot, button, action, client.player);
        }
    }

    private static void restoreSelection(PlayerInventory inventory) {
        if (selectionChanged && originalSelectedSlot >= 0) {
            inventory.setSelectedSlot(originalSelectedSlot);
        }
        selectionChanged = false;
    }

    private static void finish(MinecraftClient client, String message) {
        restoreSelection(client.player.getInventory());
        resetOperation();
        if (message != null) TrashCanDetectorClient.tip(message);
    }

    private static void abort(MinecraftClient client, String message) {
        restoreSelection(client.player.getInventory());
        resetOperation();
        TrashCanDetectorClient.tip(message);
    }

    private static void resetOperation() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        useAttempts = 0;
        sourceInventoryIndex = -1;
        temporaryHotbarSlot = -1;
        movedToTemporaryHotbar = false;
        originalSelectedSlot = -1;
        activeHand = null;
        currentTarget = null;
        targets.clear();
        queuedTargets.clear();
    }

    private static void resetConnectionState() {
        resetOperation();
        scanCooldown = 0;
    }
}

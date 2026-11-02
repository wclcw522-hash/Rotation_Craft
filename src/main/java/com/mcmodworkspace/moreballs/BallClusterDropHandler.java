package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>球堆被扔出去之后，落地物拆成一颗颗球</b>（作者 2026-11-02：
 * 「球堆用任何方式扔出去之后，自动将掉落物的部分变成球」）。
 *
 * <h2>为什么要拆</h2>
 * <p>球堆的图标是「一个壳 + 里面那颗球的贴图」，扔在地上看着就是一颗球加个角标 ——
 * 但它实际上是一个装着 N 颗球的壳。作者要的是：<b>扔出去之后就当散球用</b>，
 * 谁捡到都是散球，不再有「地上躺着一个壳」这种形态。</p>
 *
 * <h2>⚠️ 为什么不在 {@code EntityJoinLevelEvent} 里直接拆</h2>
 * <p>那个事件会在原版遍历区块实体列表的过程中触发（典型是
 * {@code PersistentEntitySectionManager.updateChunkStatus}）。在里面
 * {@code addFreshEntity} / {@code discard} 别的实体 = 在遍历中途改列表，
 * 直接抛 {@code ConcurrentModificationException}，<b>整个世界的 tick 会崩</b>。
 * 这是本工程已经踩过的坑（见 AGENTS）。</p>
 *
 * <p>所以这里只**登记 UUID**，真正拆的动作挪到 {@link ServerTickEvent.Post} ——
 * 那时不在任何实体遍历里，改列表是安全的。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallClusterDropHandler {

    /** 待拆的掉落物 UUID（事件里只登记，tick 里再动手） */
    private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();

    private BallClusterDropHandler() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof ItemEntity item)) {
            return;
        }
        if (!(item.level() instanceof ServerLevel)) {
            return;
        }
        ItemStack stack = item.getItem();
        if (!BallClusterItem.isCluster(stack)) {
            return;
        }
        if (BallClusterItem.contents(stack).isEmpty()) {
            return;   // 空壳不拆（本来就该消失）
        }
        // ⚠️ 只在服务端登记；客户端那份由同步包刷回来
        PENDING.add(item.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        // 拷一份再遍历 —— 循环里会改 PENDING
        List<UUID> batch = new ArrayList<>(PENDING);
        PENDING.clear();

        for (UUID id : batch) {
            ServerLevel level = event.getServer().overworld();
            // 逐个维度找这个实体（掉落物可能在任何维度）
            for (ServerLevel world : event.getServer().getAllLevels()) {
                var entity = world.getEntity(id);
                if (entity instanceof ItemEntity item && item.isAlive()) {
                    scatter(world, item);
                    level = null;
                    break;
                }
            }
        }
    }

    /**
     * 把一个球堆掉落物拆成一颗颗散球。
     *
     * <p>用 {@link BallClusterItem#takeOne(ItemStack)} 逐颗取（它取的是**耐久最低**那颗，
     * 与右键取球同一口径），每颗生成一个 {@link ItemEntity} ——
     * 于是落地物看起来就是「一堆球散在地上」，捡起来也是散球。</p>
     */
    private static void scatter(ServerLevel level, ItemEntity shellEntity) {
        ItemStack shell = shellEntity.getItem().copy();
        if (!BallClusterItem.isCluster(shell) || BallClusterItem.contents(shell).isEmpty()) {
            return;
        }

        boolean any = false;
        // 上限保护：球堆最多 MAX_BALLS 颗，多给一点余量防止意外死循环
        for (int guard = 0; guard < BallClusterContents.MAX_BALLS + 1; guard++) {
            ItemStack one = BallClusterItem.takeOne(shell);
            if (one.isEmpty()) {
                break;
            }
            ItemEntity drop = new ItemEntity(level,
                    shellEntity.getX(), shellEntity.getY(), shellEntity.getZ(), one);
            // 给一点随机初速，散开而不是全部叠在一个点上
            drop.setDeltaMovement(
                    (level.getRandom().nextDouble() - 0.5D) * 0.12D,
                    0.10D + level.getRandom().nextDouble() * 0.06D,
                    (level.getRandom().nextDouble() - 0.5D) * 0.12D);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
            any = true;
        }

        if (any) {
            shellEntity.discard();   // 原来那个壳不要了
        }
    }
}

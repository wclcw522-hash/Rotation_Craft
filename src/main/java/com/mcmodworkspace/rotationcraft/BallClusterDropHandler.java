package com.mcmodworkspace.rotationcraft;

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
@EventBusSubscriber(modid = RotationCraft.MOD_ID)
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
     * <b>球堆拾取延迟</b>（tick）。40 = 2 秒。
     *
     * <p>作者 2026-11-02：「这两种情况现在还会因为物品状态变化，导致扔出后被立即回收」。</p>
     *
     * <p>根因：拆散出来的每颗球都是「用过的球」（带 {@code TOUGHNESS_LEFT} 组件），
     * 而 {@code BallPickupHandler} 会在 {@code ItemEntityPickupEvent.Pre} 里把它们
     * <b>自动套壳塞回玩家背包</b>。原来的 {@code setDefaultPickUpDelay()} 只有 10 tick，
     * 玩家还站在旁边 ⇒ 半秒后就被吸回去，看起来就是「扔出去立刻被回收」。
     * 给 2 秒，玩家能看见它们散在地上。</p>
     */
    private static final int DROP_PICKUP_DELAY = 40;

    /** 散开半径（格）。必须 > 原版 {@code ItemEntity} 的合并判定半径，否则会并成一个计数栈。 */
    private static final double SCATTER_RADIUS = 0.6D;

    /**
     * 把一个球堆掉落物拆成一颗颗散球。
     *
     * <p>用 {@link BallClusterItem#takeOne(ItemStack)} 逐颗取（它取的是**耐久最低**那颗，
     * 与右键取球同一口径），每颗生成一个 {@link ItemEntity}。</p>
     *
     * <h2>为什么不直接全生成在原坐标</h2>
     * <p>作者 2026-11-02：「背包内批量扔出还会导致一堆球变成一个」。</p>
     *
     * <p>原来所有 {@code ItemEntity} 都生成在壳的**同一个坐标**上，初速只有 ±0.06，
     * 而原版 {@code ItemEntity#mergeWithNeighbors} 会把「距离近 + 同物品 + 同组件 +
     * 可堆叠」的物品合并成一个计数栈 —— 同档位的球耐久恰好相同，于是 64 个实体会
     * 并回一个。所以这里**按黄金角把每颗摆到壳周围一圈**，
     * 相邻间距远大于合并半径。</p>
     */
    private static void scatter(ServerLevel level, ItemEntity shellEntity) {
        ItemStack shell = shellEntity.getItem().copy();
        if (!BallClusterItem.isCluster(shell) || BallClusterItem.contents(shell).isEmpty()) {
            return;
        }

        boolean any = false;
        int index = 0;
        // 上限保护：球堆最多 MAX_BALLS 颗，多给一点余量防止意外死循环
        for (int guard = 0; guard < BallClusterContents.MAX_BALLS + 1; guard++) {
            ItemStack one = BallClusterItem.takeOne(shell);
            if (one.isEmpty()) {
                break;
            }

            // 黄金角（≈137.5°）铺开，任意相邻两颗都拉得开，配合 SCATTER_RADIUS 避免合并
            double angle = index * 2.39996D;
            double dx = Math.cos(angle) * SCATTER_RADIUS;
            double dz = Math.sin(angle) * SCATTER_RADIUS;

            ItemEntity drop = new ItemEntity(level,
                    shellEntity.getX() + dx,
                    shellEntity.getY() + 0.15D,
                    shellEntity.getZ() + dz,
                    one);
            // 沿各自的方向轻轻弹出去，落点更自然（也进一步防止重合）
            drop.setDeltaMovement(dx * 0.12D, 0.12D + level.getRandom().nextDouble() * 0.06D, dz * 0.12D);
            // 2 秒内不许被拾取 —— 否则会被 BallPickupHandler 立即套壳收走
            drop.setPickUpDelay(DROP_PICKUP_DELAY);
            level.addFreshEntity(drop);
            any = true;
            index++;
        }

        if (any) {
            shellEntity.discard();   // 原来那个壳不要了
        }
    }
}

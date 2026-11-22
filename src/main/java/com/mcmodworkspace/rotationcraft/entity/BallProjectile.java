package com.mcmodworkspace.rotationcraft.entity;

import com.mcmodworkspace.rotationcraft.BallItem;
import com.mcmodworkspace.rotationcraft.ModEntities;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * <b>「球」的实体</b> —— 继承 {@link BallPhysicsCore}，只保留球本身特有的行为。
 *
 * <h2>为什么有 BallPhysicsCore 这一层（作者 2026-10-20 方案 A）</h2>
 * <p>流星锤的锤头要「和球的基本功能一模一样」，但作者明确要求
 * <b>严禁写成同一种实体</b>。</p>
 *
 * <p>直接继承 {@code BallProjectile} 是不行的 —— 那样
 * {@code 锤头 instanceof BallProjectile == true}，全模组几十处
 * 「`instanceof BallProjectile` 就当球处理」的判据都会命中它
 * （接球 / 顺手捡球 / 幻翼抓球 / 球互撞 / 球上限清理 …… 堵不完）。</p>
 *
 * <p>所以把两者共用的物理与词条逻辑抽到 {@code BallPhysicsCore}，两边做<b>兄弟</b>：</p>
 * <pre>
 * ThrowableItemProjectile
 *   └─ BallPhysicsCore      ← 物理 / 词条 / 耐久 / 弹跳 / 静止 / 尖啸
 *        ├─ BallProjectile  ← 球（本类）
 *        └─ FlailHeadEntity ← 流星锤锤头
 * </pre>
 * <p>{@code instanceof BallProjectile} 对锤头恒为 false，反之亦然。</p>
 */
public class BallProjectile extends BallPhysicsCore {
    /**
     * 物品变了 ⇒ 碰撞箱要重算（作者 2026-10-29）。
     *
     * <p>碰撞箱现在是 {@code 0.5 格 × entityScale}，而 {@code entityScale} 来自当前物品
     * 的行为表。实体的 {@code dimensions} 字段只在构造和 {@code refreshDimensions()}
     * 时更新，所以换球（金球 ↔ 普通球）必须主动刷一次，否则碰撞箱会停在上一个球的尺寸。</p>
     */
    /**
     * 构造是否已经跑完（作者 2026-11-01 报「扔球就崩溃」）。
     *
     * <p>父类 {@code ThrowableItemProjectile} 的构造器里就会调 {@code this.setItem(...)}，
     * 而那是**虚方法分派** —— 下面那个覆写会在子类还没构造完时被执行。
     * 那一刻实体尺寸/追踪器相关的状态尚未就绪，{@code refreshDimensions()} 会去碰它。</p>
     *
     * <p>⚠️ 不能用 {@code this.level() != null} 当判据：{@code Entity(type, level)} 是更早的父层，
     * 到这一步 level 早就挂上了，判不出来。</p>
     */
    private boolean fullyConstructed;

    @Override
    public void setItem(ItemStack stack) {
        super.setItem(stack);
        if (this.fullyConstructed) {
            this.refreshDimensions();
        }
    }

    public BallProjectile(EntityType<? extends BallProjectile> type, Level level) {
        super(type, level);
        this.fullyConstructed = true;
    }

    /** 球间弹性碰撞 —— 只有球才做 */
    @Override
    protected void collideBallsTick() {
        this.collideWithNearbyBalls();
    }

    /** 球数上限清理 —— 只有球才做 */
    @Override
    protected void enforceCapTick() {
        if (this.level() instanceof net.minecraft.server.level.ServerLevel capLevel) {
            long now = capLevel.getGameTime();
            if (now % BALL_CAP_CHECK_INTERVAL == 0L && now != ballCapLastCheckTick) {
                ballCapLastCheckTick = now;
                enforceBallCap(capLevel);
            }
        }
    }
    // ===== 球特有的回归逻辑（作者 2026-10-20 方案 A 第②步：从 Core 挪回来）=====
    //
    // 这三段原本在 `BallPhysicsCore.ballTickPost()` 里，现在抽成钩子：
    // Core 留空实现，球在这里覆写。锤头没有「飞回主人」这回事（它对应的是**收线**），
    // 所以基类那份空实现对它正好是「什么都不做」。

    /** 回归途中、父类 tick 之前的「恼鬼三件套」压设 */
    @Override
    protected void homingPhysicsPre() {
        if (!this.homingBack) {
            return;
        }
        this.noPhysics = true;
        // ⚠️ 只在值真的变化时调用 —— setNoGravity 是同步数据，
        //    值没变也会触发实体同步，每 tick 调就是「卡一下闪现一下」。
        if (!this.isNoGravity()) {
            this.setNoGravity(true);
        }
    }

    /** 回归途中、父类 tick 之后的「恼鬼三件套」压设 */
    @Override
    protected void homingPhysicsPost() {
        if (!this.homingBack) {
            return;
        }
        if (!this.noPhysics) {
            this.noPhysics = true;
        }
        if (!this.isNoGravity()) {
            this.setNoGravity(true);
        }
    }

    /**
     * 回归推进 + 超时兜底。
     *
     * <p>⚠️⚠️ <b>超时兜底</b>（作者 2026-10-16 报的「偶尔出现悬在空中的球」）：
     * 回归期间球带着「恼鬼三件套」，而 `setSettled()` 在 `isNoGravity()` 时会拒绝进入静止态 ——
     * 只要回归一直不结束，球就永远悬空飞、永不落定、也不消失。
     * 所以这里补一条时间上限：飞够了就放弃回归、还原三件套，球自然掉下来。</p>
     *
     * @return true 表示「这一 tick 到此为止」
     */
    @Override
    protected boolean tickHomingReturn() {
        if (!this.homingBack) {
            return false;
        }
        boolean giveUp = this.homingTicks > RETURN_GIVE_UP_TICKS;
        if (!giveUp && this.homingToOwner()) {
            return true;
        }
        // 主人已不在（下线 / 换维度 / 已死），或回归超时 ——
        // 必须把「恼鬼三件套」和记账状态一起还原，否则球会一直穿墙飘着
        this.homingBack = false;
        this.homingDelayTicks = 0;   // 放弃回归 → 下次落定重新等 0.5 秒
        this.noPhysics = false;
        this.setNoGravity(false);
        this.wasInsideBlock = false;
        return false;
    }
    public BallProjectile(Level level, LivingEntity shooter, ItemStack stack) {
        super(level, shooter, stack);
        // 弩在装填/发射时会往被消耗的弹药上打「intangible_projectile」（不可回收）标记，
        // 原版箭在 AbstractArrow 构造里主动移除了它 —— 我们的球是可以被捡回来的，
        // 不清掉的话捡回来的球会显示「不可回收」，而且因为多了一个数据组件而叠不进普通堆。
        this.fullyConstructed = true;   // 先置位，下面这次 setItem 才会真的刷新碰撞箱
        ItemStack held = this.getItem();
        BallItem.stripIntangible(held);
        this.setItem(held);
    }
}

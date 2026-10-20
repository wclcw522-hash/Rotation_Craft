package com.mcmodworkspace.moreballs.entity;

import com.mcmodworkspace.moreballs.BallItem;
import com.mcmodworkspace.moreballs.ModEntities;

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

    public BallProjectile(EntityType<? extends BallProjectile> type, Level level) {
        super(type, level);
    }

    public BallProjectile(Level level, LivingEntity shooter, ItemStack stack) {
        super(level, shooter, stack);
        // 弩在装填/发射时会往被消耗的弹药上打「intangible_projectile」（不可回收）标记，
        // 原版箭在 AbstractArrow 构造里主动移除了它 —— 我们的球是可以被捡回来的，
        // 不清掉的话捡回来的球会显示「不可回收」，而且因为多了一个数据组件而叠不进普通堆。
        ItemStack held = this.getItem();
        BallItem.stripIntangible(held);
        this.setItem(held);
    }
}
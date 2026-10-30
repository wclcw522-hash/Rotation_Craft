package com.mcmodworkspace.moreballs.entity;

import com.mcmodworkspace.moreballs.BallBehavior;
import com.mcmodworkspace.moreballs.BallClusterTier;
import com.mcmodworkspace.moreballs.BallExtraTraits;
import com.mcmodworkspace.moreballs.FlailItem;
import com.mcmodworkspace.moreballs.ModComponents;
import com.mcmodworkspace.moreballs.ModItems;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * <b>流星锤的锤头</b>（作者 2026-10-19 规格）。
 *
 * <h2>为什么不继承 {@link BallProjectile}（作者反复强调）</h2>
 * <p>锤头与球「伤害逻辑与特性逻辑相同」，但<b>是两种不同的实体</b>：</p>
 * <ul>
 *   <li>球可以被捡起、可以被怪接住；<b>锤头不行</b></li>
 *   <li>锤头的生命周期绑在掷出者与那条线上，<b>不能单独存在</b></li>
 * </ul>
 * <p>继承会把这些行为一起带过来。所以这里是独立类，只<b>复制</b>「伤害公式 / 耐久扣除 /
 * 词条判定顺序」三段逻辑（见 {@code docs/流星锤-通读记录.md} 的行号对照）。</p>
 *
 * <h2>状态机</h2>
 * <pre>
 * SPIN（手持蓄力，绕玩家转）──松手──> FLYING ──线 30 格 / 手动回收──> RETURNING ──到达──> 收回
 * </pre>
 *
 * <p>⚠️ <b>SPIN 状态的锤头位置不由物理驱动</b>，每 tick 按公式算出来再 {@code setPos}；
 * 它没有速度，所以 {@code impactDamage()} 那套「按速度缩放」在 SPIN 下不适用，
 * 直接用基础伤害（作者的「依照基础伤害造成伤害」）。</p>
 */
public class FlailHeadEntity extends BallProjectile {

    /** 当前阶段 */
    public enum Stage {
        /** 手持蓄力，绕掷出者做圆周运动 */
        SPIN,
        /** 已脱手，按球的物理飞行 */
        FLYING,
        /** 被回收中，朝掷出者飞回 */
        RETURNING
    }

    private static final EntityDataAccessor<Byte> STAGE =
            SynchedEntityData.defineId(FlailHeadEntity.class, EntityDataSerializers.BYTE);
    /** 当前线长（格 × 100 存成 int，避免浮点同步精度问题） */
    private static final EntityDataAccessor<Integer> LINE_LENGTH =
            SynchedEntityData.defineId(FlailHeadEntity.class, EntityDataSerializers.INT);
    /** 首次回收后置 true —— 除弹性与重量外所有词条停效、且不再造成伤害 */
    private static final EntityDataAccessor<Boolean> TRAITS_DISABLED =
            SynchedEntityData.defineId(FlailHeadEntity.class, EntityDataSerializers.BOOLEAN);
    /** 回收时玩家是否按着右键 —— 松开就停（作者 2026-10-19 指定） */
    private static final EntityDataAccessor<Boolean> RETRIEVING =
            SynchedEntityData.defineId(FlailHeadEntity.class, EntityDataSerializers.BOOLEAN);
    /** 线另一端（掷出者主手）的世界坐标 —— 渲染拴绳用 */
    private static final EntityDataAccessor<Float> ANCHOR_X =
            SynchedEntityData.defineId(FlailHeadEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ANCHOR_Y =
            SynchedEntityData.defineId(FlailHeadEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ANCHOR_Z =
            SynchedEntityData.defineId(FlailHeadEntity.class, EntityDataSerializers.FLOAT);

    /** 线长上限（格），作者指定 30 */
    public static final double MAX_LINE = FlailItem.MAX_LINE_LENGTH;

    /** 环绕半径 / 高度，作者指定 1.5 / 1.75 */
    public static final double SPIN_RADIUS = FlailItem.SPIN_RADIUS;
    public static final double SPIN_HEIGHT = FlailItem.SPIN_HEIGHT;

    /** 环绕角速度：2π / 20 刻 */
    private static final float SPIN_STEP = (float) (Math.PI * 2.0D / FlailItem.SPIN_PERIOD_TICKS);

    /** 出手宽限期（照搬 BallProjectile.SPAWN_GRACE_TICKS） */
    private static final int SPAWN_GRACE_TICKS = 2;

    /** 回收格每秒 → 格每刻的换算 */
    private static final double TICKS_PER_SECOND = 20.0D;

    /** 原版默认击退强度 —— 作者要的 0.5 倍 / 2 倍都基于它 */
    private static final double BASE_KNOCKBACK = 0.4D;
    /** 回收遇阻时的反弹衰减 */
    private static final double RECOVER_BOUNCE = 0.55D;
    /** 离玩家多近算到家 */
    /**
     * 离主手多近算「到家」。
     *
     * <p>⚠️ 原来写的 1.6 —— 作者 2026-10-19 报「回收时瞬间就完成了」。
     * 原因很直接：按住右键那一刻锤头还在**环绕**（离主手 1.5 格半径），
     * 距离本来就小于 1.6 ⇒ 第一个 tick 就判到家 ⇒ 立刻 {@code finishReturn}。
     * 改成 0.7，必须真的被拉到手上才算。</p>
     */
    private static final double ARRIVE_DISTANCE = 0.7D;

    /**
     * 移动回收时，玩家水平位移传递给锤头的比例（作者 2026-10-30 指定 70%）。
     *
     * <p>锤头自己每 tick 只走 {@code recoverSpeed / 20}（铁球 ≈ 0.21 格），比玩家疾跑
     * （0.28 格/tick）慢 ⇒ 原地收线时人一跑就把绳子拉长、永远追不上。
     * 按这个比例把玩家的位移分给锤头，它就能被「拖着走」，边跑边收得回来。</p>
     *
     * <p>拖拽之后仍夹在 {@link #MAX_LINE} 以内，所以绳子不会变长。</p>
     */
    private static final double RETURN_DRAG_RATIO = 0.7D;

    /**
     * 锤头最长寿命（刻）= 5 分钟。
     *
     * <p>作者 2026-10-19：存档里有个锤头扔出去再也没回来，导致手里每把流星锤都
     * 被判成「已扔出」，一直只显示一根绳、新的流星锤也立刻变纯线。
     * 超过这个寿命的锤头视为孤儿，自己把物品还回去并从世界移除。</p>
     */
    public static final int MAX_HEAD_LIFE = 6000;
    /** 到线长上限时给的回弹速度 */
    private static final double SNAP_BACK_SPEED = 0.35D;

    /**
     * 绳子绷紧时朝掷出者施加的张力（每 tick 的速度增量，格/刻）。
     *
     * <p>只影响「越过线长之后」的表现：值太大会把锤头暴力拽回来像弹簧，
     * 太小则绷紧感不足。0.06 配合径向分量被完全吃掉，观感是锤头贴着球面绕着人转。</p>
     */
    /**
     * 两次「撞方块扣耐久」之间的**最小间隔**（tick）。
     *
     * <p>锤头卡在方块里时 onHit 每 tick 都触发，只靠「同一 tick 去重」拦不住 ——
     * 铁球 99 耐 5 秒就会被磨光。4 tick = 0.2 秒，保留碰撞掉耐久的语义又不至于瞬爆。</p>
     */
    private static final int TOUGHNESS_MIN_INTERVAL_TICKS = 4;

    private static final double ROPE_TENSION = 0.06D;

    /** 环绕角度（弧度） */
    private float spinAngle;

    /** 掷出时占用的背包格 —— 回收必回这一格（作者指定） */
    private int originSlot = -1;

    /** 记下的出手速度，供「按速度缩放伤害」用（照搬 BallProjectile.launchSpeed） */
    private double launchSpeed;
    /** 本次 FLYING 是否已为「到线长上限」扣过耐久（避免每 tick 反复扣） */
    private boolean lineCapConsumed;
    /** 上一次扣耐久所在的 tickCount —— 同 tick 重复碰撞不重复扣 */
    private int flailLastDamageTick = Integer.MIN_VALUE;

    public FlailHeadEntity(EntityType<? extends FlailHeadEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);   // SPIN 阶段不受重力；FLYING 时再放开
    }

    // ===== 同步与存档 =====

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STAGE, (byte) Stage.SPIN.ordinal());
        builder.define(LINE_LENGTH, 0);
        builder.define(TRAITS_DISABLED, false);
        builder.define(RETRIEVING, false);
        builder.define(ANCHOR_X, 0.0F);
        builder.define(ANCHOR_Y, 0.0F);
        builder.define(ANCHOR_Z, 0.0F);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putByte("FlailStage", (byte) stage().ordinal());
        output.putInt("FlailLine", this.getEntityData().get(LINE_LENGTH));
        output.putBoolean("FlailTraitsOff", this.getEntityData().get(TRAITS_DISABLED));
        output.putInt("FlailOriginSlot", this.originSlot);
        output.putDouble("FlailLaunchSpeed", this.launchSpeed);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.getEntityData().set(STAGE, input.getByteOr("FlailStage", (byte) 0));
        this.getEntityData().set(LINE_LENGTH, input.getIntOr("FlailLine", 0));
        this.getEntityData().set(TRAITS_DISABLED, input.getBooleanOr("FlailTraitsOff", false));
        this.originSlot = input.getIntOr("FlailOriginSlot", -1);
        this.launchSpeed = input.getDoubleOr("FlailLaunchSpeed", 0.0D);
    }

    public Stage stage() {
        return Stage.values()[this.getEntityData().get(STAGE)];
    }

    public void setStage(Stage value) {
        this.getEntityData().set(STAGE, (byte) value.ordinal());
    }

    public double lineLength() {
        return this.getEntityData().get(LINE_LENGTH) / 100.0D;
    }

    private void setLineLength(double value) {
        this.getEntityData().set(LINE_LENGTH, (int) Math.round(value * 100.0D));
    }

    public boolean traitsDisabled() {
        return this.getEntityData().get(TRAITS_DISABLED);
    }

    /** 回收开关 —— 玩家按住右键 true、松开 false（作者 2026-10-19：按住才回收） */
    public void setRetrieving(boolean value) {
        this.getEntityData().set(RETRIEVING, value);
    }

    public boolean isRetrieving() {
        return this.getEntityData().get(RETRIEVING);
    }

    /** 线另一端坐标（渲染拴绳用） */
    public Vec3 anchor() {
        return new Vec3(this.getEntityData().get(ANCHOR_X),
                this.getEntityData().get(ANCHOR_Y),
                this.getEntityData().get(ANCHOR_Z));
    }

    private void storeAnchor(Vec3 v) {
        this.getEntityData().set(ANCHOR_X, (float) v.x);
        this.getEntityData().set(ANCHOR_Y, (float) v.y);
        this.getEntityData().set(ANCHOR_Z, (float) v.z);
    }

    /** 掷出者主手坐标 —— 拴绳起点 */
    public static Vec3 handAnchor(net.minecraft.world.entity.LivingEntity owner) {
        Vec3 look = owner.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-6D) { flat = new Vec3(0.0D, 0.0D, 1.0D); }
        flat = flat.normalize();
        Vec3 right = new Vec3(-flat.z, 0.0D, flat.x);
        return owner.position().add(right.scale(0.35D)).add(0.0D, owner.getBbHeight() * 0.75D, 0.0D);
    }

    public void setOriginSlot(int slot) {
        this.originSlot = slot;
    }

    public int originSlot() {
        return this.originSlot;
    }

    /** 这把流星锤的物品栈（含内嵌球与耐久组件） */
    /**
     * <b>对父类（{@link BallProjectile}）暴露的物品栈 = 内嵌的那颗球</b>。
     *
     * <p>⚠️⚠️ 这是整个继承方案的关键一环（作者 2026-10-20 报「扔出去直接碎、
     * 词条无反应、耐久不消耗」）。</p>
     *
     * <p>球的<b>全部</b>逻辑都通过 `this.getItem()` 取 profile：
     * `isTough()` / `hasFlag()` / `bounce()` / `consumeDurability()` …… 而锤头实体
     * 自己携带的物品栈原本是**流星锤**（`rotation_craft:flail`）。
     * `BallBehavior.profileFor(流星锤)` 查不到表 ⇒ 落到 `DEFAULT` ⇒
     * `toughness = NOT_TOUGH = 0` ⇒ `isTough()` 恒为 false ⇒
     * 撞什么都是「不坚固的球」⇒ 立刻 `burst()` 碎掉、词条一条都不触发、耐久永不扣。</p>
     *
     * <p>所以这里让父类看到**内嵌球**。锤头自己的物品栈另外用
     * {@link #flailStack()} 取（渲染、归还、扣耐久都走那个）。</p>
     */
    public ItemStack getItem() {
        ItemStack flail = this.flailStack();
        ItemStack inner = FlailItem.ballOf(flail);
        return inner.isEmpty() ? super.getItem() : inner;
    }
    /**
     * <b>锤头自己携带的物品栈 = 那把流星锤</b>（含内嵌球、耐久组件、三个状态标记）。
     *
     * <p>⚠️⚠️ <b>必须读 {@code super.getItem()}，不能读 {@code this.getItem()}</b>
     * （作者 2026-10-23 实测崩服：`StackOverflowError`）。</p>
     *
     * <p>原因：本类覆写了 {@link #getItem()}，让它对父类暴露**内嵌球**（见那个方法的说明）。
     * 如果这里再调 `this.getItem()`，就成了</p>
     * <pre>
     * getItem() ──► flailStack() ──► getItem() ──► …   ← 无限递归
     * </pre>
     * <p>`super.getItem()` 拿到的是 {@code ThrowableItemProjectile} 真正存着的那个栈，
     * 也就是流星锤本身。渲染、归还、扣耐久、状态标记全走这里。</p>
     */
    public ItemStack flailStack() {
        return super.getItem();
    }

    /** 这把锤头的数值档案（内嵌球推导；没有内嵌球时退回默认档案） */
    public BallBehavior.BallProfile profile() {
        return FlailItem.profileOf(this.flailStack());
    }

    // ===== 拾取与碰撞：作者要求锤头绝不能被捡 / 被怪接 =====

    /**
     * 永远不可被「拾取」—— 原版中键复制、漏斗、以及本模组的接球都看这个。
     *
     * <p>⚠️ 这一条加上 {@code canBeCollidedWith()} 返回 false，配合「它本来就不是
     * {@code ItemEntity}、也不是 {@code BallProjectile}」，四层一起保证
     * 「锤头不能被捡、不能被怪接」——见通读记录里的类型隔离结论。</p>
     */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith(@org.jspecify.annotations.Nullable Entity other) {
        return false;
    }

    /** 球一律不显示名字标签（与 {@code BallProjectile} 同一口径）；锤头同理 */
    @Override
    public boolean shouldShowName() {
        return false;
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.FLAIL.get();
    }

    // ===== 主循环 =====

    @Override
    /**
     * 主 tick —— <b>FLYING 阶段完全交给球</b>（作者 2026-10-20：锤头一切照搬球）。
     *
     * <p>球的 `tick()` 里依次跑：`ballTickPre()`（上限/回归/票据）→ `super.tick()`
     * （物理 + 碰撞检测）→ `ballTickPost()`（照明 / 透镜 / 磁吸 / 智慧 / 熔融 / 静止 / 回归）。
     * 继承后**一个都不用搬**，全自动。</p>
     *
     * <p>SPIN 与 RETURNING 两段是锤头独有的，位置不由物理决定，所以走自己的状态机。</p>
     */
    // ⚠️ 这里**故意不写 @Override**：javac 会报「Override 不是可重复的批注接口」
    //    （签名与 BallProjectile.tick() 完全一致，报错信息有误导性）。去掉不影响覆盖行为。
    public void tick() {
        // 主人没了 / 超时：收场（任何阶段都要判）
        if (!this.level().isClientSide() && !ownerAlive()) {
            returnFlailAndDiscard();
            return;
        }
        if (!this.level().isClientSide() && this.tickCount > MAX_HEAD_LIFE) {
            returnFlailAndDiscard();
            return;
        }

        if (this.getOwner() instanceof net.minecraft.world.entity.LivingEntity livingA) {
            this.storeAnchor(handAnchor(livingA));
        }

        // ⚠️⚠️ **Entity 的基础 tick 必须跑**（作者 2026-10-23 报「转的时候抽搐」）。
        //
        //   SPIN / RETURNING 两段位置由公式算出，我原来只调了自己的 tickSpin / tickReturning，
        //   完全跳过了 `super.tick()` —— 而 `Entity.tick()` 里有 `xo/yo/zo`（上一刻坐标）的更新，
        //   客户端插值靠它算「这一帧该画在哪」。不更新就一直拿旧值插值，画面表现就是抽搐。
        //
        //   顺序也重要：先 `super.tick()` 再 `setPos` 覆盖位置 —— 反过来会被物理改掉。
        // ⚠️⚠️ **SPIN / RETURNING 两段必须关掉方块碰撞**（作者 2026-10-30：
        //   「收到脚边时会变得非常费劲，还会卡在方块上」）。
        //
        //   这两段的位置本来是每 tick 用公式/setPos 直接摆放的，但 super.tick() 里
        //   跑的是球的完整物理 —— 会先做一遍方块碰撞解算把锤头推到方块表面，
        //   再由 tickReturning 的 setPos 拽回来，两者每 tick 对着拉一次：
        //   表现就是「越靠近人越吃力」+「贴着方块抖动/卡住」。
        //
        //   noPhysics 只跳过方块碰撞，xo/yo/zo 的更新照旧（客户端插值不受影响）。
        //   FLYING 阶段必须恢复物理 —— 那是真正的投掷弹道，要撞墙要打生物。
        switch (this.stage()) {
            case SPIN -> {
                this.noPhysics = true;
                super.tick();
                tickSpin();
            }
            case RETURNING -> {
                this.noPhysics = true;
                super.tick();
                tickReturning();
            }
            case FLYING -> {
                this.noPhysics = false;
                super.tick();      // 球的完整 tick（含 BallRoll 的滚动阻力）
                tickFlying();

                // ===== 掷出者走近 ⇒ 自动开始回收（作者 2026-10-30 指定）=====
                //
                //   不必按住右键：人走到锤头旁边（碰撞箱相交）就自动收线。
                //   这和「按住右键回收」不冲突 —— 那条走 use 系统的 setRetrieving，
                //   这条只是把 RETURNING 状态踢起来，之后 tickReturning 照常拉。
                if (this.getOwner() instanceof LivingEntity autoOwner
                        && this.getBoundingBox().inflate(0.2D)
                                .intersects(autoOwner.getBoundingBox())) {
                    this.beginReturning(autoOwner);   // 会扣 1 耐久，与手动回收一致
                    this.setRetrieving(true);
                }
            }
        }
    }


    private boolean ownerAlive() {
        Entity owner = this.getOwner();
        return owner instanceof LivingEntity living && living.isAlive() && !living.isRemoved();
    }

    /** SPIN：位置完全由公式算出来，不用物理 */
    private void tickSpin() {
        Entity owner = this.getOwner();
        if (!(owner instanceof LivingEntity living)) {
            return;
        }
        // 作者 2026-10-19：「旋转方向反过来」⇒ 从 +（逆时针）改成 -（顺时针）
        this.spinAngle -= SPIN_STEP;
        if (this.spinAngle < -(float) (Math.PI * 2.0D)) {
            this.spinAngle += (float) (Math.PI * 2.0D);
        }

        Vec3 pos = spinPosition(living, this.spinAngle);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(Vec3.ZERO);
        this.setLineLength(distanceToOwner(living));

        this.getEntityData().set(LINE_LENGTH, (int) Math.round(this.lineLength() * 100.0D));

        // ===== 蓄力期间**手动**结算实体碰撞（作者 2026-10-30：「还是没有生物碰撞判定」）=====
        //
        // 为什么 onHit 里那段 SPIN 分支从来没生效：
        //   · SPIN 的位置是上面的 `setPos(...)` 直接摆出来的；
        //   · 而 `onHit` 只在 `Entity.move()` 内部检测碰撞时才触发；
        //   · 再加上 SPIN 阶段 `noPhysics = true`，`move()` 连方块都不碰。
        // 三者叠加 ⇒ 那条分支是**死代码**，蓄力扫到怪永远不掉血。
        //
        // 所以在这里主动做一次碰撞查询：碰撞箱相交的活体（掷出者除外）逐个结算。
        if (!this.level().isClientSide()) {
            for (net.minecraft.world.entity.LivingEntity target : this.level().getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class,
                    this.getBoundingBox().inflate(0.05D),
                    e -> e != living
                            && e.isAlive()
                            && !e.isSpectator()
                            && !e.isInvulnerable())) {
                this.onHitEntity(new net.minecraft.world.phys.EntityHitResult(target));
            }
        }
    }

    /**
     * 环绕位置 —— <b>紧贴碰撞箱右侧、平行于矢状面</b>（作者 2026-10-19 明确）。
     *
     * <p>矢状面由「前后轴 + 上下轴」张成，法线是玩家的<b>右向量</b>。
     * 所以圆周落在这个竖直面内：</p>
     * <pre>
     * 圆心 = 玩家位置 + 右向量 × 0.35（碰撞箱半宽），高度 = 脚下 + 1.75
     * 锤头 = 圆心 + 前向量 × R·cos(θ) + 上向量 × R·sin(θ)
     * </pre>
     * <p>⚠️ 必须用 {@code getLookAngle()} 取前向 —— 用世界坐标轴的话玩家转身时圆不跟着转。</p>
     */
    private Vec3 spinPosition(LivingEntity owner, float angle) {
        Vec3 look = owner.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z);
        if (flatLook.lengthSqr() < 1.0E-6) {
            flatLook = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flatLook = flatLook.normalize();
        Vec3 right = new Vec3(-flatLook.z, 0.0D, flatLook.x);   // 前向 × UP
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);

        Vec3 center = owner.position()
                .add(right.scale(owner.getBbWidth() * 0.5D))
                .add(0.0D, SPIN_HEIGHT, 0.0D);

        return center
                .add(flatLook.scale(SPIN_RADIUS * Math.cos(angle)))
                .add(up.scale(SPIN_RADIUS * Math.sin(angle)));
    }

    /**
     * 把一个点从方块内部抬出来 —— 修「锤头卡进地里无法回收」（作者 2026-10-19 报）。
     *
     * <p>只在服务端做（客户端靠同步）。命中方块就往上找最多 4 格，找到空位为止。</p>
     */
    private Vec3 liftOutOfBlocks(Vec3 pos) {
        if (this.level().isClientSide()) {
            return pos;
        }
        net.minecraft.core.BlockPos base = net.minecraft.core.BlockPos.containing(pos);
        if (this.level().getBlockState(base).getCollisionShape(this.level(), base).isEmpty()) {
            return pos;
        }
        for (int dy = 1; dy <= 4; dy++) {
            net.minecraft.core.BlockPos up = base.above(dy);
            if (this.level().getBlockState(up).getCollisionShape(this.level(), up).isEmpty()) {
                return new Vec3(pos.x, up.getY() + 0.05D, pos.z);
            }
        }
        return pos;
    }
    /** 松手：以球的物理飞出（作者：与球逻辑相同的运动方式） */
    /**
     * 松手：<b>无论锤头此刻在圆周的哪个位置，先瞬移到「最高点」，再按准心方向飞出</b>
     * （作者 2026-10-19 指定）。
     *
     * <p>「最高点」= 圆心 + UP × 半径，也就是圆周的正上方 —— 出手位置固定，
     * 玩家不用赌松手时机。</p>
     *
     * @param velocityMultiplier 蓄力倍率（来自 {@code BallCharge.velocityMultiplier}）
     */
    public void launch(net.minecraft.world.entity.LivingEntity shooter, float velocityMultiplier) {
        this.setStage(Stage.FLYING);
        this.setNoGravity(false);

        // ===== ① 出手点：从**视线附近**甩出去（作者 2026-10-30）=====
        //
        //   原来是 `spinTop(shooter)` —— 那是「蓄力环绕的最高点」，等于
        //   `脚底 + SPIN_HEIGHT(1.75) + SPIN_RADIUS(1.5)` = **3.25 格**，
        //   比玩家视线（1.62）高出 1.6 格。贴脸打怪时它直接从头顶飞过去，
        //   轻球（初速度大）尤其明显。
        //
        //   改成「眼睛高度再往下 0.1、身前 0.35」—— 与第一人称的出手位置一致，
        //   近距离也不会越过目标。蓄力环绕仍用 spinTop，那是另一回事。
        //
        //   出手前同样要验一次实心：贴着墙时前方 0.35 格可能在方块里，
        //   那样锤头从第一 tick 就嵌住。
        Vec3 top = launchOrigin(shooter);
        if (!this.isFreeAt(top)) {
            // 退到掷出者眼睛高度 —— 那是玩家视野所在，一定不是实心的
            Vec3 eye = shooter.getEyePosition();
            top = this.isFreeAt(eye) ? eye : shooter.position().add(0.0D, shooter.getBbHeight() * 0.5D, 0.0D);
        }
        this.setPos(top.x, top.y, top.z);

        // ② 按球的物理飞出：物理速度 × 蓄力倍率
        BallBehavior.BallProfile profile = this.profile();
        float speed = (float) (profile.physicalVelocity() * Math.max(0.1F, velocityMultiplier));
        this.launchSpeed = speed;

        // ⚠️⚠️ **每次投掷都必须把线长标记复位**（这是「线长限制 / 弹回等一大堆功能不生效」的根因）。
        //
        //   `lineCapConsumed` 在 tickFlying 里一旦置 true 就再也不回 false：
        //     if (lineLength >= MAX_LINE && !lineCapConsumed) { lineCapConsumed = true; ...弹回... }
        //   它是**实体字段、跨投掷保留**，而 launch() 原来只复位了位置与速度。
        //   于是第一次扔到线长之后，之后每一次投掷线长上限都彻底失效 ——
        //   既不会弹回、也不会被拽回，锤头可以拽到任意远。
        //
        //   同理把回收状态也清掉，让这次投掷从干净状态开始。
        this.lineCapConsumed = false;
        this.setRetrieving(false);

        Vec3 dir = shooter.getLookAngle();
        if (dir.lengthSqr() < 1.0E-6D) {
            dir = new Vec3(0.0D, 0.0D, 1.0D);
        }
        this.setDeltaMovement(dir.normalize().scale(speed));
        this.hurtMarked = true;
    }

    /**
     * 蓄力圆周的最高点 = 圆心 + UP × 半径。
     *
     * <p>圆心与 {@link #spinPosition} 同一套：碰撞箱右侧平面、离地 {@code SPIN_HEIGHT}。</p>
     */
    public Vec3 spinTop(net.minecraft.world.entity.LivingEntity owner) {
        Vec3 look = owner.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z);
        if (flatLook.lengthSqr() < 1.0E-6D) {
            flatLook = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flatLook = flatLook.normalize();
        Vec3 right = new Vec3(-flatLook.z, 0.0D, flatLook.x);
        return owner.position()
                .add(right.scale(owner.getBbWidth() * 0.5D))
                .add(0.0D, SPIN_HEIGHT + SPIN_RADIUS, 0.0D);
    }

    /**
     * <b>出手点</b>：视线略下方、身前一点点（作者 2026-10-30）。
     *
     * <p>原来用的是 {@link #spinTop}（蓄力环绕最高点），高度是「脚底 + 1.75 + 1.5 = 3.25 格」，
     * 比视线高 1.6 格 —— 贴脸打怪时锤头从头顶飞过去，轻球（初速度大）最明显。</p>
     *
     * <p>改成正对视线出手：与第一人称看到的锤头位置一致，近距离也不会越过去。</p>
     */
    private Vec3 launchOrigin(net.minecraft.world.entity.LivingEntity shooter) {
        Vec3 look = shooter.getLookAngle();
        return shooter.getEyePosition()
                .add(look.scale(0.35D))
                .add(0.0D, -0.1D, 0.0D);
    }

    /**
     * <b>蓄力（SPIN）期间的等效速度</b>（作者 2026-10-30：「蓄力期间，球对生物似乎没有碰撞效果」）。
     *
     * <p>SPIN 状态下锤头的位置由 {@code spinPosition()} 每 tick 直接摆放，速度被清成
     * {@code Vec3.ZERO} —— 而 {@code impactDamage()} 是按「击中速度 / 出手速度」缩放的，
     * 于是伤害恒为 0，扫到怪完全没反应。</p>
     *
     * <p>这里给一个**等效的环绕线速度**：转一圈的周长 ÷ 一圈的时长。它与真实的
     * 「球被甩着扫过去」的体感一致，也不会因为速度是 0 就把伤害算没。</p>
     */
    @Override
    public double effectiveSpeed() {
        if (this.stage() == Stage.SPIN) {
            // 周长 = 2πr；每圈 SPIN_STEP / (2π) 个 tick
            double circumference = 2.0D * Math.PI * SPIN_RADIUS;
            double ticksPerLoop = (2.0D * Math.PI) / SPIN_STEP;
            double speed = circumference / Math.max(1.0D, ticksPerLoop);
            // 兜底：至少让伤害有个下限（否则比值仍然接近 0）
            return Math.max(speed, this.profile().physicalVelocity() * 0.35D);
        }
        // ===== 飞行 / 回收阶段：**不按当前速度衰减**（作者 2026-10-30）=====
        //
        //   作者实测：「满蓄力打怪只有四五点伤害，偶尔能打出来 11 血」。
        //
        //   根因：`impactDamage()` 算的是 `damage × (击中瞬间速度 / 出手速度)`。
        //   球那样设计是因为弹道球飞得越远越慢、伤害自然该掉；但**锤头是近战武器** ——
        //   满蓄力甩出去打中谁，伤害就该是满的，不该因为飞了十几格（重力 + 阻力衰减）
        //   就砍到 40%。
        //
        //   这里让 FLYING / RETURNING 阶段的「等效速度」恒等于**出手速度**：
        //   ratio = launchSpeed / launchSpeed = 1 ⇒ 伤害恒为基础伤害（铁球 = 11）。
        if (this.launchSpeed > 1.0E-4D) {
            return this.launchSpeed;
        }
        return super.effectiveSpeed();
    }
    /**
     * <b>锤头永远不「飞回主人」</b>（作者 2026-10-30：「回响流星锤会在与掷出者碰撞的时候
     * 在掷出者身上掉落一个回响球」）。
     *
     * <h2>根因</h2>
     * <p>回响球带【忠诚】，而 {@code homingToOwner0()} 里有一段交付逻辑：</p>
     *
     * <pre>
     * if (toOwner.length() &lt;= RETURN_ARRIVE_DISTANCE) {
     *     this.deliverToOwner(serverLevel, living);   // ← 把球交回玩家 / 掉在脚边
     *     return true;
     * }
     * </pre>
     *
     * <p>锤头继承了球的整套词条，于是它一飞到掷出者身边就被判成「回归到达」，
     * 把内嵌那颗回响球交了出去 —— 表现就是「碰到掷出者时在身上掉一个回响球」。</p>
     *
     * <h2>为什么整个关掉</h2>
     * <p>锤头有自己的回收系统（{@code RETURNING} 状态 + {@code finishReturn}），
     * 走的是「线拉回手里」而不是「球自己飞回来」。两条路径并存只会打架：
     * 一条要交付、一条要收线，谁先到谁说了算。所以这里直接返回 false，
     * 让锤头的生命周期完全由自己的三态机掌控。</p>
     */
    @Override
    protected boolean homingToOwner0() {
        return false;
    }
    /** 这个位置放得下锤头吗？（碰撞箱与方块不相交） */
    private boolean isFreeAt(Vec3 pos) {
        net.minecraft.world.phys.AABB box = this.getDimensions(this.getPose()).makeBoundingBox(pos);
        return this.level().noCollision(box);
    }

    /** 卡进方块时往哪个方向脱离：优先朝掷出者（那是玩家所在、通常是空的） */
    private Vec3 escapeDirection() {
        if (this.getOwner() instanceof net.minecraft.world.entity.LivingEntity living) {
            Vec3 toOwner = living.getEyePosition().subtract(this.position());
            if (toOwner.lengthSqr() > 1.0E-6D) {
                return toOwner.normalize();
            }
        }
        return new Vec3(0.0D, 1.0D, 0.0D);   // 兜底往上
    }


    /**
     * FLYING 阶段。
     *
     * <p>球的物理与词条由 {@link BallPhysicsCore#tick()} 跑（父类 tick 已经覆盖：
     * 尖啸路径判定 / 弹射 / 音波 / 感应积热 / 磁吸 / 磁性 / 照明 / 透镜 / 融化 / 引雷 / 导电 …），
     * 这里只加**锤头独有的两条**（作者 2026-10-20 补充）：</p>
     *
     * <ol>
     *   <li><b>线长上限 = 完成一次弹射</b> —— 到 30 格时调一次 {@code shriekLand}，
     *       让【尖啸】把这一次当成「撞到东西」，按 {@code bounce} 档位结算一次弹射，
     *       然后扣 1 耐久 + 给一个朝掷出者的小速度。</li>
     *   <li><b>自动回收类词条 = 自动收线</b> —— 【空气动力球】/【忠诚】/【魔法·回收】
     *       原本的语义是「球自己飞回主人」，在锤头身上就是<b>自动收线</b>：
     *       直接把 {@code RETRIEVING} 置 true，走已有的回收流程。</li>
     * </ol>
     */
    private void tickFlying() {
        // ===== ⓪ 卡进方块时先脱离（作者 2026-10-30：「扔出会沉到方块里」）=====
        //
        //   FLYING 阶段 noPhysics=false，正常吃方块碰撞；但如果某一刻被推进实心区域
        //   （高速度穿过薄墙、被活塞推、或者在墙角爆炸），就会一直嵌在里面 ——
        // ===== 卡进方块时先脱离（落地后下沉、持续扣耐久）=====
        //
        // 说明一点：**球没有这个问题，也不该有**。球落地后会被碰撞解算推回方块表面、
        // 静止待命，那是正常物理表现 —— 球没有耐久账本，贴地看不出任何异常。
        //
        // 锤头不同：它沿用球那套物理（noPhysics=false、受重力），但自己在 onHit 里
        // 撞方块要扣耐久。于是「贴地」在锤头身上变成「卡在方块里持续掉耐久」。
        //
        // 两件事一起做：
        // 1) 逃逸幅度 12 步(0.75 格) 提到 40 步(2.5 格) —— 原来逃不出去就是幅度不够；
        // 2) 卡住时关掉重力，别再让重力继续往下压；真正脱离后下一次再开回来。
        if (this.isInWall()) {
            this.setNoGravity(true);
            // 先**向上**找空位：被重力压进地面时，只有往上是有效的
            // （原来只朝玩家方向水平逃逸，锤头嵌在脚下就永远出不来）。
            boolean escaped = false;
            for (int step = 1; step <= 40; step++) {
                Vec3 candidate = this.position().add(0.0D, step * 0.0625D, 0.0D);
                if (this.isFreeAt(candidate)) {
                    this.setPos(candidate.x, candidate.y, candidate.z);
                    this.setDeltaMovement(Vec3.ZERO);
                    escaped = true;
                    break;
                }
            }
            // 上方被堵（洞穴顶）⇒ 再试朝掷出者方向
            if (!escaped) {
                Vec3 escape = this.escapeDirection();
                for (int step = 1; step <= 40; step++) {
                    Vec3 candidate = this.position().add(escape.scale(step * 0.0625D));
                    if (this.isFreeAt(candidate)) {
                        this.setPos(candidate.x, candidate.y, candidate.z);
                        this.setDeltaMovement(Vec3.ZERO);
                        break;
                    }
                }
            }
        } else if (this.getDeltaMovement().lengthSqr() > 1.0E-4D) {
            this.setNoGravity(false);
        }

        this.setLineLength(distanceToOwner());

        // ===== ① 线长上限：扣 1 耐久 + 完成一次弹射 + 朝掷出者的小速度 =====
        if (this.lineLength() >= MAX_LINE && !this.lineCapConsumed) {
            this.lineCapConsumed = true;

            // 【尖啸】的「弹射」：到线长上限等同于撞到东西，交给 shriekLand 结算一次。
            // 它内部会按 bounce 档位决定「反弹还是停住」，并补落点音爆与范围伤害。
            if (this.shriekFired) {
                this.shriekLand(this.position(), null);
            }

            Vec3 toOwner = ownerEye().subtract(this.position());
            this.setDeltaMovement(toOwner.lengthSqr() > 1.0E-6D
                    ? toOwner.normalize().scale(SNAP_BACK_SPEED)
                    : Vec3.ZERO);
            this.consumeOneToughness("到达线长上限");
        }

        // ===== ② 自动回收类词条 ⇒ 自动收线 =====
        //
        // 【空气动力球】（aerodynamic_ball）/【忠诚】（loyalty）/【魔法·回收】
        // 在球身上的语义都是「自己飞回主人」。锤头没有「飞回主人」这回事 ——
        // 它对应的是**收线**，所以这里直接开始回收。
        //
        // ⚠️ 只在「还没在回收」时触发一次；`beginReturning` 自己会判重入。
        if (this.stage() == Stage.FLYING && this.autoRetrieveWanted()) {
            this.beginReturning((net.minecraft.world.entity.LivingEntity) this.getOwner());
            this.setRetrieving(true);
        }

        // ===== ③ 绳子的物理约束（作者 2026-10-29：「要有被绳子拽着的物理效果」）=====
        //
        // 绳子**不可拉伸**。锤头一旦飞到线长之外，真实的反应是：
        //   · 位置被拽回到「以掷出者为球心、半径 = 线长」的球面上
        //   · 速度里「朝外」的分量被绳子吃掉（否则等于绳子被拉长）
        //   · 「切向」分量保留 ⇒ 锤头自然开始绕着掷出者摆动（链球）
        //
        // 不这么做的后果就是原来那样：绳子只是渲染时画的一根线，
        // 锤头可以无视它一直往外飞，看起来像线在跟着瞬移。
        if (this.getOwner() instanceof net.minecraft.world.entity.LivingEntity ropeOwner) {
            Vec3 anchor = handAnchor(ropeOwner);
            Vec3 delta = this.position().subtract(anchor);
            double distance = delta.length();
            // ⚠️ 只在**明显**超出线长时才约束（作者 2026-10-30：「收到脚边时会变得非常费劲，还会卡在方块上」）。
            //
            //   原来阈值正好卡在 MAX_LINE 上，锤头一到边界就被反复拉回 + 施加向内张力，
            //   表现为「越靠近越费力」，还会被硬 setPos 塞进方块里卡住。
            //
            //   现在：① 阈值放宽到 1.15 × MAX_LINE，正常回收全程不触发；
            //        ② **不再加向内张力** —— 「绳子不可拉伸」只用「砍掉朝外的径向分量」表达。
            // 阈值必须是 MAX_LINE 本身，不能是 1.15 倍。
            //
            // 原来放宽到 1.15 倍是为了不让回收过程被反复拉回，但回收走 RETURNING 阶段、
            // 根本不会进这个方法。FLYING 阶段放宽只会让锤头在线外(30~34.5 格)无人约束地停着：
            // 物品栏显示已扔出，锤头却躺在地上不动，人走远了也不跟过来。
            //
            // 现在只要超出线长就每 tick 拽回球面，并砍掉朝外的速度分量。
            if (distance > MAX_LINE && distance > 1.0E-4D) {
                Vec3 out = delta.scale(1.0D / distance);          // 掷出者 → 锤头
                Vec3 clamped = anchor.add(out.scale(MAX_LINE));
                this.setPos(clamped.x, clamped.y, clamped.z);

                Vec3 velocity = this.getDeltaMovement();
                double radialOut = velocity.dot(out);
                if (radialOut > 0.0D) {
                    velocity = velocity.subtract(out.scale(radialOut));   // 只砍向外的分量
                }
                this.setDeltaMovement(velocity);
            }
        }
    }

    /**
     * 这颗球有没有「自动回收类」词条 —— 有就让锤头自动收线。
     *
     * <p>三条都走同一个出口：
     * <ul>
     *   <li>【空气动力球】{@code aerodynamic_ball} —— 球静止后自动飞回</li>
     *   <li>【忠诚】{@code loyalty} —— 唯一特性，完整球才有</li>
     *   <li>【魔法·回收】{@code TRAIT_MAGIC_RECOVER} —— 魔法球的一档</li>
     * </ul>
     */
    private boolean autoRetrieveWanted() {
        BallBehavior.BallProfile profile = this.profile();
        if (profile.loyalty()) {
            return true;
        }
        if (this.traitFired(TRAIT_MAGIC_RECOVER)) {
            return true;
        }
        return net.neoforged.fml.ModList.get().isLoaded("minecraft")
                && net.minecraft.world.item.enchantment.EnchantmentHelper
                        .getItemEnchantmentLevel(
                                this.level().registryAccess()
                                        .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                                        .getOrThrow(com.mcmodworkspace.moreballs.ModEnchantments.AERODYNAMIC_BALL),
                                this.flailStack()) > 0;
    }

    /** 开始回收：扣 1 点耐久，并让词条从此失效（作者指定） */
    public void beginReturning(LivingEntity owner) {
        if (this.stage() == Stage.RETURNING) {
            return;
        }
        this.setStage(Stage.RETURNING);
        this.setNoGravity(false);   // 回收时**受重力影响**（作者指定）
        this.getEntityData().set(TRAITS_DISABLED, true);
        this.setDeltaMovement(Vec3.ZERO);
        this.consumeOneToughness("开始回收");
    }

    /**
     * 回收：<b>只有 {@code isRetrieving()} 为 true 时才朝玩家移动</b>。
     *
     * <p>作者 2026-10-19：「按住右键时回收，松开会停止，直接照着掷出者移动」。</p>
     *
     * <p>⚠️ 速度<b>完全覆盖</b>，不保留原有竖直分量 —— 上一版写的是
     * {@code current.y + pull.y * 0.35}，锤头带着上冲速度一路飞上天。</p>
     */
    /**
     * 回收：<b>只有 {@code isRetrieving()} 为 true 时才朝玩家移动</b>。
     *
     * <p>作者 2026-10-19 二次修订：「回收的时候不要被地形卡住」，
     * 尤其锤头低于玩家时它会被地面挡住、一直弹开扣耐久。</p>
     *
     * <h2>做法：回收期间**不走物理、不做碰撞检测**</h2>
     * <p>原先是每 tick 射线检测 + 沿法线弹开。问题在于锤头一旦掉进沟里或低于主手，
     * 它朝上的路径**必被地面挡住**，于是永远在弹、永远回不来，还白扣耐久。</p>
     *
     * `setPos(当前位置 + 朝主手的单位向量 × 每刻距离)` 算出 ——
     * 像一根绳子把它拽回来，地形不参与。这是作者要的「保持不动来收回锤头」：
     * 玩家自己不动，锤头直线回来。</p>
     */
    /**
     * 回收：<b>只有 {@code isRetrieving()} 为 true 时才朝玩家移动</b>。
     *
     * <h2>作者 2026-10-19 的最终要求</h2>
     * <ul>
     *   <li><b>回收走物理</b> —— 不能穿墙、不能直接 setPos 落位</li>
     *   <li><b>绳子有物理体积，但只对方块生效、不影响生物</b> ——
     *       碰撞箱缩到 0.2（绳子粗细），且 {@code isPickable} / {@code canBeCollidedWith}
     *       全返回 false，生物碰不到它</li>
     *   <li><b>不被地形卡住</b> —— 碰方块时<b>沿面滑动</b>（减掉法线分量），不是弹开</li>
     * </ul>
     *
     * <h2>为什么必须滑动而不是弹开</h2>
     * <p>锤头低于玩家时，它朝主手的路径<b>必被地面挡住</b>。原先撞到就沿法线反弹 + 扣耐久，
     * 于是它在地上反复弹、永远回不来，还白扣耐久 —— 这就是作者报的
     * 「非常频繁地卡在地上」。改成减掉法线分量后，它贴着地面滑回来。</p>
     */
    private void tickReturning() {
        if (!(this.getOwner() instanceof net.minecraft.world.entity.LivingEntity living)) {
            return;
        }
        Vec3 target = handAnchor(living);
        double distance = target.distanceTo(this.position());
        // 作者 2026-10-30：「让锤头的碰撞箱与玩家碰撞箱相接触后即可完成回收」。
        //
        // 原来是「中心距 < ARRIVE_DISTANCE(0.7)」—— 两个 0.5 格的盒子贴在一起时中心距就已经是 0.5，
        // 那个阈值对现在的碰撞箱来说偏大，视觉上还差一截就被判到了。改成真正的**盒相交**，
        // 另留一个极小的心距兜底（防止两个盒子完全重合时因为浮点误差永不触发）。
        if (this.getBoundingBox().intersects(living.getBoundingBox()) || distance < 0.05D) {
            this.finishReturn(living);
            return;
        }
        this.setLineLength(distance);

        if (!this.isRetrieving()) {
            // 松开右键：原地落下（不再被拉）
            this.setDeltaMovement(this.getDeltaMovement().scale(0.6D).add(0.0D, -0.04D, 0.0D));
            return;
        }

        // ===== 回收：**完全不受地形限制**（作者 2026-10-19 第三次修订）=====
        //
        // 作者原话：「我要的回收是具有物理碰撞、并且**完全不受地形限制**的回收。」
        //
        // 这两件事并不矛盾：
        //   · 「有物理碰撞」= 投掷飞行阶段照常吃方块碰撞、能打生物、能被打 —— 没变；
        //   · 「不受地形限制」= **回收阶段**的路径不再做任何方块检测。
        //
        // 我前两版都错在同一个地方：一直在回收路径上做 `level().clip(...)`（先是撞到就弹开，
        // 后来改成沿面滑动），但锤头低于玩家时朝主手的路径**必被地面挡住**，
        // 只要检测还在，它就一定上不来。
        //
        // 碰撞体积本身还在（`isPickable` / `canBeCollidedWith` 仍是 false，生物碰不到），
        // 只是这条「绳子拉回」的路径不再受方块阻挡。
        double perTick = FlailItem.recoverSpeed(this.profile()) / TICKS_PER_SECOND;
        perTick = Math.min(perTick, Math.max(0.05D, distance - ARRIVE_DISTANCE * 0.5D));

        // ===== 移动回收：被掷出者「拖着走」（作者 2026-10-30 指定）=====
        //
        //   静态回收时锤头每 tick 只走 `recoverSpeed / 20`（铁球 ≈ 0.21 格），
        //   而玩家疾跑是 0.28 格/tick —— **锤头追不上人**，绳子只会越拉越长。
        //
        //   做法：把玩家这一 tick 的水平位移按 {@link #RETURN_DRAG_RATIO}(70%) 加给锤头，
        //   于是它一边自己往回飞、一边被拖着走。铁球合起来 ≈ 0.41 格/tick，
        //   相对玩家仍以 0.13 格/tick 靠近 ⇒ 边跑边收得回来。
        //
        //   同时**不让绳子变长**：拖拽之后若距离超过 {@link #MAX_LINE}，
        //   就沿「朝玩家」的方向夹回上限 —— 这是作者明确要求的。
        // 用位置差而不是 getDeltaMovement()：玩家实体的 deltaMovement 由 Player.aiStep()
        // 每 tick 重算，服务端读到的值不一定等于实际位移(实测拖拽完全没生效就是它)。
        // xo/yo/zo 是上一 tick 的真实坐标，稳得多。
        Vec3 ownerMove = living.position().subtract(living.xo, living.yo, living.zo);
        Vec3 drag = new Vec3(ownerMove.x * RETURN_DRAG_RATIO, 0.0D, ownerMove.z * RETURN_DRAG_RATIO);

        Vec3 dir = target.subtract(this.position()).normalize();
        Vec3 next = this.position().add(dir.scale(perTick)).add(drag);

        // 绳子不延长：超出上限就夹回来（沿朝玩家方向），这段不受地形限制的语义不变
        Vec3 toTarget = target.subtract(next);
        double afterDrag = toTarget.length();
        if (afterDrag > MAX_LINE) {
            next = target.subtract(toTarget.normalize().scale(MAX_LINE));
        }

        this.setPos(next.x, next.y, next.z);
        this.setDeltaMovement(Vec3.ZERO);
        this.hurtMarked = true;
    }
    private void finishReturn(LivingEntity owner) {
        if (this.level().isClientSide()) {
            this.discard();
            return;
        }
        returnFlailToOwner(owner);

        // ===== 收尾标记（作者 2026-10-19）=====
        //
        // ⚠️ 两件事必须一起做，漏一个就会出问题：
        //   · markRetrieving(false) —— 不清的话 `getUseAnimation` 永远返回 NONE
        //     （回收后再蓄力看不到拉弓动作），HUD 也永远判成「回收中」而不显示蓄力文字
        //   · markRecovered(true)   —— 玩家必须松开右键才能再蓄力
        //
        // 注意这里取的是**主手**：回收完成时物品还在原格（扔出时它就没离开过），
        // 所以主手那把就是被回收的这把。
        if (owner instanceof Player p) {
            clearFlailFlags(p);
            // ⚠️⚠️ **回收状态必须保持到玩家松手**（作者 2026-10-30：
            //    「回收之后保持右键不松手还是会显示蓄力字样并受到移速惩罚」）。
            //
            //    `clearFlailFlags` 会把 FLAIL_RETRIEVING 清掉，而锤头飞回这一 tick
            //    玩家手指通常还按着。清掉之后：
            //      · `getUseAnimation` 判不到回收 ⇒ 返回 BOW ⇒ 显示蓄力字样与拉弓动作；
            //      · `LocalPlayerMixin` 判不到回收 ⇒ 移速照旧被乘 0.2。
            //    两个症状同一个根，都是「状态被提前清了」。
            //
            //    这里把状态重新立起来，由 `FlailCastHandler` 在玩家**松开右键**
            //    （`!isUsingItem`）时连同 FLAIL_RECOVERED 一起清掉。
            net.minecraft.world.item.ItemStack heldAfter = p.getMainHandItem();
            if (FlailItem.isFlail(heldAfter)) {
                FlailItem.markRetrieving(heldAfter, true);
            }
        }
        this.discard();
    }

    /** 归还流星锤：**先试原本那一格**，被占就找最近空格，全满掉脚下（作者指定） */
    private void returnFlailToOwner(LivingEntity owner) {
        ItemStack flail = this.flailStack().copy();
        if (flail.isEmpty()) {
            return;
        }
        if (owner instanceof Player player) {
            var inventory = player.getInventory();
            int slot = this.originSlot;

            // ⚠️⚠️ **物品从来没有离开过手**（作者 2026-10-19 报「回收后多一把流星锤」）。
            //
            //   扔出时只把 `FLAIL_CASTING` 置 true 让贴图变成纯线形态，**主手这一格
            //   始终是那把流星锤**。所以回收时如果照旧 `inventory.add(flail)`，
            //   就等于凭空再造一把 —— 表现就是「回收后多出一个新的流星锤」。
            //
            //   正确顺序：原格还是流星锤 ⇒ 什么都不做；原格空 ⇒ 放回；
            //   原格被别的占了但手里另有一把流星锤 ⇒ 也不动（手上那把就是它）；
            //   都没有才找空格 / 掉落。
            if (slot >= 0 && slot < inventory.getContainerSize()) {
                ItemStack cur = inventory.getItem(slot);
                if (FlailItem.isFlail(cur)) {
                    return;                       // ← 关键：不 add
                }
                if (cur.isEmpty()) {
                    inventory.setItem(slot, flail);
                    return;
                }
            }
            if (FlailItem.isFlail(player.getMainHandItem())) {
                return;                           // 手里那把就是它
            }
            if (inventory.add(flail)) {
                return;
            }
            player.drop(flail, false);
            return;
        }
        // 非玩家掷出者（目前不会有，留作扩展）
        if (!owner.getSlot(0).set(flail)) {
            this.spawnAtLocation((ServerLevel) this.level(), flail, 0.1F);
        }
    }

    /** 主人没了：把流星锤还回背包后再消失，绝不留孤儿锤头 */
    /**
     * 公开版「把这把锤还回去并消失」—— 登录清理用。
     *
     * <p>与内部 {@code returnFlailAndDiscard} 的区别：这个方法先判定阶段，
     * 只有非 SPIN（真的扔出去了、或卡住了）才收，避免把正在蓄力的锤头误删。</p>
     */
    public void retireToOwner() {
        if (!this.level().isClientSide()) {
            if (this.stage() == Stage.SPIN) {
                this.discard();   // 蓄力中的直接丢（物品还在手上，不需要归还）
            } else {
                this.returnFlailAndDiscard();
            }
        }
    }

    /**
     * <b>把玩家背包里所有流星锤的「扔出中 / 回收中」标记清干净</b>。
     *
     * <p>回收完成（{@link #finishReturn}）和超时归还（{@link #returnFlailAndDiscard}）
     * 都必须调它。少调一次的表现就是作者反复报的那个：**手里那把锤只剩一根线、
     * 看不到球，而且右键蓄力被拦、扔不出去**。</p>
     *
     * <p>扫**整个背包**而不是只查主副手 —— 玩家在飞行途中把锤挪到别的格子是很常见的操作，
     * 只查手持位置会漏掉，那把锤就永久卡在纯线形态。</p>
     */
    private static void clearFlailFlags(Player p) {
        var inventory = p.getInventory();
        boolean touched = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack h = inventory.getItem(slot);
            if (FlailItem.isFlail(h) && FlailItem.isCasting(h)) {
                FlailItem.markRetrieving(h, false);
                FlailItem.markRecovered(h, true);
                FlailItem.setCasting(h, false);
                touched = true;
            }
        }
        ItemStack off = p.getOffhandItem();
        if (FlailItem.isFlail(off) && FlailItem.isCasting(off)) {
            FlailItem.markRetrieving(off, false);
            FlailItem.markRecovered(off, true);
            FlailItem.setCasting(off, false);
            touched = true;
        }
        if (!touched) {
            FlailItem.clearCastingFlags(p);
        }
        // ⚠️⚠️ **必须主动同步到客户端**（作者 2026-10-30 报「回收后物品贴图只剩线、无法再扔」）。
        //
        //   前面那些 `stack.remove(FLAIL_CASTING)` 只改了服务端的 ItemStack ——
        //   MC 的物品栏同步按「整格内容变化」触发，而这里改的是**物品的一个组件**，
        //   服务端不会因此给客户端发包。客户端于是继续按旧的 FLAIL_CASTING=true 渲染
        //   `flail_string_only`（纯线），右键蓄力也被客户端的旧状态挡着。
        //
        //   broadcastChanges() 会把整个容器同步下去，组件变化也就一起过去了。
        if (p instanceof net.minecraft.server.level.ServerPlayer sp) {
            sp.containerMenu.broadcastChanges();
            sp.getInventory().setChanged();
        }
    }

    private void returnFlailAndDiscard() {
        Entity owner = this.getOwner();
        if (owner instanceof LivingEntity living) {
            returnFlailToOwner(living);
            // ⚠️ 超时/锤主没了这条路径原来**不清标记**（作者 2026-10-29 报「回收后锤头消失」）。
            //    锤头被 discard 之后 findHead 返回 null，看似下一 tick 会自愈 ——
            //    但那只在「主手正好是流星锤」时才成立；锤被挪走就永久停在纯线形态。
            if (living instanceof Player p) {
                clearFlailFlags(p);
            }
        } else {
            ItemStack flail = this.flailStack().copy();
            if (!flail.isEmpty() && this.level() instanceof ServerLevel level) {
                this.spawnAtLocation(level, flail, 0.1F);
            }
        }
        this.discard();
    }

    // ===== 耐久（写回物品栈，不走实体字段）=====

    /**
     * 扣 1 点耐久 —— <b>写回 {@code TOUGHNESS_LEFT} 组件</b>，不走实体字段。
     *
     * <p>⚠️ 这是流星锤与球最大的实现差异：球的耐久活在实体上（回收时由
     * {@code BallItem.applyToughness} 写回），而流星锤在飞行途中就要能扣，
     * 所以直接改物品栈的组件。</p>
     */
    public void consumeOneToughness(String cause) {
        if (this.level().isClientSide()) {
            return;
        }
        ItemStack carried = this.flailStack();
        if (carried.isEmpty()) {
            return;
        }
        BallBehavior.BallProfile profile = this.profile();
        int max = profile.toughness();
        if (max == BallBehavior.TOUGH_FOREVER || max <= BallBehavior.NOT_TOUGH) {
            return;   // 永久坚固 / 不坚固 ⇒ 没有耐久可扣
        }
        // ⚠️⚠️ **同一个 tick 只能扣一次**（作者 2026-10-24：「落地一次扣好几十耐久」）。
        //
        //   落地/贴墙时 onHit 会**每 tick 触发一次**（实体一直嵌在方块表面），
        //   没有这道闸就是 20 点/秒 —— 看起来像"一次落地扣几十"。
        //   球的 consumeDurability 本来就有同样的 lastDamageTick 检查，锤头这边漏了。
        // ⚠️ 只防「同一 tick 重复」不够（作者 2026-10-30：「扔出会沉到方块里然后迅速消耗耐久碎掉」）。
        //
        //   锤头一旦嵌进方块，`onHit` 会**每 tick 都触发一次**，而 tickCount 每 tick 都在变，
        //   原来那道「同 tick 才跳过」的闸根本拦不住 —— 铁球 99 耐久 5 秒就没了。
        //
        //   改成**最小间隔 4 tick**：每 0.2 秒最多扣 1 点。既保留「连续撞墙掉耐久」的语义，
        //   又不会让「卡进方块」这种异常情况瞬间把锤头烧光。
        // ⚠️⚠️ 必须**先确认前一次有效**再算差值 —— 字段初始值是 `Integer.MIN_VALUE`，
        //    直接 `tickCount - Integer.MIN_VALUE` 会**整数溢出成负数**（100 - (-2147483648)
        //    超出 int 范围），于是 `< 4` 恒成立、每次都提前返回 —— 表现就是
        //    **耐久一点都不扣**（作者 2026-10-30：「现在流星锤耐久不消耗了」）。
        if (this.flailLastDamageTick >= 0
                && this.tickCount - this.flailLastDamageTick < TOUGHNESS_MIN_INTERVAL_TICKS) {
            return;
        }
        this.flailLastDamageTick = this.tickCount;


        // ⚠️⚠️ **耐久必须写在玩家物品栏那一把上**（作者 2026-10-19：「耐久是根本不扣」）。
        //
        //   原来的写法是 `this.setItem(after)` —— 那只改**锤头实体自己**复制的那份物品栈，
        //   而锤头 discard 之后它就不存在了，玩家手上那一把从头到尾没人碰过 ⇒ 耐久永远不变。
        //
        //   现在直接定位到玩家的物品栏格子（优先 originSlot、其次主手、再次副手），
        //   在**那一份**上写 TOUGHNESS_LEFT，并同步锤头内部那份做缓存。
        ItemStack live = findLiveFlail();
        if (live.isEmpty()) {
            return;
        }
        Integer leftComp = live.get(ModComponents.TOUGHNESS_LEFT.get());
        int remaining = (leftComp == null ? max : leftComp) - 1;
        if (remaining <= 0) {
            this.shatter();
            return;
        }
        live.set(ModComponents.TOUGHNESS_LEFT.get(), remaining);
        // 实体字段也要跟着走：球的 onHit / physicalVelocity 读的是它，
        // 不同步的话它会一直是开头的值（撞方块的耐久判定就永远停在原地）。
        this.setToughness(remaining);
        // 锤头内部那份同步一下（tooltip / 渲染读到的是它）
        ItemStack mirror = FlailItem.worldForm(live);   // 锤头那份不带 CASTING，否则世界里只剩绳
        this.setItem(mirror);
    }

    /**
     * 找到玩家物品栏里**真正的那把**流星锤。
     *
     * <p>顺序：{@code originSlot} → 主手 → 副手 → 背包里第一个流星锤。</p>
     */
    private ItemStack findLiveFlail() {
        if (!(this.getOwner() instanceof Player player)) {
            return ItemStack.EMPTY;
        }
        var inv = player.getInventory();
        if (this.originSlot >= 0 && this.originSlot < inv.getContainerSize()) {
            ItemStack s = inv.getItem(this.originSlot);
            if (FlailItem.isFlail(s)) {
                return s;
            }
        }
        ItemStack main = player.getMainHandItem();
        if (FlailItem.isFlail(main)) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (FlailItem.isFlail(off)) {
            return off;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (FlailItem.isFlail(s)) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 破碎：<b>在掷出者脚下掉一根线</b>（作者指定），然后消失。
     *
     * <p>⚠️ 刻意<b>不吃【变形】</b>：{@code BallProjectile.burst()} 对【变形】是
     * 「不变碎裂、原地变方块并 return」，照搬会导致「变形了但没掉线」，
     * 与作者给的破碎产物冲突。理由见通读记录。</p>
     */
    /**
     * 破碎：<b>在掷出者脚下掉一根线，并把手上那把流星锤一起销毁</b>（作者规格）。
     *
     * <p>⚠️⚠️ <b>必须销毁手上物品</b>（作者 2026-10-23 报「碎了还会正常拉回来一个锤头」）。</p>
     *
     * <p>之前这里只做了 `discard()` 掉实体 —— 而扔出时手上的流星锤**从来没被移走**
     * （只把 `FLAIL_CASTING` 置 true 让它显示纯线形态）。于是实体没了、物品还在，
     * 玩家松开再按右键，`use()` 又生成一个新锤头 —— 看起来就是「碎了还能拉回来一个」。</p>
     *
     * <p>所以这里要走 `FlailItem` 的正式销毁路径：把主手/原格那把 `shrink(1)`。</p>
     */
    private void shatter() {
        if (!(this.level() instanceof ServerLevel level)) {
            this.discard();
            return;
        }
        Entity owner = this.getOwner();
        Vec3 dropAt = owner != null ? owner.position() : this.position();

        BallBehavior.BallSound sound = this.profile().sound();
        level.playSound(null, dropAt.x, dropAt.y, dropAt.z,
                sound.resolveBreakSound(), net.minecraft.sounds.SoundSource.NEUTRAL,
                0.8F * sound.volume(), 1.2F);

        net.minecraft.world.entity.item.ItemEntity string =
                new net.minecraft.world.entity.item.ItemEntity(level, dropAt.x, dropAt.y + 0.2D, dropAt.z,
                        new ItemStack(net.minecraft.world.item.Items.STRING, 1));
        level.addFreshEntity(string);

        // ===== 销毁手上那把流星锤（这才是「碎了」的真正含义）=====
        // ===== 销毁**这一把**流星锤（这才是「碎了」的真正含义）=====
        //
        // ⚠️⚠️ 不能用 findLiveFlail() 满背包搜（作者 2026-10-24：
        //   「耐久耗尽破碎后…还会导致其他的流星锤拿到手里就消失」）——
        //   那把锤已经没了的时候，满背包搜会**抓到另一把**并把它 shrink 掉。
        //   这里只认 originSlot 那一格；找不到就什么都不销毁（宁可少销毁也不错杀）。
        if (owner instanceof Player player) {
            int slot = this.originSlot >= 0 ? this.originSlot
                    : player.getInventory().getSelectedSlot();
            if (slot >= 0 && slot < player.getInventory().getContainerSize()) {
                ItemStack at = player.getInventory().getItem(slot);
                if (FlailItem.isFlail(at)) {
                    at.shrink(1);
                }
            }
        }

        this.level().broadcastEntityEvent(this, (byte) 3);
        this.discard();
    }

    // ===== 命中 =====

    @Override
    /**
     * 命中生物 —— <b>直接用球那一套</b>（作者 2026-10-20：锤头一切尽可能照搬球）。
     *
     * <p>继承 {@link BallProjectile} 之后，球的 `onHitEntity` 里那一整套词条
     * （移形换位 / 善良 / 熔融 / 魔法 / 金光闪闪 / 敛财 / 招蜂引蝶 / 封印 / 尖刺 / 敌对球特效）
     * 全部原样生效，不需要再一条条搬。</p>
     *
     * <p>这里只加两件事：</p>
     * <ul>
     *   <li>首次回收之后 `traitsDisabled` ⇒ 整段跳过（作者规格：回收后不再造成伤害）</li>
     *   <li>击退倍率：SPIN 0.5 倍、FLYING 2 倍（球没有这个概念）</li>
     * </ul>
     */
    // 同上：故意不写 @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        if (this.level().isClientSide()) {
            return;
        }
        if (this.getOwner() == hitResult.getEntity()) {
            return;
        }
        if (this.traitsDisabled()) {
            return;   // 首次回收后不再造成伤害
        }
        // 交给球那套完整逻辑（伤害 + 全部词条）
        super.onHitEntity(hitResult);

        // 锤头专属：额外击退（球没有这个参数）
        if (!(hitResult.getEntity() instanceof LivingEntity target)) {
            return;
        }
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        double mult = this.stage() == Stage.SPIN ? 0.5D : 2.0D;
        Entity owner = this.getOwner();
        DamageSource source = owner instanceof LivingEntity living
                ? (owner instanceof Player p ? this.damageSources().playerAttack(p)
                        : this.damageSources().mobAttack(living))
                : this.damageSources().thrown(this, null);
        Vec3 away = target.position().subtract(this.position());
        double horizontal = Math.max(1.0E-4D, Math.sqrt(away.x * away.x + away.z * away.z));
        target.knockback(0.4D * mult,
                -away.x / horizontal, -away.z / horizontal, source, this.profile().damage());
    }

    /**
     * 撞方块 —— <b>直接用球那一套</b>。
     *
     * <p>球的 `onHit` 覆盖了脉冲 / 点金 / 尖刺 / 破坏王 / 金光闪闪 / 弹射衰减 / 碎裂，
     * 继承后全部自动生效。</p>
     *
     * <p>SPIN 与 RETURNING 阶段不结算方块碰撞（那两段位置由公式/玩家决定），
     * 直接早退。</p>
     */
    // 同上：故意不写 @Override
    /**
     * 撞方块 —— 词条交给球，但**碎裂由锤头自己处理**。
     *
     * <p>球的 `burst()` 是 private，它碎掉时掉的是**球物品**；
     * 锤头碎了必须「在掷出者脚下掉一根线、流星锤消失」（作者规格）。
     * 所以这里不能直接 `super.onHit()` 走到球的 burst 分支。</p>
     *
     * <p>做法：不坚固 ⇒ 自己 {@link #shatter()}；坚固 ⇒ 扣耐久 + 交给球的弹射逻辑。</p>
     */
    /**
     * 撞到东西 —— <b>完全交给球那一套</b>（作者 2026-10-23 指出我在这里多此一举）。
     *
     * <p>⚠️⚠️ 我原先在这里加过一个前置判断：</p>
     * <pre>
     * if (!profile.isTough()) { this.shatter(); return; }
     * super.onHit(hitResult);
     * </pre>
     * <p>目的是让「不坚固的锤头碎掉时掉一根线而不是掉球」。但代价是：</p>
     * <ul>
     *   <li>它把球 {@code onHit} 的完整逻辑（脉冲/点金/尖刺/破坏王/弹射/碎裂）<b>整个挡在外面</b></li>
     *   <li>而且只要那一瞬间判错，锤头就<b>无条件碎掉</b> —— 作者实测「耐久扣除正常，
     *       但一撞就碎」，正是这个判据在乱开枪</li>
     * </ul>
     *
     * <p><b>锤头是满耐久球做的</b>（{@code FlailItem.of()} 已经校验过 {@code isClustered()==false}），
     * 本来就不该走「不坚固」这条路。球的 {@code onHit} 自己会判，不需要我提前插手。</p>
     *
     * <p>锤头真正该「碎」的时机只有一个：<b>耐久被扣到 0</b> ——
     * 那在 {@link #consumeOneToughness} 里处理（掉线 + 销毁手上的流星锤）。</p>
     *
     * <p>SPIN / RETURNING 两段位置由公式与玩家决定，不结算方块碰撞，仍然早退。</p>
     */
    /**
     * 撞到东西 —— 词条交给球，<b>但耐久账本由锤头统一</b>。
     *
     * <p>⚠️⚠️ 锤头和球的耐性是<b>两套存储</b>，必须在这里对齐（作者 2026-10-23「耐久好像也不掉」）：</p>
     * <ul>
     *   <li><b>物品组件</b> {@code TOUGHNESS_LEFT} —— 锤头的权威值，tooltip 与耐久条读它</li>
     *   <li><b>实体同步字段</b> {@code TOUGHNESS} —— 球的 {@code onHit} / {@code isTough()} 读它</li>
     * </ul>
     *
     * <p>球的 {@code onHit} 撞方块时会调它自己的 {@code consumeDurability}，那<b>只改实体字段</b> ——
     * 于是「实体少 1、物品没动」，下一次 {@link #consumeOneToughness} 又按物品组件算，
     * 扣掉的耐久会自己长回来。反过来如果只扣物品不同步字段，球那边会一直以为自己是满的。</p>
     *
     * <p>所以顺序固定为：<b>先按物品账本扣 → 再让球跑词条 → 最后把实体字段按物品账本重置</b>。</p>
     */
    /**
     * 蓄力（SPIN）期间不结算【尖啸】—— 作者 2026-10-29 报「回响流星锤蓄力期间一直触发特效并掉落尖啸球」。
     *
     * <p>根因：SPIN 状态下锤头位置每 tick 由 {@code spinPosition()} 算出再 {@code setPos}，
     * 等于不停瞬移。而【尖啸】在球那边是「投出后第一刻就结算」——锤头照样跑 {@code super.tick()}，
     * 于是每刻都从当前球心重新打一条 256 格的射线，特效和落点掉落物就刷屏了。</p>
     */
    @Override
    protected boolean shriekAllowed() {
        return this.stage() != Stage.SPIN;
    }


    @Override
    protected void onHit(HitResult hitResult) {
        if (this.stage() == Stage.SPIN) {
            // ===== 蓄力中：只放行「碰生物」，方块碰撞整段跳过（作者 2026-10-29）=====
            //
            //   · 碰撞方块 —— 不结算弹射、不扣耐久、不触发尖啸。
            //     蓄力时锤头绕人转，位置是公式算出来的，路径上的方块会被当成一次次撞击。
            //   · 碰撞生物（掷出者除外）—— 照常生效，扫到怪一样能打。
            //
            // 之前这里是「SPIN 直接 return」，连打生物也一起关了，不符合作者要求。
            if (hitResult instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
                net.minecraft.world.entity.Entity target = entityHit.getEntity();
                if (target != this.getOwner()
                        && target instanceof net.minecraft.world.entity.LivingEntity) {
                    this.onHitEntity(entityHit);
                }
            }
            return;
        }
        if (this.stage() == Stage.RETURNING) {
            return;
        }
        if (!(this.level() instanceof ServerLevel)) {
            return;
        }

        // ① 撞方块扣 1 点（写物品组件 + 同步实体字段）。
        //    consumeOneToughness 内部已判「永久坚固 / 不坚固 ⇒ 不扣」，也会在归零时走 shatter。
        if (hitResult instanceof BlockHitResult) {
            this.consumeOneToughness("撞到方块");
        }

        // ② 词条（脉冲 / 点金 / 尖刺 / 破坏王 / 弹射 / 碎裂）全在球那边
        super.onHit(hitResult);

        // ③ 球的 consumeDurability 可能又动了实体字段 —— 以物品账本为准重置回去
        if (!this.isRemoved()) {
            Integer leftComp = this.flailStack().get(ModComponents.TOUGHNESS_LEFT.get());
            int max = this.profile().toughness();
            this.setToughness(leftComp == null ? max : leftComp);
        }
    }

    // ===== 工具 =====

    private Vec3 ownerEye() {
        Entity owner = this.getOwner();
        return owner == null ? this.position() : owner.position().add(0.0D, owner.getEyeHeight(), 0.0D);
    }

    private double distanceToOwner() {
        return distanceToOwner(this.getOwner());
    }

    private double distanceToOwner(Entity owner) {
        return owner == null ? 0.0D : owner.position().add(0.0D, owner.getEyeHeight(), 0.0D)
                .distanceTo(this.position());
    }

    /** 让外部（投掷处理器）能在 SPIN 期间更新持有的栈（耐久变化要同步过去） */
    @Override
    public void setItem(ItemStack stack) {
        super.setItem(stack);
    }

    /** 是否还能被围绕（SPIN 阶段且主人活着） */
    public boolean canSpin() {
        return this.stage() == Stage.SPIN && ownerAlive();
    }

    /** 满耐久判定（合成时要校验，和 {@code FlailItem.of} 同一口径） */
    public static boolean isPristine(ItemStack flail) {
        ItemStack ball = FlailItem.ballOf(flail);
        return !ball.isEmpty() && !BallClusterTier.of(ball).isClustered();
    }
}

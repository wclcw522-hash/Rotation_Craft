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
public class FlailHeadEntity extends ThrowableItemProjectile {

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
    private static final double ARRIVE_DISTANCE = 1.6D;
    /** 到线长上限时给的回弹速度 */
    private static final double SNAP_BACK_SPEED = 0.35D;

    /** 环绕角度（弧度） */
    private float spinAngle;

    /** 掷出时占用的背包格 —— 回收必回这一格（作者指定） */
    private int originSlot = -1;

    /** 记下的出手速度，供「按速度缩放伤害」用（照搬 BallProjectile.launchSpeed） */
    private double launchSpeed;
    /** 本次 FLYING 是否已为「到线长上限」扣过耐久（避免每 tick 反复扣） */
    private boolean lineCapConsumed;

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
    public ItemStack flailStack() {
        return this.getItem();
    }

    public BallBehavior.BallProfile profile() {
        ItemStack flail = this.flailStack();
        return FlailItem.profileOf(flail);
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
    public void tick() {
        super.tick();

        // ⚠️ **锤头绝不能单独存在**（作者强调）：主人没了 / 下线了 / 死了 ⇒ 立刻收场，
        //    并把流星锤还回背包，绝不留一个孤儿锤头在世界里。
        if (!this.level().isClientSide() && !ownerAlive()) {
            returnFlailAndDiscard();
            return;
        }

        if (this.getOwner() instanceof net.minecraft.world.entity.LivingEntity livingA) {
            this.storeAnchor(handAnchor(livingA));
        }

        switch (this.stage()) {
            case SPIN -> tickSpin();
            case FLYING -> tickFlying();
            case RETURNING -> tickReturning();
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

        // ① 瞬移到蓄力旋转的最高点
        Vec3 top = spinTop(shooter);
        this.setPos(top.x, top.y, top.z);

        // ② 按球的物理飞出：物理速度 × 蓄力倍率
        BallBehavior.BallProfile profile = this.profile();
        float speed = (float) (profile.physicalVelocity() * Math.max(0.1F, velocityMultiplier));
        this.launchSpeed = speed;

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

    private void tickFlying() {
        this.setLineLength(distanceToOwner());

        // 到线长上限：扣 1 点耐久 + 速度归零 + 给一个朝掷出者的小速度（作者指定）
        if (this.lineLength() >= MAX_LINE) {
            this.setDeltaMovement(Vec3.ZERO);
            Vec3 toOwner = ownerEye().subtract(this.position());
            if (toOwner.lengthSqr() > 1.0E-6) {
                this.setDeltaMovement(toOwner.normalize().scale(0.3D));
            }
            consumeOneToughness("到达线长上限");
        }
    }

    /** 开始回收：扣 1 点耐久，并让词条从此失效（作者指定） */
    public void beginReturning(LivingEntity owner) {
        if (this.stage() == Stage.RETURNING) {
            return;
        }
        this.setStage(Stage.RETURNING);
        this.setNoGravity(false);   // 回收时**受重力影响**（作者指定）
        this.getEntityData().set(TRAITS_DISABLED, true);
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
    private void tickReturning() {
        if (!(this.getOwner() instanceof net.minecraft.world.entity.LivingEntity living)) {
            return;
        }
        Vec3 target = handAnchor(living);
        double distance = target.distanceTo(this.position());
        if (distance < ARRIVE_DISTANCE) {
            this.finishReturn(living);
            return;
        }
        this.setLineLength(distance);

        if (!this.isRetrieving()) {
            // 松开右键：原地停住（只剩重力），不再被拉
            this.setDeltaMovement(this.getDeltaMovement().scale(0.6D).add(0.0D, -0.04D, 0.0D));
            return;
        }

        double perTick = FlailItem.recoverSpeed(this.profile()) / TICKS_PER_SECOND;
        Vec3 dir = target.subtract(this.position()).normalize();

        // 遇阻检测：这一 tick 要走的路径上有没有方块（作者：撞到要弹开并扣 1 耐久）
        Vec3 from = this.position();
        Vec3 to = from.add(dir.scale(perTick));
        var blockHit = this.level().clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (blockHit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            net.minecraft.core.Direction face = blockHit.getDirection();
            Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            double along = dir.dot(normal);
            Vec3 reflected = dir.subtract(normal.scale(2.0D * along)).normalize();
            this.setDeltaMovement(reflected.scale(perTick * RECOVER_BOUNCE).add(normal.scale(0.08D)));
            this.consumeOneToughness("回收时撞到方块");
            this.hurtMarked = true;
            return;
        }

        // 正常拉回：完全覆盖速度（含竖直分量）
        this.setDeltaMovement(dir.scale(perTick).add(0.0D, -0.03D, 0.0D));
        this.hurtMarked = true;
    }
    private void finishReturn(LivingEntity owner) {
        if (this.level().isClientSide()) {
            this.discard();
            return;
        }
        returnFlailToOwner(owner);
        // 标记「刚回收完」—— 玩家必须松开右键才能再蓄力（作者 2026-10-19）
        if (owner instanceof Player p) {
            ItemStack h = p.getMainHandItem();
            if (FlailItem.isFlail(h)) {
                FlailItem.markRecovered(h, true);
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
            if (slot >= 0 && slot < inventory.getContainerSize() && inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, flail);
                return;
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
    private void returnFlailAndDiscard() {
        Entity owner = this.getOwner();
        if (owner instanceof LivingEntity living) {
            returnFlailToOwner(living);
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
        ItemStack flail = this.flailStack();
        if (flail.isEmpty()) {
            return;
        }
        BallBehavior.BallProfile profile = this.profile();
        int max = profile.toughness();
        if (max == BallBehavior.TOUGH_FOREVER || max <= BallBehavior.NOT_TOUGH) {
            return;   // 永久坚固 / 不坚固 ⇒ 没有耐久可扣
        }
        // 剩余耐久：优先读组件，没有组件说明是满耐久
        Integer left = flail.get(ModComponents.TOUGHNESS_LEFT.get());
        int remaining = (left == null ? max : left) - 1;
        if (remaining <= 0) {
            this.shatter();
            return;
        }
        ItemStack after = flail.copy();
        after.set(ModComponents.TOUGHNESS_LEFT.get(), remaining);
        this.setItem(after);
    }

    /**
     * 破碎：<b>在掷出者脚下掉一根线</b>（作者指定），然后消失。
     *
     * <p>⚠️ 刻意<b>不吃【变形】</b>：{@code BallProjectile.burst()} 对【变形】是
     * 「不变碎裂、原地变方块并 return」，照搬会导致「变形了但没掉线」，
     * 与作者给的破碎产物冲突。理由见通读记录。</p>
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
                sound.resolveBreakSound(), net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F * sound.volume(), 1.2F);

        net.minecraft.world.entity.item.ItemEntity string =
                new net.minecraft.world.entity.item.ItemEntity(level, dropAt.x, dropAt.y + 0.2D, dropAt.z,
                        new ItemStack(net.minecraft.world.item.Items.STRING, 1));
        level.addFreshEntity(string);

        this.level().broadcastEntityEvent(this, (byte) 3);
        this.discard();
    }

    // ===== 命中 =====

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);

        if (this.level().isClientSide()) {
            return;
        }
        if (this.getOwner() == hitResult.getEntity()) {
            return;   // 不打主人
        }
        if (this.tickCount < SPAWN_GRACE_TICKS) {
            return;   // 出手宽限期（照搬 BallProjectile）
        }

        // 首次回收之后：**不再造成伤害**（作者指定）
        if (this.traitsDisabled()) {
            return;
        }

        if (!(hitResult.getEntity() instanceof LivingEntity target)) {
            return;
        }
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }

        // 伤害：SPIN 状态用基础伤害（速度不是飞行速度）；FLYING 状态照搬
        // BallProjectile.impactDamage() 的「基础 × 击中速度 / 出手速度」。
        BallBehavior.BallProfile profile = this.profile();
        float dealt;
        if (this.stage() == Stage.SPIN) {
            dealt = profile.damage();
        } else {
            double base = this.launchSpeed > 1.0E-4D ? this.launchSpeed : profile.physicalVelocity();
            dealt = base <= 1.0E-4D
                    ? profile.damage()
                    : (float) (profile.damage() * (this.getDeltaMovement().length() / base));
        }

        // ⚠️ 击退必须**自己施加**：原版 hurtServer 内部的击退方向由 DamageSource 决定、
        //    还会补随机推力（见 BallProjectile.deliverToOwner 的注释）。
        //    这里用普通攻击来源结算伤害，再手动 knockback —— SPIN 0.5 倍、FLYING 2 倍。
        double mult = this.stage() == Stage.SPIN ? 0.5D : 2.0D;

        Entity owner = this.getOwner();
        DamageSource source = owner instanceof LivingEntity living
                ? (owner instanceof Player player ? this.damageSources().playerAttack(player)
                        : this.damageSources().mobAttack(living))
                : this.damageSources().thrown(this, null);

        boolean hurt = target.hurtServer(level, source, dealt);

        // ⚠️ 26.2 的签名是 5 参：knockback(power, xd, zd, source, damage)
        //    —— 传 source 与 damage 是让原版能算「抗性/护甲是否该减免击退」，
        //    漏了它们就只能命中 3 参重载（不存在）。
        if (hurt) {
            Vec3 away = target.position().subtract(this.position());
            double horizontal = Math.max(1.0E-4D, Math.sqrt(away.x * away.x + away.z * away.z));
            target.knockback(BASE_KNOCKBACK * mult,
                    -away.x / horizontal, -away.z / horizontal, source, dealt);
        }

        // ===== 词条结算（作者 2026-10-19：流星锤必须继承球的全部词条）=====
        //
        // 流星锤是**完整球**做的 ⇒ 词条按 100% 生效，不走 `FIRED_TRAITS` 掷骰。
        // 每一条都对照 `BallProjectile.onHitEntity` 的写法，方法签名逐个核对过。
        LivingEntity traitOwner = owner instanceof LivingEntity le2 ? le2 : null;
        boolean truePhysical = false;   // 锤头不是「真实物理伤害」，与球默认一致

        // 【熔融】：目标身上有金属装备才挂灼伤
        if (profile.hasMolten()) {
            com.mcmodworkspace.moreballs.MoltenBurnEffect.applyMoltenBurn(
                    level, target, traitOwner, BallBehavior.MOLTEN_BURN_TICKS);
        }

        // 【魔法】：随机给目标一个负面效果
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_MAGIC)) {
            com.mcmodworkspace.moreballs.BallMagic.applyToVictim(level, target);
        }

        // 【敛财】：往目标身上掉钱/物品
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_MONEY)) {
            BallExtraTraits.applyMoney(target, traitOwner);
        }

        // 【招蜂引蝶】：召唤蜜蜂
        if (profile.hasBees()) {
            BallExtraTraits.summonBees(level, target, profile.beeCount(), owner, truePhysical);
        }

        // 【封印】
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_SEAL)) {
            BallExtraTraits.seal(level, target, owner);
        }

        // 【尖刺】：从命中点朝四周射出骨刺
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_SPIKE)) {
            BallExtraTraits.shootSpikes(level, this.position(), this.getDeltaMovement(),
                    owner, truePhysical);
        }
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        // 撞方块：FLYING 状态按球的弹射衰减一次；SPIN 与 RETURNING 不结算
        if (!(this.level() instanceof ServerLevel)) {
            return;
        }
        if (this.stage() != Stage.FLYING) {
            return;
        }
        // 弹性衰减：保留 (bounce × 10)% 的速度并翻折竖直分量
        double retain = Math.min(1.0D, this.profile().bounce() / 10.0D);
        if (retain <= 0.0D) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 vel = this.getDeltaMovement();
        this.setDeltaMovement(vel.x * retain, -vel.y * retain, vel.z * retain);
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

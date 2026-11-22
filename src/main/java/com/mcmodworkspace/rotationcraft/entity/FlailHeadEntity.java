package com.mcmodworkspace.rotationcraft.entity;

import com.mcmodworkspace.rotationcraft.BallBehavior;
import com.mcmodworkspace.rotationcraft.BallClusterTier;
import com.mcmodworkspace.rotationcraft.BallExtraTraits;
import com.mcmodworkspace.rotationcraft.FlailItem;
import com.mcmodworkspace.rotationcraft.ModComponents;
import com.mcmodworkspace.rotationcraft.ModItems;
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
    /** 已脱手，按自由飞行的弹道运动 */
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

    /**
     * <b>掷出后的回收冷却</b>（作者 2026-10-31：「给流星锤添加一个掷出后 0.5s 不触发回收」）。
     *
     * <p>0.5 秒 = 10 刻。这段时间里既不能手动按住回收、也不会被走近/忠诚自动收线 ——
     * 否则刚甩出去就被自己吸回来，投掷根本没意义。</p>
     */
    public static final int RECOVER_LOCK_TICKS = 10;

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
     * 「回到主人身边」的判定半径（以**玩家本体**为准，不受视线影响）。
     *
     * <p>{@code handAnchor()} 每 tick 随视线与位置重算，拿它当到达目标时玩家一扭头就追不上，
     * 锤头会卡在「脑袋右边」表现为假回收。这条用本体中点做判据，玩家怎么动都收敛。</p>
     */
    private static final double ARRIVE_TO_BODY_DISTANCE = 1.2D;

    /**
     * 移动回收时，**玩家水平移速**额外叠加到收球速度上的比例（作者 2026-10-31 指定 50%）。
     *
     * <p>锤头每 tick 朝主人的位移 = 基础回收速度 + 玩家移速 × 这个比例。</p>
     * <p>玩家跑得越快锤头回得越快，边跑边收才追得上（铁球基础只有 0.21 格/tick，
     * 而玩家疾跑是 0.28）。</p>
     */
    private static final double RETURN_SPEED_BONUS_RATIO = 0.5D;



    /**
     * 松弛绳段的收线速度（格/tick，作者 2026-10-31 提出）。
     *
     * <p>回收开始时 ropeLimit 从 {@link #MAX_LINE} 起算，此刻绳子对锤头其实是松的，
     * 锤头并不受约束。这一段用极快的速度把松弛量收掉，直到 ropeLimit 追平实际距离 ——
     * 也就是绳子绷紧那一刻。之后才进入「锤头拖着绳子」的正常回收速度。</p>
     */
    private static final double SLACK_TAKE_UP_SPEED = 4.0D;

    /**
     * 手动回收时叠加到收线速度上的倍率（作者 2026-11-01：「回收动作只会叠加【忠诚】锤头的回收速度」）。
     *
     * <p>自动回收（【忠诚】/【空气动力球】）自己慢慢收；玩家按住右键时**不打断它**，
     * 只是在原有速度上再叠一层，收得更快。</p>
     */
    private static final double MANUAL_RECOVER_BOOST = 2.5D;

    /** 玩家此刻是否按着右键在手动收线（由 use() 置位、松手/finishReturn 清除）。 */
    private boolean manualRetrieving;

    /**
     * <b>当前绳长上限</b>（格）。回收就是把这条上限一点点收紧（作者 2026-10-31）。
     *
     * <p>锤头始终被约束在「以手部锚点为球心、半径 = 这个值」的球面上。</p>
     * <p>· 飞行阶段 = {@link #MAX_LINE}（30 格，绳子拉满就拽住）；</p>
     * <p>· 回收阶段 = 从当前实际距离一路收紧到 0 —— <b>这就是「回收」</b>：</p>
     * <p>  玩家把绳子往回收，锤头被绳子拽着带回来，而不是自己飞回来。</p>
     */
    /**
     * <b>绳子的最大长度上限</b>（格）—— 可变，作者 2026-11-01：「这个上限不要写成不可改变的，
     * 后边可能会用到」。</p>
     *
     * <p>默认取 {@link FlailItem#MAX_LINE_LENGTH}（30 格）。后续要加「更长的绳子」这类机制
     * （附魔、词条、材料差异……）时，只要调 {@link #setMaxRopeLength(double)}，
     * 约束、拖拽、回收全部自动跟着变，不需要再改别处。</p>
     */
    private double maxRopeLength = FlailItem.MAX_LINE_LENGTH;
    private double ropeLimit = FlailItem.MAX_LINE_LENGTH;

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
     * <b>带【尖啸】的锤头专用的「判定线长」</b>（作者 2026-11-18 指定）。
     *
     * <p>【尖啸】的射线距离上限是 {@code BallExtraTraits.SHRIEK_MAX_RANGE = 256} 格，
     * 而 {@code shriek()} 是「把整条轨迹算完、球直接停到落点」——
     * 于是锤头一触发尖啸，位置直接跳到最多 256 格外，{@code lineLength()} 远远越过 30。
     * 之后它又被绳子约束拉回 30 格球面上，于是「刚过 30 判一次、拉回来、
     * 下一 tick 又是 30.x」反复触发，线长上限与回归的时序就错乱了。</p>
     *
     * <p>所以给带【尖啸】的锤头把**判定**阈值放宽到 40 格留出余量。
     * <b>实际约束仍是 30 格</b>（{@code maxRopeLength} / {@code ropeLimit} 都不动），
     * 物品面板上的「线长 30」也照旧来自 {@code FlailItem.MAX_LINE_LENGTH} ——
     * 玩家看到的数字、和锤头真正停下的位置，都没有变。</p>
     */
    private static final double SHRIEK_SNAP_THRESHOLD = 40.0D;

    /** 本锤头的判定线长；{@code < 0} 表示还没按 profile 算过（首次 tickFlying 时定值）。 */
    private double snapThreshold = -1.0D;

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

    /** 手动回收标志：按住右键置 true，松手或回收结束时清 false。 */
    public void setManualRetrieving(boolean value) {
        this.manualRetrieving = value;
    }

    public boolean isManualRetrieving() {
        return this.manualRetrieving;
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
                // ===== 回收：**只由绳子驱动**（作者 2026-11-01：「【忠诚】老是一顿一顿的并且飞得特别慢」）=====
                //
                //   原来这里是 super.tick() + tickReturning()：
                //     · super.tick() 里有原版重力 → 每 tick 把锤头往下拉 0.08、move() 下移一点；
                //     · 紧接着 tickReturning() 的约束又 setPos 把它拉回球面。
                //   两者每 tick 对着拉一次，表现就是「一顿一顿」。
                //
                //   回收的位移本来就完全由 ropeLimit 收紧 + applyRopeConstraint 决定，
                //   不需要重力参与 —— 关掉它，绳子说了算。
                //   （不会因此悬空：约束会一路把它拉到主人手边。）
                this.setNoGravity(true);
                tickReturning();
            }
            case FLYING -> {
                this.noPhysics = false;
                super.tick();      // 父类 tick（含 BallPhysicsCore 那层的词条与静止判定）
                tickFlying();

                // ===== 掷出者走近 ⇒ 自动开始回收（作者 2026-10-30 指定）=====
                //
                //   不必按住右键：人走到锤头旁边（碰撞箱相交）就自动收线。
                //   这和「按住右键回收」不冲突 —— 那条走 use 系统的 setRetrieving，
                //   这条只是把 RETURNING 状态踢起来，之后 tickReturning 照常拉。
                if (this.tickCount >= RECOVER_LOCK_TICKS
                        && this.getOwner() instanceof LivingEntity autoOwner
                        && this.getBoundingBox().inflate(0.2D)
                                .intersects(autoOwner.getBoundingBox())) {
                    this.beginReturning(autoOwner);   // 会扣 1 耐久，与手动回收一致
                    this.setRetrieving(true);
                }
            }
        }

        // ===== 统一收尾：方块碰撞解算（作者 2026-10-31：「下沉别忘了修好」）=====
        //
        // 上面三个分支都会用 setPos 直接改坐标（环绕 / 收线 / 位移），
        // 而 setPos 是**跳过方块碰撞**的 —— 坐标落在方块里时没有任何东西会把锤头推出来，
        // 于是它嵌进地面、每 tick 触发 onHit、一直扣耐久，这就是「下沉」。
        //
        // 这里是**唯一的兜底点**：不管上面谁改过位置，收尾都做一次零位移的 move()，
        // 它的碰撞解算会把嵌进方块的部分推出去。一次写在这里，比每个分支各修一遍可靠。
        //
        // ⚠️ 只在 noPhysics == false 时做 —— SPIN / RETURNING 是刻意穿墙的，
        //    那两段不参与方块碰撞。
        if (!this.noPhysics) {
            this.move(net.minecraft.world.entity.MoverType.SELF, Vec3.ZERO);
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
    /** 松手：按弹道飞出（作者：与普通投掷物一致的运动方式） */
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

        // ② 按弹道飞出：物理速度 × 蓄力倍率
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
        // ⚠️ **必须在这里把 noPhysics 放开**（作者 2026-10-31：「首次掷出后还是会下坠」）。
        //
        //   launch() 是从 SPIN 阶段调过来的，而 SPIN 期间 noPhysics = true（环绕靠 setPos，
        //   不需要方块碰撞）。如果不在这一刻恢复，出手后**第一 tick 的 move() 不做碰撞解算**，
        //   锤头会直接穿过地面往下掉一格 —— 看起来就是「首次掷出下坠」。
        //
        //   之后 tick() 的 FLYING 分支也会设 false，但那是下一 tick 的事，来不及。
        this.noPhysics = false;
        this.ropeLimit = this.maxRopeLength;      // 绳子拉满 30 格
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
        // ⚠️ **必须在掷出者碰撞箱之外**（作者 2026-11-01：「首次掷出后还是会下坠」）。
        //
        //   父类的 tick 是这样移动的（ThrowableProjectile.tick）：
        //     HitResult r = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        //     setPos(r.getType() != MISS ? r.getLocation() : position().add(getDeltaMovement()));
        //   它会把位置**直接设成命中点**，也不看 noPhysics。
        //
        //   出手点原来只往前 0.35 格 —— 玩家碰撞箱半宽就有 0.3，射线第一个 tick 立刻命中自己，
        //   位置被钉住、速度被吃掉，看起来就是「刚扔出去就往下掉」。
        // 0.8 格就能稳定脱出碰撞箱，同时还在视线正前方，不会改变手感。
        return shooter.getEyePosition()
                .add(look.scale(0.8D))
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
     * <b>锤头只在 FLYING 阶段允许「回归」</b>（作者 2026-11-01：
     * 「会回归的球没法蓄力了，应该是蓄力阶段转圈的时候就被回收了」）。
     *
     * <h2>为什么需要这道闸</h2>
     * <p>{@code homingToOwner0()} 是在 {@code ballTickPost()} 里被调的，而 SPIN /
     * RETURNING 两段**也跑 {@code super.tick()} → {@code ballTickPost()}**。
     * 上一版我把 {@code tickFlying} 里的自动回收改成「置 {@code returnToOwner} 就交给回归」，
     * 但没限定阶段 —— 于是带【忠诚】/【空气动力球】的锤头<b>一进入蓄力（SPIN）就被判成该回家</b>，
     * 转着圈就往主人身上扑，压根蓄不了力。</p>
     *
     * <p>三个阶段该不该回归：</p>
     * <ul>
     *   <li><b>SPIN</b> —— 不该。位置由公式算、正在蓄力，回家就是打断蓄力</li>
     *   <li><b>FLYING</b> —— <b>该</b>。这才是「扔出去之后落定、自己飞回来」</li>
     *   <li><b>RETURNING</b> —— 不该。那已经是线在拽了，两套并存会打架</li>
     * </ul>
     */
    @Override
    protected boolean homingToOwner0() {
        if (this.stage() != Stage.FLYING) {
            return false;
        }
        return super.homingToOwner0();
    }

    /**
     * <b>锤头到达主人时的动作 = 收线归位，而不是「把球交出去」</b>
     * （作者 2026-11-01：「回归和回收不是一个概念」）。
     *
     * <h2>★ 锤头必须有「回归」</h2>
     * <p>「回归」= 词条/附魔触发的<b>自动飞回</b>（{@code homingToOwner0}，和球同一套）——
     * 【忠诚】【空气动力球】靠它。原先前任为了修「回响流星锤撞到掷出者时在地上掉一个
     * 回响球」（1.2.0.51），把 {@code homingToOwner0()} 整个覆写成 {@code return false}，
     * <b>等于把锤头的回归彻底关掉了</b> —— 只剩「线拽着收回来」那条路，
     * 于是回归变成走线的速度，又慢又顿。</p>
     *
     * <h2>正确的做法：保留回归，只换到达动作</h2>
     * <p>掉回响球的根因从来不是「有回归」，而是 {@code deliverToOwner} 的收尾是
     * {@code spawnAtLocation}（把内嵌球丢成掉落物）。锤头到家时该做的是
     * {@link #finishReturn}（归还手上那把流星锤），所以这里只覆写这一个方法：
     * 回归的<i>飞行动作</i>完整继承（速度、加速、穿墙、到达判定都是球那一套），
     * 到家那一刻换成收线归位。两条路不再打架。</p>
     */
    @Override
    protected void deliverToOwner(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.world.entity.LivingEntity owner) {
        this.finishReturn(owner);
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
     * <b>距离约束 —— 唯一实现点，处处复用。</b>
     *
     * <p>作者 2026-10-31：「回收的时候不就相当于掷出者拽着绳子让绳子持续处于最大长度
     * 来带回锤头吗？……这一段代码写好了可以很多地方复用的。」</p>
     *
     * <p>把锤头钳制在「以手部锚点为球心、半径 {@link #ropeLimit}」的球面上：</p>
     * <ol>
     *   <li><b>位置投影</b> —— 超出就拉回球面；</li>
     *   <li><b>速度径向清零</b> —— 砍掉朝外的分量（绳子不可拉伸），切向保留（链球摆动）；</li>
     *   <li><b>碰撞解算</b> —— 投影后可能落在方块里，用零位移 move() 推出来。</li>
     * </ol>
     *
     * <p>回收不需要「让锤头飞」：只要<b>把 ropeLimit 一点点收紧</b>，
     * 这个约束就会把锤头拽回来；玩家移动时锚点移动，锤头自然被带着走。</p>
     *
     * <p>飞行阶段用 {@link #MAX_LINE} 当上限，同一个函数就实现了「超出线长被拉动」。</p>
     */
    /** 当前绳子的最大长度上限（格）。 */
    public double maxRopeLength() {
        return this.maxRopeLength;
    }

    /**
     * 设置绳子的最大长度上限（格）。
     *
     * <p>给后续机制留的入口：附魔/词条/etc 想让绳子更长，调这里就行，
     * 约束与回收会自动跟随。</p>
     *
     * <p>下限保护到 1 格；如果当前正在回收（{@code ropeLimit} 已经收得更短），
     * 不会因为调大上限而把它撑回去。</p>
     */
    public void setMaxRopeLength(double value) {
        this.maxRopeLength = Math.max(1.0D, value);
        if (this.ropeLimit > this.maxRopeLength) {
            this.ropeLimit = this.maxRopeLength;
        }
    }

    private void applyRopeConstraint() {
        if (!(this.getOwner() instanceof net.minecraft.world.entity.LivingEntity ropeOwner)) {
            return;
        }
        Vec3 anchor = handAnchor(ropeOwner);
        Vec3 delta = this.position().subtract(anchor);
        double distance = delta.length();
        if (distance <= this.ropeLimit || distance < 1.0E-4D) {
            return;
        }

        Vec3 out = delta.scale(1.0D / distance);            // 锚点 → 锤头

        // ① 位置投影回球面
        Vec3 clamped = anchor.add(out.scale(this.ropeLimit));
        this.setPos(clamped.x, clamped.y, clamped.z);

        // ② 速度：砍掉朝外的分量，保留切向（链球摆动）
        Vec3 velocity = this.getDeltaMovement();
        double radialOut = velocity.dot(out);
        if (radialOut > 0.0D) {
            this.setDeltaMovement(velocity.subtract(out.scale(radialOut)));
        }

        // ③ 投影后再解算一次方块碰撞 —— 把嵌进方块的部分推出来（防下沉）
        if (!this.noPhysics) {
            this.move(net.minecraft.world.entity.MoverType.SELF, Vec3.ZERO);
        }
    }

    private void tickFlying() {
        // ===== 锤头自己的「落定」判定（作者 2026-11-01：「球落地就是落地了，锤头却会往方块里钻」）=====
        //
        //   球靠 BallPhysicsCore 里这套严格条件进静止态：
        //     impulseTicks <= 0 && wisdomHitGrace <= 0 && hasSupportBelow()
        //       && deltaMovement.lengthSqr() < SETTLE_SPEED_SQR(0.0009)
        //   球在 super.tick() 里被 move() 拦停后垂直速度归零，进这一判时速度≈0，所以能过。
        //
        //   锤头过不了：撞地那一刻速度确实归零，但下一 tick 重力又给它 0.08 的向下速度，
        //   0.08^2 = 0.0064 远大于 0.0009 —— 条件永远凑不齐，静止态进不去。
        //   于是它每 tick 被往下压一点、被方块挡住、再往下压，表现为「一直往方块里钻」。
        //   而且这是「掷出那一次」才明显：后面回收流程会把它拉到球面上，位置动过就恢复正常。
        //
        //   这里给锤头补一条自己的判定：只要**已经站在地上**且水平几乎不动，就直接进静止。
        //   阈值比球宽一点（0.04 格/tick），因为锤头比球重、落地那一下残留速度更大。
        if (this.onGround()
                && this.getDeltaMovement().horizontalDistanceSqr() < 0.0016D) {
            // 站在地上且水平几乎不动 ⇒ 把速度清掉。
            // 下一步 move() 会挡住重力，位置就不会再往下走。
            // （注意：这里不能调 setSettled() —— 它是父类的 private 方法。）
            this.setDeltaMovement(Vec3.ZERO);
        }

        // ===== ⓪ 卡进方块时先脱离（作者 2026-10-30：「扔出会沉到方块里」）=====
        //
        //   FLYING 阶段 noPhysics=false，正常吃方块碰撞；但如果某一刻被推进实心区域
        //   （高速度穿过薄墙、被活塞推、或者在墙角爆炸），就会一直嵌在里面 ——
        // ===== 落地静止交给球的 settled 机制（作者 2026-10-31：从根源改）=====
        //
        // 这里**不要**自己调 setNoGravity(true)。`BallPhysicsCore.setSettled()` 有这条：
        //
        //     if (settled && this.isNoGravity()) { SETTLED = false; return; }
        //
        // 无重力时它会**拒绝进入静止态**，于是父类 tick 里的重力继续每 tick 往下压，
        // 把锤头一路往方块里压 —— 1.2.0.61/62 加的 setNoGravity(true) 就是这么把
        // 「偶尔下沉」变成「必定下沉」的。
        //
        // 正确做法：什么都不干预。锤头落地后由球的 settled 接管 ——
        //   · 「坐实」那 5 刻只保留向下分量；
        //   · 真正静止后，除向下以外的速度全部归零；
        //   · hasSupportBelow() 维持住静止态，不会沉进方块。
        // 球没有耐久看不到这个，锤头只要不打断它就跟球一样。

        this.setLineLength(distanceToOwner());



        // ===== ① 线长上限：扣 1 耐久 + 完成一次弹射 + 朝掷出者的小速度 =====
        //
        // 判定阈值：带【尖啸】的锤头用 40 格（见 SHRIEK_SNAP_THRESHOLD 的说明），
        // 其余球种仍是自己的 maxRopeLength（默认 30）。
        // ⚠️ 这里只放宽**判定**，实际约束仍由 ropeLimit / maxRopeLength 管，都是 30 格。
        if (this.snapThreshold < 0.0D) {
            this.snapThreshold = this.profile().hasFlag(BallBehavior.BallProfile.FLAG_SHRIEK)
                    ? SHRIEK_SNAP_THRESHOLD
                    : this.maxRopeLength;
        }
        if (this.lineLength() >= this.snapThreshold && !this.lineCapConsumed) {
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
        // 【空气动力球】/【忠诚】/【魔法·回收】在球身上的语义是「自己飞回主人」。
        // 锤头没有「飞回主人」这回事 —— 它对应的是**收线**，所以这里直接开始回收。
        //
        // ⚠️⚠️ **必须把物品上的 FLAIL_RETRIEVING 也一起写上**（作者 2026-10-31：
        //    「回响流星锤在由【忠诚】自动回收时，有时会出现半成功，锤头已经回来了，
        //     但是还需要按一下右键才会归位」）。
        //
        //    锤头的「回收」状态有两份、彼此独立：
        //      · 实体字段 RETRIEVING —— tickReturning 靠它决定要不要拉；
        //      · 物品组件 FLAIL_RETRIEVING —— 收尾（清标记、恢复形态、归位）靠它。
        //
        //    手动回收走 use()，两条都写了；这里原来**只写了实体那条**，
        //    于是 FlailCastHandler 的收尾逻辑（它只看物品组件）对自动回收完全不生效：
        //    锤头飞回来了、实体也 discard 了，物品却停在「扔出中」的形态，
        //    非得再按一次右键（走一遍手动路径）才把状态补齐。
        // ===== 自动返回类词条 ⇒ 交给「回归」，**不是**收线回收 =====
        //
        // ⚠️⚠️ 作者 2026-11-01：「回收和回归不是一个概念」。
        //
        //   · **回归** = 【忠诚】/【魔法】/【空气动力球】触发的**自己飞回主人** ——
        //     走 {@code homingToOwner0()}，和球完全是同一套（加速、穿墙、到达判定），
        //     速度快、连续。
        //   · **回收** = 掷出者拽着绳子把锤头拉回来 —— 走 {@code beginReturning} +
        //     {@code tickReturning}，那是「线的收线速度」，本来就慢。
        //
        //   这里原先写的是 `if (settleReady && autoRetrieveWanted()) beginReturning(...)`
        //   —— 于是带这三个词条的锤头一落定就被拽进**回收**，回归永远轮不到，
        //   表现就是「回归非常非常慢」（其实是走了收线）。
        //
        //   现在只把 `returnToOwner` 置位，让它走球那条回归；回归到家时由
        //   {@link #deliverToOwner} 换成 {@link #finishReturn} 收线归位。
        if (this.stage() == Stage.FLYING && this.autoRetrieveWanted()) {
            this.setReturnToOwner(true);
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
            // ===== 绳子约束：**唯一的实现点**（作者 2026-10-31）=====
            //
            // 作者原话：「为什么不从根源解决，让线真的拥有一个上限长度，
            // 达到上限后启用线的物理逻辑呢？你这样修要修到什么时候去」
            //
            // 对。「线长上限」本来就该是一条**约束**，而不是散落在各处的特判。
            // 所以这里只调一次 applyRopeConstraint() —— 位置钳制、速度径向清零、
            // 碰撞解算全在它内部，别的地方不再重复实现。
            this.applyRopeConstraint();

        // ⚠️⚠️ **落定检查必须放在线长处理之后**（作者 2026-10-31：「线长上限时拉动锤头的设计
        //    没有实现，现在还是超出距离没效果」）。
        //
        //    1.2.0.63 把它放在 tickFlying 开头，锤头一落地静止就 return，
        //    下面「到达线长上限」与「径向约束」全被跳过 —— 超长时既不会弹回也不会被拽回。
        //
        //    现在它只挡「落定之后不该做的事」（自动收线等），线长那两条永远执行。
        if (this.isSettled()) {
            return;
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
                                        .getOrThrow(com.mcmodworkspace.rotationcraft.ModEnchantments.AERODYNAMIC_BALL),
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
        // 回收开始：绳长上限从**当前实际距离**开始收紧（不是从 30 格）
        // 回收开始：绳长上限从最大值起算（作者 2026-10-31 进阶思路）
        this.ropeLimit = this.maxRopeLength;
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
        // ===== 到达判定（作者 2026-10-31：「会挂在掷出者脑袋右边导致假回收」）=====
        //
        // 原来只有两条：盒相交、或「到 handAnchor 的距离 < 0.05」。
        //
        // 但 `handAnchor()` 是**每 tick 重算**的：
        //     owner.position() + right(0.35) + (0, bbHeight * 0.75, 0)
        // 它的 right 跟着视线走、原点跟着玩家走 —— 玩家一扭头或一走，锚点就挪开，
        // 锤头永远差那么一点点，于是**停在「脑袋右边」那个位置不动**，
        // 表现为「回收假成功」：实体还在、线还连着、物品也没归位。
        //
        // 所以补一条**以玩家本体为准**的判据：只要靠近到 1.2 格就算到位。
        // 这个距离与视线、朝向都无关，玩家怎么动都收敛得过去。
        double toBody = living.position()
                .add(0.0D, living.getBbHeight() * 0.5D, 0.0D)
                .distanceTo(this.position());
        if (this.getBoundingBox().intersects(living.getBoundingBox())
                || distance < 0.05D
                || toBody < ARRIVE_TO_BODY_DISTANCE) {
            this.finishReturn(living);
            return;
        }
        // 线就跟着视觉上被拉长。回收的语义是「把线收回来」，它只该缩短。
        // ⚠️ 线长**不再同步成实际距离**（作者 2026-10-31：「移动回收那里还是不对，换个思路修」）。
        //
        //   原来这里写的是 `setLineLength(min(distance, lineLength()))`：
        //   线长被强行等同于「当前位置到手的距离」，而回收又靠「缩短线长」来实现，
        //   于是两者互相抵消 —— 无论朝主人挪多少，下一 tick 线长又被改回实际距离，
        //   锤头等于原地不动；玩家一跑，实际距离变大，锤头就被拽着走，越走越远。
        //
        //   现在线长是**独立的自由度**：只在回收时递减，由下面的位移段统一维护。

        if (!this.isRetrieving()) {
            // ===== 松手中断回收（作者 2026-11-01：「回收过程中中断会导致绳长拖拽不生效」）=====
            //
            //   根因：松手后锤头**还留在 RETURNING 阶段**，而超长拖拽（applyRopeConstraint）
            //   只在 tickFlying 里调用 —— 于是中断一次之后，线长上限这条逻辑就再也不跑了，
            //   锤头可以拖到任意远。
            //
            //   正确做法：松手 = 这次回收结束，把阶段交还给 FLYING。
            //   之后重力、方块碰撞、超长拖拽全部由正常流程接管，不需要在这里手写落下。
            //   绳长同时放松到实际距离（但不超过上限），避免再次回收时瞬移。
            this.setStage(Stage.FLYING);
            this.ropeLimit = Math.min(this.maxRopeLength,
                    Math.max(this.ropeLimit, this.distanceToOwner()));
            return;
        }

        // ===== 回收 = 每刻直接朝主人推进（原始实现，作者 2026-11-01 要求恢复）=====
        //
        // ⚠️⚠️ **这一段必须「每刻都 setPos 前进」，不能靠 ropeLimit 收紧 + 投影**。
        //
        //   1.2.0.31 我把它改成了「两段式收线」：先收松弛量、绷紧后逐刻收紧 ropeLimit，
        //   再由 applyRopeConstraint() 把锤头投影到球面上。结果是：
        //
        //     tick N   : ropeLimit -= perTick → distance > ropeLimit → 投影 → 前进 perTick
        //     tick N+1 : distance == ropeLimit（上一刻刚投影到那儿）→ **distance <= ropeLimit
        //                → applyRopeConstraint 提前 return** → 位置一动不动
        //     tick N+2 : ropeLimit 又减了 → 再前进 perTick
        //
        //   **两刻才前进一次 = 一动一停；每两刻只走一个 perTick = 速度正好减半。**
        //   这正是作者报的「一动一停一动一停，速度还慢的要死」，而且「在空中也会」——
        //   因为它跟撞不撞方块完全无关，是收线方式本身决定的。
        //
        //   原始写法（1.2.0.23 之前，936 行那版）就是下面这样：每刻算一个 perTick，
        //   直接把位置朝主人挪过去、然后 setDeltaMovement(ZERO) 清掉速度
        //   （因为位移已经由 setPos 给足了，速度留着反而会叠加）。
        double perTick = FlailItem.recoverSpeed(this.profile()) / TICKS_PER_SECOND;
        // 手动按住右键 ⇒ 在自动回收速度上再叠一层（作者 2026-11-01：只叠加，不打断）
        if (this.manualRetrieving) {
            perTick *= MANUAL_RECOVER_BOOST;
        }
        // 别冲过头：最后一小段按剩余距离收缩
        perTick = Math.min(perTick, Math.max(0.05D, distance - ARRIVE_DISTANCE * 0.5D));

        // ⚠️ 绳长上限跟着实际距离走，让 applyRopeConstraint 在「飞行阶段」仍能兜住超长拖拽；
        //    但**不要**用它来推进位置（那正是上面说的隔刻才动的根源）。
        this.ropeLimit = Math.min(this.maxRopeLength,
                Math.max(this.ropeLimit, distance));

        Vec3 dir = target.subtract(this.position()).normalize();
        Vec3 next = this.position().add(dir.scale(perTick));
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
            // ===== 收尾：把物品状态一次清干净（作者 2026-10-31）=====
            //
            // 作者判断得很准：「碰撞箱碰到后判定了回收，锤头实体消失，
            // 但是因为在向左移动导致分开，没来得及更新物品状态」——
            // 本质就是**实体已经 discard、物品状态却停在「扔出中 / 回收中」**。
            //
            // 原来这里会无条件 `markRetrieving(true)`（为了防「按着右键时显示蓄力字样」），
            // 而它在 FlailCastHandler 里只在 `!isUsingItem` 时清 ——
            // 玩家按着右键就把这个状态永久挂住了。
            //
            // 现在改成：**不设 RETRIEVING，只留 RECOVERED**。
            //   · `getUseAnimation` 里把 RECOVERED 也算作「不做蓄力动作」，
            //     所以按着右键同样不会显示蓄力字样；
            //   · RECOVERED 由 FlailCastHandler 在松手时清，语义单一、不会互相打架。
            net.minecraft.world.item.ItemStack heldAfter = p.getMainHandItem();
            if (FlailItem.isFlail(heldAfter)) {
                FlailItem.markRetrieving(heldAfter, false);
                FlailItem.markRecovered(heldAfter, true);
                FlailItem.setCasting(heldAfter, false);
            }
            p.containerMenu.broadcastChanges();
            p.getInventory().setChanged();
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
        int max = com.mcmodworkspace.rotationcraft.FlailItem.flailMaxToughness(this.flailStack());
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
        // 交给 BallPhysicsCore 那层的完整命中逻辑（伤害 + 全部词条）
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
        // ===== 方块命中 ⇒ 沿法线弹出（作者 2026-11-01）=====
        //
        //   ⚠️ **只能有一段**：这个弹出会挪动「半个碰撞箱 + 0.02」格。
        //   写成两段就会推两次（0.54 格），落地后被弹起半格多、再落再弹 ——
        //   表现就是抖动 / 看起来还在往下钻。
        //
        //   父类的移动方式是射线：
        //     HitResult r = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        //     setPos(r.getType() != MISS ? r.getLocation() : ...);
        //   命中点就在**方块表面**上，而它是把锤头的**中心**设到那个点 ——
        //   碰撞箱有 0.5 格，于是有一半埋在方块里，看上去就是「砸在地上沉进去」。
        //   而且之后每 tick 都判定成卡在方块中，一直扣耐久。
        //
        //   这里沿命中面的法线把它推出去「半个碰撞箱 + 一点余量」，再把速度清零：
        //   速度归零之后下 tick 的射线长度就是 0，不会再钉回方块里。
        if (!this.level().isClientSide() && hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
            net.minecraft.core.Direction face = blockHit.getDirection();
            Vec3 nudge = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ())
                    .scale(this.getBbHeight() * 0.5D + 0.02D);
            this.setPos(this.position().add(nudge));
            this.setDeltaMovement(Vec3.ZERO);
        }

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
            int max = com.mcmodworkspace.rotationcraft.FlailItem.flailMaxToughness(this.flailStack());
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

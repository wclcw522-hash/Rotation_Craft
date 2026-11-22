package com.mcmodworkspace.rotationcraft;

import com.mcmodworkspace.rotationcraft.entity.BallPhysicsCore;
import com.mcmodworkspace.rotationcraft.entity.BallProjectile;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>狗（Wolf）的捡球 / 拦截行为</b> —— 作者 2026-11-19 指定的整套规格。
 *
 * <h2>一、捡球</h2>
 * 驯服的狗主动找主人周围 <b>12 格</b>内「静止的球」与「掉落物形态的球」，
 * 锁最稀有的那颗（{@link BallProfile#rarity()} 越小越稀有，0 最稀有；同权重随机），
 * 主动靠近并 100% 捡起、叼在嘴里，然后回主人身边把球<b>以掉落物形态</b>抛给主人。
 *
 * <p>组合球的稀有度单独算（见 {@link #rarityOf}）：<b>任何一份组件的 rarity 是 0
 * ⇒ 整颗视为 0</b>；否则取各份的<b>算术平均</b>。</p>
 *
 * <h2>二、血量分档</h2>
 * <table border="1">
 *   <tr><th>血量</th><th>捡球 CD</th><th>攻击行为</th></tr>
 *   <tr><td>&gt; 60%</td><td>5 秒</td><td>CD 期间正常辅助攻击与反击</td></tr>
 *   <tr><td>25% ~ 60%</td><td>3 秒</td><td>CD 期间<b>不主动攻击</b>，仅协助反击；捡球优先</td></tr>
 *   <tr><td>&lt; 25%</td><td>1.5 秒</td><td><b>不攻击也不反击</b>；持有球时额外获得速度 II</td></tr>
 * </table>
 * <p><b>装备狼铠时无视血量，一律按 &gt; 60% 档处理</b>（作者指定）。</p>
 *
 * <h2>三、拦截</h2>
 * 有球飞向主人时，狗会把自己插到「球 → 主人」的连线上并起跳，<b>被球命中才算拦截</b>。
 * 成功率按<b>难度</b>：简单 100% / 普通 90% / 困难 75%（作者 2026-11-19 明确）。
 * 成功时狗不掉血、球的<b>命中类</b>效果被沉默（轨迹类如【感应】不受影响）；
 * 失败时按正常命中结算。
 *
 * <p>拦截 CD 每只狗独立 <b>10 秒</b>，从接到球那刻起算；完成一次拦截时
 * <b>拦截 CD 与捡球 CD 一起重置</b>（因为狗要回去吐球）。</p>
 */
@EventBusSubscriber(modid = RotationCraft.MOD_ID)
public final class DogBallBehavior {

    private DogBallBehavior() {
    }

    // ===== 扫描与距离 =====

    /** 捡球扫描半径（格）—— 作者指定 12 */
    private static final double SCAN_RADIUS = 12.0D;

    /** 叼着球时，离主人这么近就把球抛出去 */
    private static final double DELIVER_DISTANCE = 2.5D;

    /** 拦截时，判定「球的目标是不是狗的主人」的允许误差（格） */
    private static final double INTERCEPT_PATH_RADIUS = 8.0D;

    /** 只拦这么近、且还在朝主人飞的球（太远来不及，纯属白跑） */
    private static final double INTERCEPT_BALL_DIST = 28.0D;

    /** 球速低于这个值就不算「正在飞」，没有拦截意义 */
    private static final double INTERCEPT_MIN_SPEED = 0.15D;

    // ===== CD（刻）=====

    /** > 60% 档：捡球 CD 5 秒 */
    private static final int CD_PICKUP_HIGH = 100;
    /** 25%~60% 档：捡球 CD 3 秒 */
    private static final int CD_PICKUP_MID = 60;
    /** < 25% 档：捡球 CD 1.5 秒 */
    private static final int CD_PICKUP_LOW = 30;
    /** 拦截 CD 10 秒（每只狗独立） */
    private static final int CD_INTERCEPT = 200;

    // ===== 效果 =====

    /** 拦截成功后给的速度 V + 跳跃提升 III，持续 3 秒 */
    private static final int INTERCEPT_BUFF_TICKS = 60;
    private static final int INTERCEPT_SPEED_AMP = 4;   // 速度 V = amplifier 4
    private static final int INTERCEPT_JUMP_AMP = 2;    // 跳跃提升 III = amplifier 2

    /** < 25% 档、且嘴里有球时的速度 II */
    private static final int LOW_HP_SPEED_AMP = 1;      // 速度 II = amplifier 1

    // ===== 移动 =====

    /** 走近球 / 走近主人时的寻路速度倍率 */
    private static final double NAV_SPEED = 1.1D;

    /** 判定「已经到球边上、可以捡了」的水平距离 */
    private static final double PICKUP_REACH = 1.6D;

    /** 追球（拦截）时的寻路速度倍率 —— 比平时快一点，配合速度 V 才追得上 */
    private static final double INTERCEPT_CHASE_SPEED = 1.6D;

    /** 起跳判定：水平距离在这么多格以内 */
    private static final double INTERCEPT_JUMP_HORIZ = 2.5D;

    /** 起跳判定：球比狗高出这么多格才值得跳（平飞的球不需要跳） */
    private static final double INTERCEPT_JUMP_HEIGHT = 1.0D;

    /**
     * 实时预判的**上限时长**（刻）—— 最多往前算 1 秒。
     *
     * <p>不封顶的话，球离得很远时「距离 / 狗速」会算出十几秒，
     * 预判点直接被推到天边，狗会朝一个毫无意义的方向狂奔。</p>
     */
    private static final double INTERCEPT_MAX_LEAD_TICKS = 20.0D;

    /**
     * 血量档位（作者 2026-11-19 指定）。
     *
     * <p><b>装备狼铠时直接返回 {@link #HIGH}</b> —— 无视当前血量。
     * 狼铠用原版 {@code Wolf#isWearingBodyArmor()} 判断，26.2 自带。</p>
     */
    public enum Tier {
        /** > 60%，或装备狼铠 */
        HIGH,
        /** 25% ~ 60% */
        MID,
        /** < 25% */
        LOW
    }

    /** 当前档位 */
    public static Tier tierOf(Wolf wolf) {
        if (wolf.isWearingBodyArmor()) {
            return Tier.HIGH;   // 作者指定：穿狼铠一律按高血量档
        }
        float max = wolf.getMaxHealth();
        if (max <= 0.0F) {
            return Tier.HIGH;
        }
        float ratio = wolf.getHealth() / max;
        if (ratio > 0.60F) {
            return Tier.HIGH;
        }
        if (ratio > 0.25F) {
            return Tier.MID;
        }
        return Tier.LOW;
    }

    /** 该档位的捡球 CD（刻） */
    public static int pickupCdFor(Tier tier) {
        return switch (tier) {
            case HIGH -> CD_PICKUP_HIGH;
            case MID -> CD_PICKUP_MID;
            case LOW -> CD_PICKUP_LOW;
        };
    }

    /**
     * 拦截成功率 —— <b>按难度</b>（作者 2026-11-19 明确：简单 100% / 普通 90% / 困难 75%）。
     *
     * <p>注意与 {@code BallMobAI} 的「怪接球概率」方向相反：那边越难越会接，
     * 这里是<b>越难越容易拦不住</b>（作者指定）。</p>
     */
    public static float interceptChanceFor(Level level) {
        return switch (level.getDifficulty()) {
            case PEACEFUL, EASY -> 1.00F;
            case NORMAL -> 0.90F;
            case HARD -> 0.75F;
        };
    }

    // ==================================================================
    //  主循环
    // ==================================================================

    @SubscribeEvent
    public static void onWolfTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Wolf wolf)) {
            return;
        }
        Level level = wolf.level();
        if (level.isClientSide() || !wolf.isAlive() || !wolf.isTame()) {
            return;
        }
        if (!(wolf.getOwner() instanceof Player owner) || !owner.isAlive()) {
            return;
        }

        Tier tier = tierOf(wolf);
        long now = level.getGameTime();
        ItemStack held = wolf.getData(ModAttachments.DOG_HELD_BALL.get());

        // ===== ⓪ 血量档位对「攻击行为」的约束（作者 2026-11-19 指定）=====
        //
        //   > 60%  ⇒ 正常辅助攻击与反击
        //   25~60% ⇒ 捡球 CD 期间**不主动攻击**，仅协助反击（被打才还手）
        //   < 25%  ⇒ **不攻击也不反击**
        //
        // 「反击」的判据用原版的 lastHurtByMob：那是「打我的是谁」，
        // 和主动索敌（getTarget 由 AI 设）是两回事 —— 所以中档只清掉「不是打我的人」的目标。
        applyAggressionTier(wolf, tier, now);

        // ===== ① 嘴里有球 ⇒ 送回主人身边并抛给他 =====
        if (!held.isEmpty()) {
            deliverToOwner(wolf, owner, held, now, tier);
            return;
        }

        // ===== ② 拦截：优先于捡球（球正在飞向主人，更紧急）=====
        //
        // ⚠️ 作者 2026-11-19：「在拦截 cd 的时候就不要让狗做出拦截动作了」。
        //
        //   两层都要管：
        //     · CD 期间**不进** tryStartInterceptChase（不追、不站到路线上）
        //     · CD 期间**清掉拦截时给的加速/跳跃**（它们只活 3 秒，但 CD 有 10 秒，
        //       这 3 秒里狗还在高速跑跳，看上去就像「还在拦」）
        //
        //   清的时候**只清等级对得上的**（amplifier 正好等于拦截给的那档）——
        //   低血量档给的速度 II 是另一档，不会被误删。
        long interceptReadyAt = wolf.getData(ModAttachments.DOG_INTERCEPT_READY_AT.get());
        if (now < interceptReadyAt) {
            clearInterceptBuff(wolf);
        } else if (!wolf.isOrderedToSit() && tryStartInterceptChase(wolf, owner)) {   // 坐着的狗不拦（作者 2026-11-22）
            return;
        }

        // ===== ③ 捡球 =====
        //
        // ⚠️ 坐着的狗也不捡（作者 2026-11-22：「一起禁」）。
        //    「坐下」在玩家心里就是「别动」，如果它还会跑去捡球，这个命令等于没有。
        //
        //    ① 吐球那条**不**受这条限制（见上面 ① —— 嘴里叼着球得先交出去，
        //    不然球就永远含在嘴里了）。
        if (wolf.isOrderedToSit()) {
            return;
        }
        if (now < wolf.getData(ModAttachments.DOG_PICKUP_READY_AT.get())) {
            return;   // 还在 CD 里
        }
        tryPickup(wolf, owner, tier, now);
    }

    // ==================================================================
    //  血量档位 → 攻击行为
    // ==================================================================

    /**
     * 按血量档位压制狗的攻击行为（作者 2026-11-19）。
     *
     * <p>「主动攻击」和「反击」在这里是分开的：</p>
     * <ul>
     *   <li>主动攻击 = AI 自己索敌设的 {@code getTarget()}</li>
     *   <li>反击 = 被谁打了就还手，判据用原版 {@code getLastHurtByMob()}</li>
     * </ul>
     * <p>所以中档（25~60%）只在「捡球 CD 期间」清掉「不是打我的人」的目标，
     * 保留挨打还手；低档（< 25%）连目标都清空，不攻击也不反击。</p>
     */
    private static void applyAggressionTier(Wolf wolf, Tier tier, long now) {
        switch (tier) {
            case HIGH -> {
                // > 60% 或穿狼铠：什么都不做，保持原版行为
            }
            case MID -> {
                // 只在捡球 CD 期间压制；CD 好了就恢复正常辅助攻击
                if (now < wolf.getData(ModAttachments.DOG_PICKUP_READY_AT.get())) {
                    LivingEntity target = wolf.getTarget();
                    LivingEntity avenger = wolf.getLastHurtByMob();
                    if (target != null && target != avenger) {
                        wolf.setTarget(null);
                    }
                }
            }
            case LOW -> {
                // 不攻击也不反击
                wolf.setTarget(null);
            }
        }
    }

    // ==================================================================
    //  捡球
    // ==================================================================

    /** 找 12 格内最稀有的一颗球并去捡 */
    private static void tryPickup(Wolf wolf, Player owner, Tier tier, long now) {
        Level level = wolf.level();
        AABB box = owner.getBoundingBox().inflate(SCAN_RADIUS);
        List<Candidate> found = new ArrayList<>();

        // 形态一：地上的球实体（已静止）
        for (BallProjectile ball : level.getEntitiesOfClass(BallProjectile.class, box)) {
            if (!ball.isAlive() || !ball.isSettled()) {
                continue;
            }
            ItemStack stack = ball.getItem();
            if (!BallAmmo.isBall(stack)) {
                continue;
            }
            found.add(new Candidate(stack.copyWithCount(1), ball, null));
        }
        // 形态二：掉落物形态的球
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack stack = drop.getItem();
            if (!BallAmmo.isBall(stack) || stack.isEmpty()) {
                continue;
            }
            found.add(new Candidate(stack.copyWithCount(1), null, drop));
        }
        if (found.isEmpty()) {
            return;
        }

        // 锁最稀有的：rarity 越小越稀有；0 最稀有。同权重随机
        int best = Integer.MAX_VALUE;
        for (Candidate c : found) {
            best = Math.min(best, c.rarity);
        }
        List<Candidate> pool = new ArrayList<>();
        for (Candidate c : found) {
            if (c.rarity == best) {
                pool.add(c);
            }
        }
        Candidate pick = pool.get(wolf.getRandom().nextInt(pool.size()));

        // 已经贴到球边上了 ⇒ 直接捡起
        Vec3 pos = pick.position();
        double horiz = Math.hypot(wolf.getX() - pos.x, wolf.getZ() - pos.z);
        if (horiz <= PICKUP_REACH && Math.abs(wolf.getY() - pos.y) <= 2.0D) {
            pickUp(wolf, pick);
            return;
        }
        // 否则寻路过去（下一 tick 再判距离）
        wolf.getNavigation().moveTo(pos.x, pos.y, pos.z, NAV_SPEED);
    }

    /** 真把球叼进嘴里 */
    private static void pickUp(Wolf wolf, Candidate pick) {
        // 捡球是 100% 成功（作者指定），这里不再掷概率
        if (pick.ball != null) {
            pick.ball.discard();
        } else if (pick.drop != null) {
            pick.drop.discard();
        }
        wolf.setData(ModAttachments.DOG_HELD_BALL.get(), pick.stack);
        wolf.getNavigation().stop();
        wolf.level().playSound(null, wolf.getX(), wolf.getY(), wolf.getZ(),
                net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 1.3F);
    }

    /** 叼着球回到主人身边，抛给他 */
    private static void deliverToOwner(Wolf wolf, Player owner, ItemStack held, long now, Tier tier) {
        double dist = Math.hypot(wolf.getX() - owner.getX(), wolf.getZ() - owner.getZ());
        if (dist > DELIVER_DISTANCE) {
            // 还没到 ⇒ 继续往主人那边走
            wolf.getNavigation().moveTo(owner, NAV_SPEED);
            // < 25% 档：嘴里有球时给速度 II（作者指定）
            if (tier == Tier.LOW) {
                wolf.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.SPEED,
                        40, LOW_HP_SPEED_AMP, false, false, true));
            }
            return;
        }

        // 到位 ⇒ 抛出掉落物形态的球
        wolf.setData(ModAttachments.DOG_HELD_BALL.get(), ItemStack.EMPTY);

        Vec3 aim = owner.position().add(0.0D, 0.8D, 0.0D).subtract(wolf.position());
        if (aim.lengthSqr() < 1.0E-6D) {
            aim = new Vec3(0.0D, 0.3D, 0.0D);
        }
        ItemEntity thrown = new ItemEntity(wolf.level(),
                wolf.getX(), wolf.getEyeY() - 0.25D, wolf.getZ(), held.copy());
        thrown.setDeltaMovement(aim.normalize().scale(0.55D));
        thrown.setPickUpDelay(4);   // 给主人一点反应时间，免得狗自己又叼回去
        wolf.level().addFreshEntity(thrown);

        // CD 从「完成一次捡球（丢出球）」开始算（作者指定）
        wolf.setData(ModAttachments.DOG_PICKUP_READY_AT.get(), now + pickupCdFor(tier));
    }

    /**
     * 清掉「拦截时给的那两个 buff」（作者 2026-11-19）。
     *
     * <p>拦截 CD 有 10 秒，而加速/跳跃只活 3 秒 —— 不清的话那 3 秒里狗还在高速跑跳，
     * 玩家看着就像「CD 中还在拦截」。这里在 CD 期间每 tick 检查一遍，
     * 只要等级对得上就移除。</p>
     *
     * <p><b>只认拦截给的那一档</b>（速度 V = amplifier 4、跳跃提升 III = amplifier 2），
     * 所以低血量档给的速度 II（amplifier 1）不会被误删。</p>
     */
    private static void clearInterceptBuff(Wolf wolf) {
        var speed = wolf.getEffect(net.minecraft.world.effect.MobEffects.SPEED);
        if (speed != null && speed.getAmplifier() == INTERCEPT_SPEED_AMP) {
            wolf.removeEffect(net.minecraft.world.effect.MobEffects.SPEED);
        }
        var jump = wolf.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
        if (jump != null && jump.getAmplifier() == INTERCEPT_JUMP_AMP) {
            wolf.removeEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
        }
    }

    // ==================================================================
    //  拦截
    // ==================================================================

    /**
     * 找一颗「正飞向主人」的球，把狗挪到它的飞行路线上。
     *
     * <p>真正的拦截在球打到狗那一刻判定 —— 见 {@link #tryIntercept}。
     * 这个方法只负责「站到路上」。</p>
     *
     * @return true 表示已经在追某颗球（本 tick 不再做别的事）
     */
    private static boolean tryStartInterceptChase(Wolf wolf, Player owner) { if (wolf.isOrderedToSit()) { return false; } // 坐着不追（作者 2026-11-22）
        // ⚠️ 双保险：拦截 CD 期间一律不追（作者 2026-11-19「CD 时不要做出拦截动作」）。
        //    外层 onWolfTick 已经判过一次，这里再判一次是防将来别处调用绕过它。
        if (wolf.level().getGameTime()
                < wolf.getData(ModAttachments.DOG_INTERCEPT_READY_AT.get())) {
            return false;
        }
        Level level = wolf.level();
        AABB box = owner.getBoundingBox().inflate(INTERCEPT_BALL_DIST);
        // 扫的是**所有弹射物**，不只是模组的球 —— 作者 2026-11-19 要求
        // 「无法被拦截的弹射物狗也会试图挡住」。能不能真拦住由命中那一刻判（见 tryIntercept / onIncomingDamage）。
        net.minecraft.world.entity.projectile.Projectile best = null;
        double bestDist = Double.MAX_VALUE;

        for (net.minecraft.world.entity.projectile.Projectile proj
                : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class, box)) {
            if (!proj.isAlive()) {
                continue;
            }
            // ===== 主人自己扔出去的不拦（作者 2026-11-19 指定）=====
            // 包括主人扔的球、主人射的箭 —— 狗不该去抢自己人的东西。
            if (proj.getOwner() == owner) {
                continue;
            }
            Vec3 motion = proj.getDeltaMovement();
            if (motion.length() < INTERCEPT_MIN_SPEED) {
                continue;   // 没在飞
            }
            // 必须「正飞向主人」：弹射物到主人的距离在缩短
            Vec3 toOwner = owner.getEyePosition().subtract(proj.position());
            if (toOwner.length() > INTERCEPT_BALL_DIST) {
                continue;
            }
            if (motion.dot(toOwner) <= 0.0D) {
                continue;   // 方向背离主人
            }
            // 狗得在路径附近
            double lateral = distanceToSegment(wolf.position(),
                    proj.position(), owner.getEyePosition());
            if (lateral > INTERCEPT_PATH_RADIUS) {
                continue;
            }
            double d = proj.distanceToSqr(wolf);
            if (d < bestDist) {
                bestDist = d;
                best = proj;
            }
        }
        if (best == null) {
            return false;
        }

        // ===== 进入拦截动作 ⇒ 立刻给加速（作者 2026-11-19 明确）=====
        //
        // ⚠️ 是「**开始追**」就给，不是「拦成功」才给 —— 狗要靠这个加速才追得上球。
        //   所以放在这里（已经选定目标之后），而不是放在 tryIntercept 里。
        //
        // 每 tick 都会走到，但 addEffect 同强度只是刷新时长 ⇒ 拦截期间一直保持，
        // 追丢了自然在 3 秒后自己消失。
        wolf.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.SPEED,
                INTERCEPT_BUFF_TICKS, INTERCEPT_SPEED_AMP, false, false, true));
        wolf.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.JUMP_BOOST,
                INTERCEPT_BUFF_TICKS, INTERCEPT_JUMP_AMP, false, false, true));

        // ===== 实时预判：瞄球的**未来位置**，不是追尾（作者 2026-11-19）=====
        //
        // ⚠️⚠️ 作者的话分两步：
        //   ①「拦截过程中不要让狗瞬移过去，就让狗根据自己的 buff 计算拦截」
        //   ②「不要追，就实时预判」
        //
        //   所以这里既不是 1.2.0.140 那种**一次性固定预判点**（算在空气里、狗够不到只能
        //   反复起跳，看着像被摆过去），也不是 1.2.0.141 那种**追球屁股**（球一快就永远差一步）。
        //
        //   正确做法：**每 tick 重算一次「球将来会在哪」**，让狗往那个移动中的点跑。
        //   抵达时长按**狗自己的移动能力**估 —— 它手里有速度 V，跑多快就预判多远，
        //   这正是「根据自己的 buff 计算拦截」。
        Vec3 ballPos = best.position();
        Vec3 ballVel = best.getDeltaMovement();

        // 狗的实际水平速度（格/刻）：基础属性 × 速度 V 加成 × 寻路倍率
        double baseSpeed = wolf.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        // 速度 V（amplifier 4）在原版是每级 +20% ⇒ 倍率 1 + 0.2 × (amp+1)
        double speedBuff = 1.0D + 0.2D * (INTERCEPT_SPEED_AMP + 1);
        double dogSpeedPerTick = Math.max(0.05D, baseSpeed * speedBuff * INTERCEPT_CHASE_SPEED);

        // 抵达时长 = 水平距离 / 狗的速度；封顶 1 秒，免得球在天边时算出一个荒唐的点
        double dist = Math.hypot(wolf.getX() - ballPos.x, wolf.getZ() - ballPos.z);
        double ticksToArrive = Math.min(INTERCEPT_MAX_LEAD_TICKS, dist / dogSpeedPerTick);

        // 预判点 = 球当前位置 + 球速 × 抵达时长；y 仍用狗自身高度（保证可站立、不会往天上跑）
        Vec3 predicted = ballPos.add(ballVel.scale(ticksToArrive));
        wolf.getNavigation().moveTo(predicted.x, wolf.getY(), predicted.z, INTERCEPT_CHASE_SPEED);

        // 起跳：只在**水平已经贴得很近**、而且**球明显高于本狗**时才跳。
        // 平飞过来的球不需要跳 —— 狗跑过去就能撞上。
        if (wolf.onGround()) {
            double horiz = Math.hypot(wolf.getX() - ballPos.x, wolf.getZ() - ballPos.z);
            double heightDiff = ballPos.y - wolf.getY();
            if (horiz <= INTERCEPT_JUMP_HORIZ && heightDiff > INTERCEPT_JUMP_HEIGHT) {
                wolf.setDeltaMovement(wolf.getDeltaMovement().add(0.0D, 0.55D, 0.0D));
            }
        }
        return true;
    }

    /**
     * <b>球打到狗的那一刻</b> —— 由 {@code BallPhysicsCore.onHitEntity} 调用。
     *
     * <p>按难度掷成功率：简单 100% / 普通 90% / 困难 75%。</p>
     * <ul>
     *   <li><b>成功</b>：狗不掉血（调用方会把伤害早退）、球的命中类效果被沉默
     *       （调用方置 {@code interceptedByDog}）、狗叼住这颗球、
     *       拦截 CD 与捡球 CD 一并重置、获得 3 秒速度 V + 跳跃提升 III</li>
     *   <li><b>失败</b>：返回 false，调用方照常结算伤害与效果</li>
     * </ul>
     *
     * @return true = 拦截成功，调用方不该再结算伤害
     */
    public static boolean tryIntercept(Wolf wolf, BallProjectile ball) { if (wolf.isOrderedToSit()) { return false; } // 坐着不拦（作者 2026-11-22）
        if (wolf.level().isClientSide() || !wolf.isAlive() || !wolf.isTame()) {
            return false;
        }
        if (!(wolf.getOwner() instanceof Player owner) || !owner.isAlive()) {
            return false;
        }
        long now = wolf.level().getGameTime();
        if (now < wolf.getData(ModAttachments.DOG_INTERCEPT_READY_AT.get())) {
            return false;   // 拦截还在 CD
        }
        // ===== 主人自己扔出去的球不拦（作者 2026-11-19 指定）=====
        // 狗不该去抢自己人的东西 —— 和 tryStartInterceptChase 里那条判据对齐。
        if (ball.getOwner() == owner) {
            return false;
        }
        if (!ball.getItem().isEmpty() && !BallAmmo.isBall(ball.getItem())) {
            return false;   // 不是球（锤头之类）
        }

        // 掷难度成功率（作者 2026-11-19 明确：简单 100% / 普通 90% / 困难 75%）
        float chance = interceptChanceFor(wolf.level());
        if (wolf.getRandom().nextFloat() >= chance) {
            return false;   // 拦截失败 ⇒ 正常被打中，效果不沉默
        }

        // ===== 成功 =====
        ItemStack stack = ball.getItem().copyWithCount(1);
        BallItem.stripIntangible(stack);
        BallItem.applyToughness(stack, ball.getToughness());

        wolf.setData(ModAttachments.DOG_HELD_BALL.get(), stack);
        // 拦截 CD 从「接到球」开始算；同时捡球 CD 一并重置（因为还要回去吐球）
        wolf.setData(ModAttachments.DOG_INTERCEPT_READY_AT.get(), now + CD_INTERCEPT);
        wolf.setData(ModAttachments.DOG_PICKUP_READY_AT.get(), now);

        // 加速不在这里给 —— 已经在「进入拦截动作」时给了（见 tryStartInterceptChase）


        wolf.getNavigation().stop();
        ball.discard();
        wolf.level().playSound(null, wolf.getX(), wolf.getY(), wolf.getZ(),
                net.minecraft.sounds.SoundEvents.WOLF_ARMOR_DAMAGE,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1.2F);
        RotationCraft.LOGGER.debug("[dog] 拦截成功：{} 叼走了球（难度成功率 {}）",
                wolf.getName().getString(), chance);
        return true;
    }

    // ==================================================================
    //  原版弹射物的拦截（作者 2026-11-19 指定）
    // ==================================================================

    /**
     * <b>拦原版的箭 / 光灵箭 / 烈焰弹</b> —— 挂在 {@code LivingIncomingDamageEvent} 上。
     *
     * <h2>为什么用伤害事件而不是 Mixin 弹射物</h2>
     * <p>原版弹射物种类多、继承链各不相同（{@code AbstractArrow} 底下还有 {@code Arrow} /
     * {@code SpectralArrow} / {@code ThrownTrident}，火球那边还有 {@code LargeFireball} /
     * {@code SmallFireball}），逐个 Mixin 的 {@code onHitEntity} 侵入面太大。
     * 伤害事件是 NeoForge 官方扩展点、一处覆盖全部，而且**天然就是「已经命中了」的那一刻** ——
     * 正好对应「被击中才算触发拦截」的语义。</p>
     *
     * <h2>拦不住的那些</h2>
     * <p>本方法只处理 {@link #interceptLootFor} 认识的那几种。**其余弹射物一律放行**
     * （狗还是会去挡 —— 见 {@code tryStartInterceptChase}，因为那边的扫描已经覆盖所有
     * {@code Projectile}），它们照常造成伤害、效果不沉默。这正是作者要的「拦不住的也会试图挡」。</p>
     */
    @SubscribeEvent
    public static void onIncomingDamage(
            net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Wolf wolf)) {
            return;
        }
        if (wolf.level().isClientSide() || !wolf.isAlive() || !wolf.isTame()) {
            return;
        }
        if (!(wolf.getOwner() instanceof Player owner) || !owner.isAlive()) {
            return;
        }
        if (wolf.isOrderedToSit()) { return; }   // 坐着的狗不拦，正常挨打（作者 2026-11-22）
        net.minecraft.world.entity.Entity direct = event.getSource().getDirectEntity();
        if (direct == null || direct == wolf) {
            return;
        }
        // 模组的球走 BallPhysicsCore.onHitEntity 那条（那边还要置 interceptedByDog 去沉默效果），
        // 这里必须让开，否则会重复处理。
        if (direct instanceof BallProjectile) {
            return;
        }
        // 主人自己射出去的不拦（作者 2026-11-19）
        if (direct instanceof net.minecraft.world.entity.projectile.Projectile projectile
                && projectile.getOwner() == owner) {
            return;
        }
        // 只拦认识的这几种；其余（三叉戟、潜影贝弹、凋零头……）放行 ⇒ 正常受伤
        ItemStack loot = interceptLootFor(direct);
        if (loot.isEmpty()) {
            return;
        }
        long now = wolf.level().getGameTime();
        if (now < wolf.getData(ModAttachments.DOG_INTERCEPT_READY_AT.get())) {
            return;   // 拦截 CD 中
        }
        // 按难度掷成功率（简单 100% / 普通 90% / 困难 75%）
        float chance = interceptChanceFor(wolf.level());
        if (wolf.getRandom().nextFloat() >= chance) {
            return;   // 拦失败 ⇒ 正常被打中，效果不沉默
        }

        // ===== 成功 =====
        event.setCanceled(true);   // 狗不掉血
        wolf.setData(ModAttachments.DOG_HELD_BALL.get(), loot);
        wolf.setData(ModAttachments.DOG_INTERCEPT_READY_AT.get(), now + CD_INTERCEPT);
        wolf.setData(ModAttachments.DOG_PICKUP_READY_AT.get(), now);
        direct.discard();          // 弹射物被叼走
        wolf.level().playSound(null, wolf.getX(), wolf.getY(), wolf.getZ(),
                net.minecraft.sounds.SoundEvents.WOLF_ARMOR_DAMAGE,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1.2F);
        RotationCraft.LOGGER.debug("[dog] 拦截成功：{} 叼住了 {}（难度成功率 {}）",
                wolf.getName().getString(), direct.getType().toString(), chance);
    }

    /**
     * 这种弹射物被拦下之后，狗嘴里应该变成什么**掉落物**。
     *
     * @return 空栈 = 这种东西拦不住（调用方应当放行，让它正常造成伤害）
     */
    public static ItemStack interceptLootFor(net.minecraft.world.entity.Entity projectile) {
        // ⚠️ 先判子类：SpectralArrow 和 Arrow 是兄弟（都 extends AbstractArrow），
        //    顺序写反了光灵箭会被当成普通箭。
        if (projectile instanceof net.minecraft.world.entity.projectile.arrow.SpectralArrow) {
            return new ItemStack(net.minecraft.world.item.Items.SPECTRAL_ARROW);
        }
        if (projectile instanceof net.minecraft.world.entity.projectile.arrow.Arrow) {
            return new ItemStack(net.minecraft.world.item.Items.ARROW);
        }
        // 烈焰弹（大火球 / 小火球都属于 hurtingprojectile 下的 Fireball）
        if (projectile instanceof net.minecraft.world.entity.projectile.hurtingprojectile.Fireball) {
            return new ItemStack(net.minecraft.world.item.Items.FIRE_CHARGE);
        }
        return ItemStack.EMPTY;
    }

    // ==================================================================
    //  工具
    // ==================================================================

    /** 一个候选球：球实体 或 掉落物 */
    private record Candidate(ItemStack stack, BallProjectile ball, ItemEntity drop, int rarity) {
        Candidate(ItemStack stack, BallProjectile ball, ItemEntity drop) {
            this(stack, ball, drop, rarityOf(stack));
        }

        Vec3 position() {
            if (ball != null) {
                return ball.position();
            }
            return drop != null ? drop.position() : Vec3.ZERO;
        }
    }

    /**
     * 这颗球的「自然生成权重」（越小越稀有，0 最稀有）。
     *
     * <p>普通球直接读 {@link BallBehavior.BallProfile#rarity()}。
     * <b>组合球单独算</b>（它的 profile.rarity 被 {@code comboProfile} 写死成 0，
     * 不能直接用）：</p>
     * <ul>
     *   <li>读 {@code combo_sources} 组件拿到各份来源；</li>
     *   <li><b>任何一份的 rarity 是 0 ⇒ 整颗视为 0</b>（作者指定）；</li>
     *   <li>否则取各份 rarity 的<b>算术平均</b>（作者指定）。</li>
     * </ul>
     */
    public static int rarityOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        String raw = stack.get(ModComponents.COMBO_SOURCES.get());
        if (raw == null || raw.isEmpty()) {
            return BallBehavior.profileFor(stack).rarity();
        }
        List<Integer> indexes = BallFragments.parseIndexes(raw);
        if (indexes.isEmpty()) {
            return BallBehavior.profileFor(stack).rarity();
        }
        List<net.minecraft.world.item.Item> sources = BallFragments.sources();
        long sum = 0L;
        int count = 0;
        for (int idx : indexes) {
            if (idx < 0 || idx >= sources.size()) {
                continue;
            }
            int r = BallBehavior.profileFor(new ItemStack(sources.get(idx))).rarity();
            if (r <= 0) {
                return 0;   // 任一份是 0 ⇒ 整颗视为最稀有
            }
            sum += r;
            count++;
        }
        if (count == 0) {
            return BallBehavior.profileFor(stack).rarity();
        }
        return (int) (sum / count);
    }

    /** 点到线段的距离（拦截「狗在不在路径附近」用） */
    private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0E-9D) {
            return point.distanceTo(a);
        }
        double t = point.subtract(a).dot(ab) / len2;
        t = Math.max(0.0D, Math.min(1.0D, t));
        return point.distanceTo(a.add(ab.scale(t)));
    }
}

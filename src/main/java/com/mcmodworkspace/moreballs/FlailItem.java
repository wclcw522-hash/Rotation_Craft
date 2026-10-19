package com.mcmodworkspace.moreballs;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * <b>流星锤</b> —— 一个物品类承载全部球种（作者 2026-10-19 规格）。
 *
 * <h2>为什么是「一个物品 + 组件」而不是 19 个物品</h2>
 * <p>流星锤要<b>继承材料球的一切数值与词条</b>、<b>信息显示和球一样</b>。
 * 如果每种球注册一个 {@code FlailItem}，那 19 种球 × 各自的数值/词条/tooltip 全都要
 * 再写一遍；而用组件把「内嵌的那颗球」存进去，所有派生数据都能直接问它要 ——
 * 与 {@link ComboBallItem} 用 {@code combo_sources} 存来源是同一个思路。</p>
 *
 * <p>创造栏里「每种球独立展示」由 {@link ModCreativeTabs} 遍历球的注册表逐个
 * {@link #of(ItemStack)} 生成实例实现，视觉上独立、底层是同一个物品。</p>
 *
 * <h2>它不是 {@link BallItem}</h2>
 * <p>刻意不继承：{@code BallItem} 带的是「可以扔出去」的语义，而流星锤的投掷由
 * 自己的蓄力/回收流程接管（见 {@code FlailThrowHandler}）。
 * 继承会让「右键扔球」的逻辑误命中它。</p>
 */
public class FlailItem extends Item {

    /**
     * 线长上限（格）。作者指定固定 30 格。
     */
    public static final double MAX_LINE_LENGTH = 30.0D;

    /**
     * 手持蓄力时锤头的圆周半径（格）。
     */
    public static final double SPIN_RADIUS = 1.5D;

    /**
     * 手持蓄力时锤头的离地高度（格）。
     */
    public static final double SPIN_HEIGHT = 1.75D;

    /**
     * 锤头绕玩家转一圈所需 tick（20 tick = 1 秒 ⇒ 2π/秒）。
     */
    public static final int SPIN_PERIOD_TICKS = 20;

    /**
     * 回收速度公式的分子：{@code 60 / 重量} 格每秒。
     */
    public static final double RECOVER_SPEED_NUMERATOR = 60.0D;

    /**
     * 回收速度的<b>下限保护</b> —— 重量装进这个数的下限。
     *
     * <p>⚠️ {@code weight} 的下界是 <b>0</b>（回响球就是 0，见 {@code BallBehavior} L604）。
     * 直接算 {@code 60 / 0} 会得到 {@code Infinity}，锤头一帧就飞回手里，完全没法玩。</p>
     *
     * <p>取 0.5 ⇒ 重量 0 的流星锤回收速度是 120 格/秒，仍然是最快的一档，
     * 但还在「看得见过程」的范围内。</p>
     */
    public static final double MIN_RECOVER_WEIGHT = 0.5D;

    /**
     * 回收速度的硬上限（格每秒）—— 防止任何极端 profile 算出离谱数值。
     */
    public static final double MAX_RECOVER_SPEED = 120.0D;

    /** 蓄力上限（刻）—— 与球的最长蓄力 50 刻对齐 */
    public static final int MAX_USE_TICKS = 50;

    public FlailItem(Properties properties) {
        super(properties);
    }

    // ===== 唯一构造入口 =====

    /**
     * 用一颗**满耐久**的球造一把流星锤。
     *
     * <p>满耐久的判据是 {@link BallClusterTier#of(ItemStack)} 落在 {@code PRISTINE} ——
     * 这一条对普通球和组合球<b>一视同仁</b>（组合球继承 {@link BallItem} 的整套耐久系统，
     * 记在 {@code ModComponents.TOUGHNESS_LEFT} 上）。</p>
     *
     * @return 造好的流星锤；球不合格时返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack of(ItemStack ball) {
        if (!BallAmmo.isBall(ball) || ball.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (BallClusterTier.of(ball).isClustered()) {
            return ItemStack.EMPTY;   // 不是满耐久 ⇒ 不允许合成
        }
        ItemStack out = new ItemStack(ModItems.FLAIL.get());
        out.set(ModComponents.FLAIL_BALL.get(), ball.copyWithCount(1));
        // 派生字段：物品模型的 select 只能比基础类型，所以球的短 id 要单独存一份
        out.set(ModComponents.FLAIL_KEY.get(), flailKeyOf(ball));
        return out;
    }

    /**
     * 取出内嵌的球；没有则返回 {@link ItemStack#EMPTY}。
     *
     * <p>⚠️ 返回的是<b>副本</b>，改它不会动到流星锤里那颗。</p>
     */
    public static ItemStack ballOf(ItemStack flail) {
        if (flail.isEmpty() || !(flail.getItem() instanceof FlailItem)) {
            return ItemStack.EMPTY;
        }
        ItemStack inner = flail.get(ModComponents.FLAIL_BALL.get());
        return inner == null ? ItemStack.EMPTY : inner;
    }

    /**
     * 这把流星锤对应的球的数值档案；没有内嵌球时退回默认档案。
     */
    public static BallBehavior.BallProfile profileOf(ItemStack flail) {
        ItemStack inner = ballOf(flail);
        return inner.isEmpty()
                ? BallBehavior.profileFor(new ItemStack(ModItems.WOODEN_BALL.get()))
                : BallBehavior.profileFor(inner);
    }

    /** 这把流星锤此刻是不是「已扔出」状态（图标只显示线、且禁止切栏） */
    public static boolean isCasting(ItemStack stack) {
        return isFlail(stack) && Boolean.TRUE.equals(stack.get(ModComponents.FLAIL_CASTING.get()));
    }

    /** 设置「已扔出」状态；传 false 恢复成带球的图标 */
    public static void setCasting(ItemStack stack, boolean casting) {
        if (!isFlail(stack)) {
            return;
        }
        if (casting) {
            stack.set(ModComponents.FLAIL_CASTING.get(), Boolean.TRUE);
        } else {
            stack.remove(ModComponents.FLAIL_CASTING.get());
        }
    }
    /**
     * 球的短 id（{@code iron_ball} 这种），供 {@code items/flail.json} 的 select 用。
     *
     * <p>取注册名去掉命名空间，因为组合球之外的所有球都在 {@code rotation_craft} 下；
     * 原版两个（{@code ender_pearl} / {@code slime_ball}）也照这个口径取，
     * 与贴图文件名 {@code flail_<短id>.png} 一一对应。</p>
     */
    public static String flailKeyOf(ItemStack ball) {
        if (ball.isEmpty()) {
            return "";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ball.getItem()).getPath();
    }

    public static boolean isFlail(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof FlailItem;
    }

    // ===== 名字 =====

    /**
     * 名字由内嵌球推导：
     * <ul>
     *   <li>普通球 ⇒「铁球流星锤」—— 用 {@code item.rotation_craft.flail.format}</li>
     *   <li>组合球 ⇒「铁-铜-木-石流星锤」—— 直接拼内嵌组合球自己的名字（它已经是
     *       「铁-铜-木-石球」），所以走另一个格式串把末尾的「球」换掉</li>
     * </ul>
     */
    @Override
    public Component getName(ItemStack stack) {
        ItemStack inner = ballOf(stack);
        if (inner.isEmpty()) {
            return super.getName(stack);
        }
        if (inner.getItem() instanceof ComboBallItem) {
            // 组合球的名字形如「铁-铜-木-石球」，这里把结尾的「球」换成「流星锤」的效果
            // 由语言文件里的格式串负责，代码只负责把内嵌球名字整体当参数传进去。
            return Component.translatable("item.rotation_craft.flail.combo.format", inner.getHoverName());
        }
        return Component.translatable("item.rotation_craft.flail.format", inner.getHoverName());
    }

    /**
     * tooltip 直接委托给内嵌球，末尾补流星锤专属的三行。
     *
     * <p>⚠️ 不复述词条 —— 词条由球的 tooltip 负责，两处各写一遍必然漂移。</p>
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        ItemStack inner = ballOf(stack);
        if (!inner.isEmpty()) {
            // 把内嵌球的 tooltip 原样搬过来（它就是「信息显示和球一样」的实现）
            inner.getItem().appendHoverText(inner, context, display, tooltip, flag);
        }
        // ===== 流星锤自己的三行 =====
        tooltip.accept(Component.translatable("tooltip.rotation_craft.flail.line_length",
                String.valueOf((int) MAX_LINE_LENGTH)).withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.rotation_craft.flail.recover",
                String.valueOf((int) Math.round(recoverSpeed(profileOf(stack)))))
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.rotation_craft.flail.broken")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * 回收速度（格每秒）= {@code 60 / 重量}，带下限保护与硬上限。
     *
     * <p>重量 0（回响球）会被夹到 {@link #MIN_RECOVER_WEIGHT}，不会算出 Infinity。</p>
     */
    public static double recoverSpeed(BallBehavior.BallProfile profile) {
        double weight = Math.max(MIN_RECOVER_WEIGHT, profile.weight());
        return Math.min(MAX_RECOVER_SPEED, RECOVER_SPEED_NUMERATOR / weight);
    }

    /**
     * 让它能显示耐久条 —— 耐久照抄内嵌球那一套（{@code TOUGHNESS_LEFT} / {@code profile.toughness()}）。
     */
    // 耐久条直接委托给内嵌球的**实例方法**（BallItem 里是非 static）——
    // 这样「流星锤的耐久条」和「那颗球的耐久条」必然一致，不会两处维护漂移。
    @Override
    public boolean isBarVisible(ItemStack stack) {
        ItemStack inner = ballOf(stack);
        return !inner.isEmpty() && inner.getItem().isBarVisible(inner);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        ItemStack inner = ballOf(stack);
        return inner.isEmpty() ? 0 : inner.getItem().getBarWidth(inner);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        ItemStack inner = ballOf(stack);
        return inner.isEmpty() ? 0xFFFFFF : inner.getItem().getBarColor(inner);
    }

    /**
     * 流星锤不进收纳袋、不能切、不进任何「球」的标签 —— 由各自的判定点拦，
     * 这里不需要额外覆写。列出来是为了让以后改动的人知道这套规则在哪几处生效：
     * <ul>
     *   <li>收纳袋：{@code BallPouchItem} 的存入判定</li>
     *   <li>切石机：{@code BallCuttingGate}</li>
     *   <li>拾取：{@code BallPickupHandler}（只认 {@code BallProjectile}）</li>
     * </ul>
     */
    // ===== 投掷：按住 → 环绕，松手 → 飞出 =====

    /**
     * 右键。
     *
     * <ul>
     *   <li><b>已有飞出的锤头</b> ⇒ 这一下是「开始回收」，立刻返回 {@code CONSUME}</li>
     *   <li>否则 ⇒ 开始蓄力</li>
     * </ul>
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!isFlail(stack)) {
            return InteractionResult.PASS;
        }
        var flying = findHead(level, player);
        if (flying != null) {
            if (!level.isClientSide()
                    && flying.stage() != com.mcmodworkspace.moreballs.entity.FlailHeadEntity.Stage.RETURNING) {
                flying.beginReturning(player);
            }
            return InteractionResult.CONSUME;
        }
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    /** 蓄力上限：50 刻（与球的最长蓄力一致） */
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return MAX_USE_TICKS;
    }

    /**
     * 蓄力中每 tick：**首次调用时生成锤头实体**，之后由 {@code FlailHeadEntity.tick()} 自己维持环绕。
     *
     * <p>⚠️ 环绕位置由实体自己算（见 {@code tickSpin}），这里只负责「有没有」。
     * 两处都算位置会在客户端与服务端之间抖动。</p>
     */
    @Override
    public void onUseTick(Level level, LivingEntity living, ItemStack stack, int remainingUseDuration) {
        if (level.isClientSide() || !(living instanceof Player player)) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (findHead(level, player) != null) {
            return;   // 已经有锤头了
        }
        var head = com.mcmodworkspace.moreballs.ModEntities.FLAIL_HEAD.get().create(serverLevel,
                net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (head == null) {
            return;
        }
        head.setOwner(player);
        head.setItem(stack.copy());
        head.setOriginSlot(player.getInventory().getSelectedSlot());
        head.setPos(player.getX(), player.getY() + SPIN_HEIGHT, player.getZ());
        serverLevel.addFreshEntity(head);
    }

    /**
     * 松手：锤头按球的物理飞出。蓄力比例 = 用掉的比例（越久越快，与球一致）。
     */
    /**
     * 松手：锤头按球的物理飞出。
     *
     * <p>⚠️ 26.2 的 {@code Item.releaseUsing} 返回 <b>{@code boolean}</b>（不是 void —— 老版本是 void）。
     * 返回 true 表示「这次使用被取消/已消费」。</p>
     */
    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity living, int remainingUseDuration) {
        if (level.isClientSide() || !(living instanceof Player player)) {
            return false;
        }
        var head = findHead(level, player);
        if (head == null) {
            return false;
        }
        int used = MAX_USE_TICKS - remainingUseDuration;
        float powerRatio = Math.max(0.25F, (float) used / MAX_USE_TICKS);
        head.launch(player, powerRatio);
        return true;
    }

    /**
     * 找这个玩家扔出去的、还没结束的锤头。
     *
     * <p>用范围搜索而不是静态 Map：实体引用不该被静态持有（跨维度/卸载会留下悬空引用），
     * 而战场就那么大，一次 {@code getEntitiesOfClass} 的成本可以忽略。</p>
     */
    public static com.mcmodworkspace.moreballs.entity.FlailHeadEntity findHead(Level level, Player player) {
        var heads = level.getEntitiesOfClass(com.mcmodworkspace.moreballs.entity.FlailHeadEntity.class,
                player.getBoundingBox().inflate(MAX_LINE_LENGTH + 4.0D),
                head -> head.getOwner() == player && !head.isRemoved());
        return heads.isEmpty() ? null : heads.get(0);
    }
    public static List<String> ruleLocations() {
        return List.of("BallPouchItem", "BallCuttingGate", "BallPickupHandler");
    }
}

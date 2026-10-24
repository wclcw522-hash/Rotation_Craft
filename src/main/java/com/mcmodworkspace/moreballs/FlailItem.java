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
     * 锤头绕玩家转一圈所需 tick。
     *
     * <p>作者 2026-10-19：「速度变成现在的两倍」⇒ 20 刻（2π/秒）改成 <b>10 刻（4π/秒）</b>。</p>
     */
    public static final int SPIN_PERIOD_TICKS = 10;

    /**
     * 回收速度公式的分子：{@code 60 / 重量} 格每秒。
     */
    public static final double RECOVER_SPEED_NUMERATOR = 30.0D;   // ⚠️ 30 —— 作者指定 15 改成 30，不要擅自改

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

    /**
     * 蓄力时长 —— **和球一样用 {@link BallCharge#USE_DURATION}（72000 刻）**。
     *
     * <p>⚠️ 作者 2026-10-19 报「右键无法蓄力，会直接飞出去」：
     * 我原先写的是 50 刻，到点原版就自动 {@code releaseUsing} ⇒ 表现为「自动扔出」。
     * 球那边用 72000（约 1 小时），按住多久都不会自己松手。</p>
     */
    public static final int MAX_USE_TICKS = BallCharge.USE_DURATION;

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
        if (!canBecomeFlail(ball)) {
            return ItemStack.EMPTY;   // 不是球，或是被排除的那两颗
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
    /** 标记「刚回收完」—— 玩家松开右键才能再蓄力 */
    public static void markRecovered(ItemStack stack, boolean value) {
        if (stack.isEmpty()) {
            return;
        }
        if (value) {
            stack.set(ModComponents.FLAIL_RECOVERED.get(), Boolean.TRUE);
        } else {
            stack.remove(ModComponents.FLAIL_RECOVERED.get());
        }
    }

    /** 标记「正在回收锤头」—— 让装备动画变成 NONE，不做蓄力动作 */
    public static void markRetrieving(ItemStack stack, boolean value) {
        if (stack.isEmpty()) {
            return;
        }
        if (value) {
            stack.set(ModComponents.FLAIL_RETRIEVING.get(), Boolean.TRUE);
        } else {
            stack.remove(ModComponents.FLAIL_RETRIEVING.get());
        }
    }

    public static boolean isRetrieving(ItemStack stack) {
        return !stack.isEmpty() && Boolean.TRUE.equals(stack.get(ModComponents.FLAIL_RETRIEVING.get()));
    }

    public static boolean isRecovered(ItemStack stack) {
        return !stack.isEmpty() && Boolean.TRUE.equals(stack.get(ModComponents.FLAIL_RECOVERED.get()));
    }
    /**
     * <b>给世界里的锤头实体用的物品形态</b> —— 永远带球、不显示成纯线。
     *
     * <p>⚠️ 作者 2026-10-19 报「回收开始后锤头消失只剩一根绳」。</p>
     *
     * <p>根因：`items/flail.json` 最外层是 `select(flail_casting)` ——
     * 组件为 true 时物品模型整个换成 `flail_string_only`（纯线）。
     * 而锤头实体是用**物品模型**渲染的（`ThrownItemRenderer` 按 `state.item` 画），
     * 所以只要那份物品栈带着 `FLAIL_CASTING=true`，世界里就只剩一根绳。</p>
     *
     * <p>正确做法是把「手上的形态」和「世界里的形态」分开：
     * 手上那把要显示纯线（表示已经扔出去了），世界里的锤头必须永远是球 + 绳。
     * 这个 helper 就是给后者用的 —— 复制一份并剥掉 `FLAIL_CASTING` / `FLAIL_RETRIEVING`。</p>
     */
    public static ItemStack worldForm(ItemStack stack) {
        if (!isFlail(stack)) {
            return stack;
        }
        ItemStack out = stack.copy();
        out.remove(ModComponents.FLAIL_CASTING.get());
        out.remove(ModComponents.FLAIL_RETRIEVING.get());
        out.remove(ModComponents.FLAIL_RECOVERED.get());
        // 世界形态用「只有球、没有绳」的图 —— 否则物品图上的绳 + 渲染器画的拴绳 = 两根
        out.set(ModComponents.FLAIL_BARE.get(), Boolean.TRUE);
        return out;
    }

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

    /**
     * 这颗球**能不能做成流星锤**（作者 2026-10-19：末影珍珠与史莱姆球不做）。
     *
     * <p>⚠️ 这是唯一判据 —— 配方校验（{@code FlailRecipe}）与创造栏（{@code BATTLE} tab）
     * 都必须走它。两处各写一遍的话，必然出现「创造里能看到但合不出来」这种不一致。</p>
     *
     * <p>为什么排除这两颗：它们都在 {@code #rotation_craft:balls} 标签里（所以能扔、能当弩弹药），
     * 但作为流星锤的锤头不合适 —— 一个是原版投掷物、一个本身就是软的。</p>
     */
    public static boolean canBecomeFlail(ItemStack ball) {
        if (ball.isEmpty() || !BallAmmo.isBall(ball)) {
            return false;
        }
        // ⚠️ 作者 2026-10-19 明确：**只有实心球和空心球**能做流星锤。
        //    所以判据是那两个子标签，不是总标签 `#rotation_craft:balls` ——
        //    雪球系（snowball 子标签）不在其中，自动排除。
        var item = ball.getItem();
        net.minecraft.tags.TagKey<net.minecraft.world.item.Item> solid =
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                MoreBalls.MOD_ID, "balls/solid"));
        net.minecraft.tags.TagKey<net.minecraft.world.item.Item> hollow =
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                MoreBalls.MOD_ID, "balls/hollow"));
        if (!item.builtInRegistryHolder().is(solid) && !item.builtInRegistryHolder().is(hollow)) {
            return false;   // 雪球系等都在这条被挡掉
        }
        // 另外排掉两个原版物品：它们在 solid 标签里，但作者要求不做流星锤
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if ("minecraft".equals(id.getNamespace())
                && ("ender_pearl".equals(id.getPath()) || "slime_ball".equals(id.getPath()))) {
            return false;
        }
        return true;
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
    /**
     * 把球名字结尾的「球」/「 Ball」去掉 —— 流星锤的名字里不带「球」字（作者 2026-10-24）。
     *
     * <p>例：「铁球」→「铁」、「Iron Ball」→「Iron」、「铁-铜-木-石球」→「铁-铜-木-石」。</p>
     */
    private static String stripBallSuffix(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String s = name.trim();
        if (s.endsWith(" Ball")) {
            return s.substring(0, s.length() - 5).trim();
        }
        if (s.toLowerCase(java.util.Locale.ROOT).endsWith("ball")) {
            return s.substring(0, s.length() - 4).trim();
        }
        if (s.endsWith("球")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public Component getName(ItemStack stack) {
        ItemStack inner = ballOf(stack);
        if (inner.isEmpty()) {
            return super.getName(stack);
        }
        // ⚠️ 内嵌球的名字结尾带「球」（铁球 / Iron Ball），直接拼会变成「铁球流星锤」。
        //    这里剥掉结尾的「球」/「 Ball」，只留材质部分。
        String bare = stripBallSuffix(inner.getHoverName().getString());
        if (inner.getItem() instanceof ComboBallItem) {
            return Component.translatable("item.rotation_craft.flail.combo.format", bare);
        }
        return Component.translatable("item.rotation_craft.flail.format", bare);
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
        // 剩余耐久（作者 2026-10-23：信息显示里没写剩余耐久）
        int tMax = flailMaxToughness(stack);
        if (tMax > 0) {
            Integer tLeft = stack.get(ModComponents.TOUGHNESS_LEFT.get());
            int left = tLeft == null ? tMax : Math.max(0, tLeft);
            tooltip.accept(Component.translatable("tooltip.rotation_craft.flail.toughness",
                    String.valueOf(left), String.valueOf(tMax))
                    .withStyle(left <= 0 ? ChatFormatting.RED : ChatFormatting.GRAY));
        }
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
    // ===== 耐久条 =====
    //
    // ⚠️⚠️ **不能委托给内嵌球**（作者 2026-10-19 报「球耐久条好像不显示」）。
    //
    //    上一版写的是 `inner.getItem().isBarVisible(inner)` —— 那个方法读的是
    //    **内嵌球自己**的 `TOUGHNESS_LEFT`。而流星锤消耗耐久时写的是**流星锤这层**的
    //    `TOUGHNESS_LEFT`（`FlailHeadEntity.consumeOneToughness`），
    //    内嵌球那份永远满 ⇒ `left < max` 恒 false ⇒ **耐久条永远不显示**。
    //
    //    现在直接读流星锤自己的组件；上限取 `profile.toughness()`（从内嵌球推导，
    //    所以「这颗球本来多少耐久」仍跟着球走，只有「还剩多少」是本层的）。
    // 耐久条配色与满宽 —— 与 `BallItem` 里那四个 private 常量**同值**。
    // 不改它们的可见性去 public：那是无谓地扩大 API 面，复制三个 int 更省事，
    // 而且颜色本来就是原版风格（绿 / 黄 / 红），不会各自漂移。
    private static final int BAR_COLOR_HIGH = 0xFF00E000;
    private static final int BAR_COLOR_MID = 0xFFFFD000;
    private static final int BAR_COLOR_LOW = 0xFFFF3000;
    private static final int BAR_FULL_WIDTH = 13;

    private static int flailMaxToughness(ItemStack stack) {
        return profileOf(stack).toughness();
    }

    private static int flailRemainingToughness(ItemStack stack, int max) {
        Integer left = stack.get(ModComponents.TOUGHNESS_LEFT.get());
        return left == null ? max : left;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        int max = flailMaxToughness(stack);
        return !stack.isEmpty() && max > 0 && max != BallBehavior.TOUGH_FOREVER
                && flailRemainingToughness(stack, max) < max;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int max = flailMaxToughness(stack);
        if (max <= 0 || max == BallBehavior.TOUGH_FOREVER) {
            return 0;
        }
        int left = Math.min(flailRemainingToughness(stack, max), max);
        return Math.round(BAR_FULL_WIDTH * left / (float) max);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        int max = flailMaxToughness(stack);
        if (max <= 0 || max == BallBehavior.TOUGH_FOREVER) {
            return BAR_COLOR_HIGH;
        }
        float ratio = Math.min(flailRemainingToughness(stack, max), max) / (float) max;
        if (ratio > 0.5F) {
            return BAR_COLOR_HIGH;
        }
        return ratio > 0.25F ? BAR_COLOR_MID : BAR_COLOR_LOW;
    }

    /** 耐久是否已经用光 —— 用光了就不能再扔（作者 2026-10-19） */
    public static boolean isBroken(ItemStack stack) {
        if (!isFlail(stack)) {
            return false;
        }
        int max = flailMaxToughness(stack);
        if (max <= 0 || max == BallBehavior.TOUGH_FOREVER) {
            return false;
        }
        return flailRemainingToughness(stack, max) <= 0;
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
        // ===== 耐久耗尽：不能再扔，直接掉一根线并销毁（作者 2026-10-19）=====
        //
        // 作者原话：「耐久耗尽还能扔绳子，应该耐久耗尽后掉一根线直接爆掉」。
        // 判据是 `isBroken(stack)`（本层 TOUGHNESS_LEFT ≤ 0）。
        if (isBroken(stack)) {
            if (!level.isClientSide()) {
                player.drop(new ItemStack(net.minecraft.world.item.Items.STRING, 1), false);
                stack.shrink(1);   // 直接销毁这把锤
            }
            return InteractionResult.CONSUME;
        }

        // 作者 2026-10-19：回收完之后必须**松开再按**才算下一次蓄力。
        // 玩家还按着右键时（isUsingItem 为真）不响应，松手后 FlailCastHandler 会清标记。
        if (isRecovered(stack) && player.isUsingItem()) {
            return InteractionResult.CONSUME;
        }

        var head = findHead(level, player);

        // ===== 情况 A：手里这把锤已经扔出去了 ⇒ 这一下是「按住回收」 =====
        if (head != null
                && head.stage() != com.mcmodworkspace.moreballs.entity.FlailHeadEntity.Stage.SPIN) {
            if (!level.isClientSide()) {
                if (head.stage() == com.mcmodworkspace.moreballs.entity.FlailHeadEntity.Stage.FLYING) {
                    head.beginReturning(player);    // 首次按住 ⇒ 进入回收并扣 1 耐久
                }
                head.setRetrieving(true);           // 按住才拉
                markRetrieving(stack, true);        // 动画变 NONE，不做蓄力动作
            }
            player.startUsingItem(hand);            // 借用「使用中」状态来检测松开
            return InteractionResult.CONSUME;
        }

        // ===== 情况 B：还没扔 ⇒ 开始蓄力 =====
        // ⚠️ 作者 2026-10-19：回收完之后必须**松开再按**才算下一次蓄力。
        //    这里用 head == null 作为「上一把已经收回来了」的判据：
        //    回收完成时实体会 discard，所以下一 tick findHead 就是 null。
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return MAX_USE_TICKS;
    }

    /**
     * <b>蓄力动画 = 弓箭</b> —— 原版据此画那条蓄力进度条（作者要求「像球一样显示蓄力层数」）。
     *
     * <p>依据：{@code BallClusterItem} 也是返回 {@code BOW}，注释写明
     * 「不覆写的话原版用 {@code NONE}，画不出条」。</p>
     */
    @Override
    public net.minecraft.world.item.ItemUseAnimation getUseAnimation(ItemStack stack) {
        // 回收中不显示蓄力动作（作者 2026-10-19：「回收动作不应该和蓄力的一样，
        // 应该保持不动来收回锤头」）。判据用独立的 FLAIL_RETRIEVING，
        // 因为 FLAIL_CASTING 在蓄力时也是 true，拿它会让正常蓄力也没动作。
        if (isRetrieving(stack)) {
            return net.minecraft.world.item.ItemUseAnimation.NONE;
        }
        return net.minecraft.world.item.ItemUseAnimation.BOW;
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
        // ⚠️ 作者 2026-10-19 报「回收完立刻自动蓄力并扔出」——
        //    `use()` 有 isRecovered 守卫，但**后续每 tick 走的是这里**，原先没判。
        //    所以锤头一 discard，下一 tick 就新建了一个，看起来像自动蓄力。
        if (isRecovered(stack) && player.isUsingItem()) {
            return;
        }

        var existing = findHead(level, player);
        if (existing != null && existing.stage() == com.mcmodworkspace.moreballs.entity.FlailHeadEntity.Stage.SPIN) {
            return;   // 蓄力中，锤头已存在，由实体自己维持环绕
        }
        if (existing != null) {
            existing.setRetrieving(true);   // 回收中：保持「按住」标志
            markRetrieving(stack, true);    // 动画变 NONE
            return;
        }
        var head = com.mcmodworkspace.moreballs.ModEntities.FLAIL_HEAD.get().create(serverLevel,
                net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (head == null) {
            return;
        }
        head.setOwner(player);
        head.setItem(worldForm(stack));   // 世界里永远带球形态
        // ⚠️⚠️ **必须把球的数值灌进实体的同步字段**（作者 2026-10-23 三个现象同一个根）
        //
        //   锤头的权威耐久在物品组件 TOUGHNESS_LEFT 里，但继承来的球逻辑
        //   （onHit 的 !isTough()、physicalVelocity() 的配重、bounceOff 的档位、
        //    onHitEntity 的穿甲）读的是**实体的同步字段** TOUGHNESS / WEIGHT /
        //   BOUNCE / PIERCE_LEVEL。
        //
        //   这四个字段在 BallProjectile 里是构造时按 profile 设的，而锤头走
        //   super(type, level) 那个构造器 —— **从来没设过，全是默认 0**：
        //     · getToughness()==0 ⇒ 球判「不坚固」⇒ 一碰就 burst()（一撞就碎）
        //     · 球的 consumeDurability 改的是实体字段 ⇒ 玩家物品的组件没动（耐久不掉）
        //     · getWeight()==0 ⇒ 速度按最轻算 ⇒ 重球也飞得远（重量不对）
        BallBehavior.BallProfile startProfile = profileOf(stack);
        head.setToughness(startProfile.toughness());
        head.setWeight(startProfile.weight());
        head.setBounce(startProfile.bounce());
        head.setPierceLevel((byte) startProfile.penetration());
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
    /**
     * 松开右键。
     *
     * <ul>
     *   <li><b>蓄力中（SPIN）</b> ⇒ 锤头瞬移到「蓄力旋转的最高点」，再按球那套蓄力等级向准心方向飞出</li>
     *   <li><b>回收中</b> ⇒ 只是停止拉回（{@code setRetrieving(false)}），锤头留在原地</li>
     * </ul>
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
        if (head.stage() != com.mcmodworkspace.moreballs.entity.FlailHeadEntity.Stage.SPIN) {
            head.setRetrieving(false);       // 松开 ⇒ 停止回收
            markRetrieving(stack, false);    // 动画恢复 BOW（否则永远是 NONE）
            return true;
        }
        // 蓄力等级：和球完全同一套算法
        int usedTicks = BallCharge.usedTicks(player);
        int chargeLevel = BallCharge.levelAt(usedTicks, profileOf(stack).chargeLevels());
        head.launch(player, BallCharge.velocityMultiplier(chargeLevel));
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
                // ⚠️ `tickCount < MAX_HEAD_LIFE` 是**兜底**（作者 2026-10-19 存档里有个锤头
                //    扔出去再也没回来，导致手里每把流星锤都被判成「已扔出」、只显示一根绳）。
                //    超过寿命的锤头视为孤儿，不再认它 —— 这样即使实体还赖在世界上，
                //    物品也能恢复正常形态（实体本身由 FlailHeadEntity.tick 自行超时回收）。
                head -> head.getOwner() == player && !head.isRemoved()
                        && head.tickCount < com.mcmodworkspace.moreballs.entity.FlailHeadEntity.MAX_HEAD_LIFE);
        return heads.isEmpty() ? null : heads.get(0);
    }

    /** 把所有流星锤上的「扔出中」标记清掉（登录兜底用） */
    public static void clearCastingFlags(net.minecraft.world.entity.player.Player player) {
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack s = inv.getItem(slot);
            if (isFlail(s)) {
                setCasting(s, false);
                markRecovered(s, false);
            }
        }
        if (isFlail(player.getOffhandItem())) {
            setCasting(player.getOffhandItem(), false);
            markRecovered(player.getOffhandItem(), false);
        }
    }
    public static List<String> ruleLocations() {
        return List.of("BallPouchItem", "BallCuttingGate", "BallPickupHandler");
    }
}

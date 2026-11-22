package com.mcmodworkspace.rotationcraft;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 「选中弹药」的自动装填 —— <b>副手的弹药跟着轮盘选的种类走</b>。
 *
 * <h2>作者指定的机制</h2>
 * <ul>
 *   <li>在径向菜单里选中某种球之后，不必副手持弹，弩也能用</li>
 *   <li>弹药直接从收纳袋里取，<b>按耐久从低到高</b>消耗（先把旧的打出去）</li>
 *   <li><b>切换选中弹药时，副手那颗别的种类的球要换成目标种类的</b> ——
 *       副手拿着用旧的空心铁球，切到木球 / 铁球 / 圆石球时，副手就该跟着换成新选的那种
 *       （作者 2026-10-07 明确要求）</li>
 * </ul>
 *
 * <h2>副手各种情况怎么办</h2>
 * <table border="1">
 *   <caption>副手处理规则</caption>
 *   <tr><th>副手拿着</th><th>动作</th></tr>
 *   <tr><td><b>空的</b></td><td>从袋子里抽一颗选中种类的放进去</td></tr>
 *   <tr><td><b>别的种类的球</b></td><td><b>换掉</b>：旧的收回袋子中转槽
 *       （收不下退背包、再不行掉地上），换上选中种类的</td></tr>
 *   <tr><td><b>就是选中种类的球</b></td><td>不动 —— 手上这颗按玩家的来</td></tr>
 *   <tr><td><b>非球物品</b>（火把之类）</td><td>不动</td></tr>
 * </table>
 *
 * <p>换之前会先确认「袋子里确实抽得到目标种类的球」，抽不到就整段不动 ——
 * 免得把玩家副手那颗白白收走。</p>
 *
 * <h2>怎么做到「不持弹也能射」</h2>
 * <p>不去碰弩的类，只是往副手悄悄塞一颗弹，装填与发射全程走原版流程 ——
 * 所以不会跟别的改弩的模组打架。</p>
 */
@EventBusSubscriber(modid = RotationCraft.MOD_ID)
public final class BallAmmoAutoLoader {

    private BallAmmoAutoLoader() {
    }

    /**
     * 【状态】上一刻主手是不是弩 —— 只用来认「**刚刚**从弩切走」这个瞬间。
     *
     * <p>不记这个的话，「主手不是弩」每刻都成立，副手那颗球会被反复收回 ——
     * 而那正是作者 2026-10-15 报的「按整理时副手那颗变成球堆掉进物品栏」。</p>
     */
    private static final java.util.Map<java.util.UUID, Boolean> WAS_HOLDING_CROSSBOW =
            new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        // ⚠️⚠️ **整段只在服务端跑**（作者 2026-10-16 报的「副手的球收不回收纳袋、只是隐藏」的根因）。
        //
        //    这个类用 `WAS_HOLDING_CROSSBOW`（**静态** map）记「上一刻主手是不是弩」，
        //    而 `PlayerTickEvent.Post` **两端都会触发** —— 单人存档又是「客户端与服务端同一个 JVM」，
        //    两边**共用同一张 map**、互相踩：
        //      · 客户端先 tick：它那边主手已经本地预测切走了 → 写入 `false`，
        //        并且自己也把回收走了一遍（本地清空副手 + 顺手改了客户端那份袋子副本）；
        //      · 服务端紧接着 tick 时读到的 `wasHolding` 已经是客户端刚写进去的 `false`
        //        ⇒ **认不出「刚从弩切走」这回事 ⇒ 服务端压根没收那颗球**。
        //    现象就是主人看到的那样：副手看着空了（客户端自己清的），
        //    权威数据里那颗球还挂在手上；按一下切换副手触发同步，球又冒出来。
        //
        //    限定在服务端之后：map 只有服务端维护、判定可靠，客户端看到什么完全由同步包决定。
        if (player.level().isClientSide()) {
            return;
        }

        ItemStack mainHand = player.getMainHandItem();

        // ===== ⓪ 主手**从弩切走**的那一刻 → 把副手那颗球收回收纳袋（作者 2026-10-15 要求）=====
        //
        // ⚠️ 顺序很关键：这一条必须排在「主手得是弩」的 return **之前**。
        //    原来的写法是「主手不是弩就 return」，于是副手那颗自动补上去的球
        //    会一直挂在手上 —— 切到镐子、切到剑都还在，玩家得自己拿掉。
        //    作者要的是「主手从持弩状态切换到其它状态时，袋子主动收回副手的球」。
        //
        // ⚠️⚠️ 但**只能在这一个瞬间收**，不能每刻收。原来的判据是单纯的
        //      `!(mainHand.getItem() instanceof CrossbowItem)` —— 那个条件**每刻都成立**
        //      （只要主手不是弩），于是：
        //        副手的球被收走 → 自动补弹下一 tick 又补一颗 → 再被收走 …
        //      玩家看到的是一次「整理 / 切物品」之后，副手那颗变成球堆掉进物品栏、
        //      而副手上同时又多了一颗（作者 2026-10-15 反馈）。
        //      改成「**上一刻是弩、这一刻不是**」才算「切走」。
        //
        //    只收「是球」的副手 —— 副手拿着火把 / 盾牌之类的，一律不碰。
        boolean holdingCrossbow = mainHand.getItem() instanceof CrossbowItem;
        Boolean wasHolding = WAS_HOLDING_CROSSBOW.put(player.getUUID(), holdingCrossbow);
        boolean justSwitchedAway = wasHolding != null && wasHolding && !holdingCrossbow;

        if (!holdingCrossbow) {
            if (justSwitchedAway) {
                recallOffhandBall(player);
            }
            // ★ 主手不再是弩 —— 隐藏槽里要是还压着袋子，必须还回副手。
            //   否则它会被永久扣在那儿：玩家看得见「袋子没了」，背包里也找不到。
            restoreStashedPouch(player);
            return;
        }

        // ① ★ 装填已经结束 / 玩家松手了 → 把暂存的袋子换回副手（作者 2026-11-12 指定）
        //
        //    ⚠️ 这一条**必须排在下面「已装填就 return」之前**：装填完成时
        //       `isCharged` 正好变 true，若先撞上那个 return，袋子就永远换不回来了。
        //
        //    两个触发时机都要认：
        //      · 球已经进膛（`isCharged`）—— 正常装填完成
        //      · 不再按着右键（`!isUsingItem`）—— 蓄力中途松手 / 被打断
        if (BallPouchHelper.hasStashedPouch(player)
                && (CrossbowItem.isCharged(mainHand) || !player.isUsingItem())) {
            restoreStashedPouch(player);
            return;
        }

        // ② 主手是弩，但已经装填好了 → 不用管（子弹已经在膛里）
        if (CrossbowItem.isCharged(mainHand)) {
            return;
        }

        // ② ===== 必须装备了收纳袋（作者 2026-10-15 要求）=====
        //
        // ⚠️ 原来这里没有这一条，于是「主手拿着弩 + 轮盘选过弹药」就会往副手塞球 ——
        //    哪怕身上一个袋子都没有。作者反馈的「无论是否装备魔丸收纳袋都会被自动填充」
        //    就是这个。
        //
        //    本机制的定位是「袋子里的弹药自动补到副手」（见类注释第 18-19 行：
        //    「弹药直接从收纳袋里取」）—— 没有袋子，就没有「自动补弹」这件事可言，
        //    副手该由玩家自己管。
        if (BallPouchHelper.allPouchStacks(player).isEmpty()) {
            return;
        }

        // ③ 得在轮盘里选过弹药（存的是带组件的样本球，不是物品 id —— 组合球只能这样区分）
        ItemStack wanted = BallAmmoSelection.selected(player);
        if (wanted.isEmpty()) {
            return;
        }

        // ③ 看副手：
        //    · 拿着非球物品 → 完全不碰
        //    · 拿着同种球、且**耐久不比袋里最旧的那颗更差** → 不用换
        //
        //    ⚠️ 后半句的「耐久」不能省：只比「同种」的话，副手拿着满耐久的球时就直接返回，
        //    袋里那颗刚回收、已经用过的同类球就永远轮不上 —— 而「先把旧的打出去」
        //    正是作者要求的行为（作者反馈的「袋里只有满耐久时，用过的同类球不会替换到副手」）。
        ItemStack offhand = player.getOffhandItem();
        boolean occupied = !offhand.isEmpty();

        // ★★ 副手是收纳袋 + 正在蓄力装填 → 临时换手（作者 2026-11-12 指定）
        //
        // 作者的原话：「让副手是袋子的时候也触发上膛动画，在完成上膛前把袋子挤到
        // 一个额外的隐藏物品栏里，把目标弹药拿出来，完成上膛后再把副手的弹药放回收纳袋
        // 并让收纳袋从隐藏的额外物品栏回来。」
        //
        // 为什么非这样不可：原版 `CrossbowItem.tryLoadProjectiles` 只认**手持的球**。
        // 副手拎着袋子时它什么都找不到，装填直接不起来 —— 而这恰恰是作者最自然的用法
        // （主手弩、副手袋子）。所以装填期间必须让副手上真的有一颗球。
        //
        // ⚠️ 只认「正在蓄力」这一个瞬间（`isUsingItem` + 使用中的就是主手那把弩）：
        //    平时副手拎着袋子不该被无端顶掉，那是玩家自己的搭配。
        if (occupied && BallPouchHelper.isPouch(offhand) && isChargingCrossbow(player, mainHand)) {
            borrowFromPouch(player, offhand, wanted);
            return;
        }

        if (occupied && !BallAmmo.isBall(offhand)) {
            return;
        }
        if (occupied
                && BallPouchItem.sameKind(offhand, wanted)
                && BallPouchItem.remainingToughness(offhand)
                        <= BallPouchItem.lowestToughnessInPouches(player, wanted)) {
            // 诊断：副手已经是最该用的那颗 → 正常跳过。
            // 排查「补不上来」时需要它，否则分不清是「合理跳过」还是「逻辑没走到」。
            diagnose(player, "副手已是最旧的一颗，跳过", wanted, offhand);
            return;
        }

        // ④ 先抽一颗目标种类的球（抽不到就整段不动，别白换掉副手那颗）
        ItemStack taken = ItemStack.EMPTY;
        for (ItemStack pouch : BallPouchHelper.allPouchStacks(player)) {
            taken = BallPouchItem.takeLowestDurability(pouch, wanted);
            if (!taken.isEmpty()) {
                break;
            }
        }
        if (taken.isEmpty()) {
            diagnose(player, "袋子里取不到这种球", wanted, offhand);
            return;
        }

        // ⑤ 副手原来那颗先收好 —— 收回袋子中转槽，退而求其次进背包，实在不行掉地上。
        //    无论如何不能让它凭空消失：那是玩家的东西（多半还是一颗用过的球）。
        if (occupied) {
            ItemStack old = offhand.copy();
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            BallPouchHelper.Delivery delivery = BallPouchHelper.deliverReturned(player, old);
            if (delivery == BallPouchHelper.Delivery.NOWHERE) {
                player.drop(old, false);
            } else if (delivery == BallPouchHelper.Delivery.INVENTORY_TRANSIT_FULL) {
                // 作者指定：中转区满时，在物品栏上方用一串白字说明这颗球去哪了。
                // 26.2 里这个「物品栏上方」的方法叫 sendOverlayMessage（老的 displayClientMessage(…, true) 已没了）
                player.sendOverlayMessage(
                        Component.translatable("message.rotation_craft.transit_full"));
            }
        }

        // ⑥ 换上刚抽到的那颗，剩下的交给弩自己装填
        player.setItemInHand(InteractionHand.OFF_HAND, taken);

        // 从袋子里抽走了一颗 ⇒ 袋子内容变了，让客户端跟上。
        // （否则 HUD 上那个弹药数会一直显示旧值 —— 作者 2026-10-15 报的「不实时更新」）
        BallPouchHelper.syncPouchToClient(player);
    }

    /**
     * <b>把副手那颗球收回袋子</b> —— 主手从弩切到别的东西时调用（作者 2026-10-15 要求）。
     *
     * <h2>为什么只在「有袋子」时收</h2>
     * <p>袋子不在身上时「收回」无处可去，只能塞进背包 —— 而背包本来就是玩家自己管的，
     * 那样反而像模组擅自挪了东西。所以没袋子就什么都不做，副手保持原样。</p>
     *
     * <h2>只收「是球」的副手</h2>
     * <p>副手拿着火把 / 盾牌 / 别的物品时一律不碰 —— 那是玩家自己的搭配。</p>
     *
     * <p>收回走 {@link BallPouchHelper#deliverReturned}，和弩射完的回收是同一套：
     * 优先袋子中转槽、退而进背包、实在不行掉地上。绝不会让那颗球凭空消失。</p>
     */
    private static void recallOffhandBall(Player player) {
        ItemStack offhand = player.getOffhandItem();
        if (offhand.isEmpty()) {
            return;
        }
        // ⚠️⚠️ **必须同时认「球堆（壳）」** —— 作者 2026-10-15 第二次反馈
        //     「副手的球在弩切出去之后为啥没有收回收纳袋」就是这里。
        //
        //     原来只判 `BallAmmo.isBall(offhand)`，而那是
        //     `stack.is(ModTags.Items.BALLS)` —— **球堆不在 BALLS 标签里**
        //     （它是容器，不是弹药），所以副手拿着球堆时这一行直接 return、什么都不做。
        //
        //     而作者的用法恰恰是「副手挂着球堆」⇒ 切走弩之后那颗球堆纹丝不动。
        //     现在「是球」或「是壳」都收；`deliverReturned` 本来就认壳，
        //     壳会被整颗收进袋子的转运段。
        if (!BallAmmo.isBall(offhand) && !BallClusterItem.isCluster(offhand)) {
            return;
        }
        if (BallPouchHelper.allPouchStacks(player).isEmpty()) {
            return;   // 没袋子 —— 不擅自挪玩家的东西
        }
        ItemStack old = offhand.copy();
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        BallPouchHelper.Delivery delivery = BallPouchHelper.deliverReturned(player, old);


        if (delivery == BallPouchHelper.Delivery.NOWHERE) {
            player.drop(old, false);
        } else if (delivery == BallPouchHelper.Delivery.INVENTORY_TRANSIT_FULL) {
            player.sendOverlayMessage(
                    Component.translatable("message.rotation_craft.transit_full"));
        }
    }

    /**
     * 玩家此刻是不是正拿着<b>这把主手弩</b>在蓄力。
     *
     * <p>只看 {@code isUsingItem()} 不够 —— 那个状态是「玩家在用<b>某样</b>东西」，
     * 副手喝药水、吃食物时同样成立。必须再确认「正在用的就是主手这把弩」，
     * 否则副手一边喝药一边被塞球就闹笑话了。</p>
     */
    private static boolean isChargingCrossbow(Player player, ItemStack mainHand) {
        return player.isUsingItem() && player.getUseItem() == mainHand;
    }

    /**
     * <b>把装填期间暂存的袋子换回副手</b> —— 作者 2026-11-12 指定流程的后半段。
     *
     * <p>顺序必须是「先把副手剩的球收进袋子，再把袋子放回副手」：
     * 球已经被原版装填消耗掉了，一般副手是空的；但玩家中途松手、或者装填被别的原因
     * 打断时，那颗球可能还挂在上头 —— 那就得先收好，别让它跟着袋子一起被顶掉。</p>
     *
     * <p>万一副手此刻被别的东西占了（玩家手快塞了个火把），袋子走
     * {@link BallPouchHelper#deliverReturned} 退回背包，<b>绝不丢在地上</b>。</p>
     */
    private static void restoreStashedPouch(Player player) {
        if (!BallPouchHelper.hasStashedPouch(player)) {
            return;
        }

        // ⚠️ 顺序：**先收球、再取袋子**。
        //    `unstashPouch` 会立刻清空隐藏槽，那之后 `allPouchStacks` 就找不到这个袋子了 ——
        //    先取袋子的话，下面 `deliverReturned` 会把球塞进背包而不是袋子，
        //    玩家看到的就是「那颗球没回袋子里」。袋子还在槽里时收球，才能落回它自己身上。
        ItemStack leftover = player.getOffhandItem();
        RotationCraft.LOGGER.info("[ball][还袋] 副手此刻={}（isBall={}），stash里球数={}",
                leftover.isEmpty() ? "空" : leftover.getItem().toString() + " x" + leftover.getCount(),
                BallAmmo.isBall(leftover),
                countInPouch(BallPouchHelper.stashedPouch(player)));
        if (!leftover.isEmpty() && BallAmmo.isBall(leftover)) {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            BallPouchHelper.deliverReturned(player, leftover.copy());
            RotationCraft.LOGGER.info("[ball][还袋] 副手的球已收回袋子");
        }

        ItemStack pouch = BallPouchHelper.unstashPouch(player);
        if (pouch.isEmpty()) {
            return;
        }
        RotationCraft.LOGGER.info("[ball][还袋] 取出袋子，球数={}，副手现在={}", countInPouch(pouch),
                player.getOffhandItem().isEmpty() ? "空" : player.getOffhandItem().getItem().toString());

        if (player.getOffhandItem().isEmpty()) {
            player.setItemInHand(InteractionHand.OFF_HAND, pouch);
            RotationCraft.LOGGER.info("[ball][还袋] 袋子已放回副手");
        } else {
            // 副手被别的东西占了 —— 袋子退回背包（进袋子自己 / 背包 / 掉落，都行），不能凭空消失
            BallPouchHelper.deliverReturned(player, pouch);
            RotationCraft.LOGGER.info("[ball][还袋] 副手被占，袋子退回背包");
        }
        BallPouchHelper.syncPouchToClient(player);
    }

    /**
     * <b>装填期间临时换手</b> —— 把副手的袋子挪进隐藏槽，换上一颗选中种类的球。
     *
     * <p>对应作者 2026-11-12 指定的前半段。做完之后原版
     * {@code CrossbowItem.tryLoadProjectiles} 就能在副手找到球、正常跑完装填动画；
     * 装填结束时由 {@link #restoreStashedPouch} 把袋子换回来。</p>
     *
     * <h2>抽不到球怎么办</h2>
     * <p>整段<b>回滚</b>：袋子原样放回副手。绝不能出现「袋子被挪走了、球又没拿到」
     * 这种两头空的局面 —— 那是把玩家的东西弄丢了。</p>
     */
    private static void borrowFromPouch(Player player, ItemStack pouch, ItemStack wanted) {
        if (!BallPouchHelper.stashPouch(player, pouch)) {
            return;
        }
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

        // 注意：`allPouchStacks` 已经把隐藏槽里的袋子算进去了（见 BallPouchHelper）。
        // ⚠️ 但**不能**假设「拿到的那份就是存储里那个对象」—— 见下面写回那段的说明。
        ItemStack taken = ItemStack.EMPTY;
        ItemStack usedPouch = ItemStack.EMPTY;
        for (ItemStack candidate : BallPouchHelper.allPouchStacks(player)) {
            int before = countInPouch(candidate);
            taken = BallPouchItem.takeLowestDurability(candidate, wanted);
            int after = countInPouch(candidate);
            RotationCraft.LOGGER.info("[ball][借球] 候选袋球数 {} -> {}，取到={}（与入参同一对象? {}）",
                    before, after,
                    taken.isEmpty() ? "空" : taken.getHoverName().getString(),
                    candidate == pouch);
            if (!taken.isEmpty()) {
                usedPouch = candidate;
                break;
            }
        }

        if (taken.isEmpty()) {
            player.setItemInHand(InteractionHand.OFF_HAND, BallPouchHelper.unstashPouch(player));
            diagnose(player, "袋子里取不到这种球（临时换手已回滚）", wanted, pouch);
            return;
        }

        // ⚠️⚠️ **扣完球必须把那个袋子显式写回隐藏槽**（作者 2026-11-13 实测：球被复制）。
        //
        //    现象：装填能成功，但袋里的球数一颗不少 —— 装十次就白拿十颗，是个刷球漏洞。
        //
        //    根因：`BallPouchItem.takeLowestDurability` 是**就地改 ItemStack 组件**的
        //    （`pouch.set(POUCH_CONTENTS, ...)`）。改的是它手里那个 ItemStack。
        //    而 attachment 的 `getData` 返回的那份**不保证**与存储里是同一个对象 ——
        //    扣球扣在临时副本上，真袋子纹丝不动，下一 tick 又能取到同样一颗。
        //
        //    所以取到球之后，把**改过的这一份**写回隐藏槽，让存储指向它。
        if (!usedPouch.isEmpty()) {
            BallPouchHelper.stashPouch(player, usedPouch);
            RotationCraft.LOGGER.info("[ball][借球] 写回stash后：stash里球数={}，countOf={}",
                    countInPouch(BallPouchHelper.stashedPouch(player)),
                    BallAmmoSelection.countOf(player, wanted));
        }

        player.setItemInHand(InteractionHand.OFF_HAND, taken);
        BallPouchHelper.syncPouchToClient(player);
    }

    /** 诊断用：这个袋子里现存的球数（两段合起来）；空栈返回 -1 */
    private static int countInPouch(ItemStack pouch) {
        if (pouch.isEmpty()) {
            return -1;
        }
        BallPouchContents contents = BallPouchItem.contents(pouch);
        return BallPouchContents.countBalls(contents.ammo())
                + BallPouchContents.countBalls(contents.transit());
    }

    /**
     * 补弹诊断补弹诊断 —— 只在「本该补弹却没补」的地方调用，并做限流避免每刻刷屏。
     *
     * <p>打出的量：选中的是哪种球、副手现在拿着什么/耐久多少、袋子里同种最低耐久、
     * 身上有几个收纳袋。这四个数一摆，「补不上来」的几种可能（没选、袋子没找到、
     * 同种没匹配上、副手判定跳过）立刻能分开。</p>
     */
    private static long lastDiagnoseTick = -100L;

    private static void diagnose(Player player, String reason, ItemStack wanted, ItemStack offhand) {
        long now = player.level().getGameTime();
        if (now - lastDiagnoseTick < 40L) {   // 2 秒最多一条
            return;
        }
        lastDiagnoseTick = now;
        RotationCraft.LOGGER.debug(
                "[ball] 补弹未发生（{}）：选中={} / 副手={}（耐久 {}）/ 袋中同种最低耐久 {} / 收纳袋 {} 个",
                reason,
                wanted.getHoverName().getString(),
                offhand.isEmpty() ? "空" : offhand.getHoverName().getString(),
                BallPouchItem.remainingToughness(offhand),
                BallPouchItem.lowestToughnessInPouches(player, wanted),
                BallPouchHelper.allPouchStacks(player).size());
    }

    /**
     * 玩家登出时清掉「上一刻是不是持弩」的记录。
     *
     * <p>不清的话它会一直挂在表里（每条只有一个 UUID + boolean，量很小，
     * 但长期开放的服务器上没必要留着）。</p>
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();

        // ⚠️ 退出前必须把暂存的袋子还回去。
        //    玩家要是在装填动画跑到一半时退出，那个袋子还压在隐藏槽里 ——
        //    不清的话它就**永久留在附件里**了：下一次登录，玩家背包里找不到袋子、
        //    副手也是空的，而数据其实还挂在身上，等于凭空消失一整袋球。
        //    这里顺手放回副手（副手多半是空的，正好接住）。
        if (!player.level().isClientSide() && BallPouchHelper.hasStashedPouch(player)) {
            restoreStashedPouch(player);
        }

        WAS_HOLDING_CROSSBOW.remove(player.getUUID());
    }
}

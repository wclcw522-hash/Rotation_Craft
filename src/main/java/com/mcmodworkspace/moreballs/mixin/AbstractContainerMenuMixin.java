package com.mcmodworkspace.moreballs.mixin;

import com.mcmodworkspace.moreballs.BallClusterContents;
import com.mcmodworkspace.moreballs.BallAmmo;
import com.mcmodworkspace.moreballs.BallClusterItem;
import com.mcmodworkspace.moreballs.BallClusterTier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * <b>背包里右键球堆 = 取出「最低耐久那一组」，而不是拿起整个球堆</b>（作者 2026-10-15 要求）。
 *
 * <h2>为什么要 Mixin</h2>
 * <p>NeoForge <b>没有</b>「容器被点击」的事件 —— 查过 {@code event/} 目录，与容器有关的只有
 * {@code PlayerContainerEvent.Open/Close}（开关界面）。而物品栏里的左键/右键行为全部由
 * 原版 {@link AbstractContainerMenu#clicked} 直接处理，没有留钩子。</p>
 *
 * <p>项目里本来就有 Mixin 框架（{@code rotation_craft.mixins.json} + CrossbowItemMixin /
 * LocalPlayerMixin），所以这里沿用同一套，而不是去魔改 UI 层。</p>
 *
 * <h2>接管的时机（四条限制，缺一不可）</h2>
 * <ol>
 *   <li>{@code input == PICKUP} —— 普通的左/右键拿起动作</li>
 *   <li>{@code button == 1} —— 右键</li>
 *   <li>光标是空的 —— 光标上有东西时不碰，免得把玩家手上的东西吞了</li>
 *   <li>那个槽里是<b>球堆</b>且不是空的</li>
 * </ol>
 * <p>四条全满足才接管；其余情况一律放行，绝不干扰原版的左键拿起、Shift 快速移动、
 * 数字键交换、丢弃等等。</p>
 *
 * <h2>「一组」是什么</h2>
 * <p>和 {@code Shift+右键扔出一组}、tooltip 里那行「共 N 颗」是<b>同一个口径</b> ——
 * 见 {@link BallClusterContents#lowestGroupSize()}：<b>耐久值相同的那一批</b>。</p>
 *
 * <h2>为什么可以一次拿一组</h2>
 * <p>同一组里的球<b>耐久完全相同</b>，也就是数据组件完全相同 —— 它们本来就能叠成一堆。
 * 所以光标上放的是「一个数量 N 的球」而不是 N 个格子，这正是玩家要的效果。</p>
 *
 * <h2>为什么改的是那个槽而不是新建栈</h2>
 * <p>壳是 {@code stacksTo(1)} 的容器，装进多少个球它都只占一格。
 * 取出之后要把<b>剩余</b>写回<b>同一个槽</b>（用 {@code slot.setByPlayer}，
 * 它会顺手触发容器同步）；取空了就写 {@code ItemStack.EMPTY}，那一格自然消失。</p>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    /** 切球诊断只打一次（定位完删掉） */
    private static boolean DIAG_CUT = false;

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void moreballs$specialClicks(int slotId, int button, ContainerInput input,
                                         Player player, CallbackInfo ci) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;

        // ===== ⓪ 双击一键全部拿起（原版 PICKUP_ALL）对球堆生效（作者 2026-11-02）=====
        //
        // 原版双击的真实协议**不是** slotId == -999（那是「点击窗口外丢弃物品」），而是：
        //   AbstractContainerScreen.mouseClicked L405 判 doubleclick + canTakeItemForPickAll，
        //   成立后 L419 发 ContainerInput.PICKUP_ALL。
        //
        // 球堆是 stacksTo(1) 的自定义物品，canTakeItemForPickAll 对它返回 false ——
        // 连 PICKUP_ALL 都发不出来，所以下面还附了一个覆写把它放行。
        if (input == ContainerInput.PICKUP_ALL) {
            if (moreballs$tidyIntoCarried(self, player)) {
                ci.cancel();
                return;
            }
        }

        // ===== ① 切石机：材料格是球堆 / 组合球时，点产物槽自己接管 =====
        // 见 moreballs$cutBalls 的注释（作者 2026-10-16 的方案）
        if (moreballs$cutBalls(self, slotId, button, input, player)) {
            ci.cancel();
            return;
        }

        // ===== ② 光标上拿着球堆 + 右键空格 = 只放出 **1 颗球**（作者 2026-10-16）=====
        //
        // ⚠️ 这是在**改造原版行为**：原版「光标上有物品 + 右键空格」会把光标上那一份
        //    **整个放进去 1 份** —— 而球堆的 count 恒为 1（一格里装着 N 颗球），
        //    于是「整个壳」会被塞进那一格。作者要的是「只落 1 颗球，壳留在光标上」。
        //
        // 只在服务端干：这一段会改球堆的组件（取走一颗），是持久状态变更；
        // 客户端那份由同步包刷回来。
        if (input == ContainerInput.PICKUP && button == 1
                && !player.level().isClientSide()
                && slotId >= 0 && slotId < self.slots.size()
                && BallClusterItem.isCluster(self.getCarried())) {
            Slot target = self.slots.get(slotId);
            if (target.getItem().isEmpty() && target.mayPlace(self.getCarried())) {
                ItemStack carried = self.getCarried();
                // takeOne 就地取走壳里**耐久最低**那颗（与右键取一颗、切石机同一条口径）
                ItemStack one = BallClusterItem.takeOne(carried);
                if (!one.isEmpty()) {
                    target.set(one);
                    // 壳被掏空时 takeOne 已经把组件摘干净了 —— 那光标上就该真的空掉
                    self.setCarried(BallClusterItem.contents(carried).isEmpty()
                            ? ItemStack.EMPTY
                            : carried);
                    self.broadcastChanges();
                    ci.cancel();
                    return;
                }
            }
        }

        // ===== ②' 光标上拿着**球** + 右键**球堆** = 把球放进去（作者 2026-10-18）=====
        //
        // ⚠️ 之前我把这段写进了 BallPouchMenu.clicked —— 那是**收纳袋自己的界面**，
        //    而作者是在**背包界面**里测的 ⇒ 永远走不到，功能等于没实现。
        //    这里才是所有容器界面（背包 / 箱子 / 收纳袋…）共用的 clicked 入口。
        //
        // ⚠️ **两端都要执行**（作者 2026-10-18 反馈「加号有但放不进」）：
        //    原来我加了 `!player.level().isClientSide()`，只在服务端改。
        //    但客户端那一侧没有我的 ci.cancel()，原版会照自己的规则处理「光标有物品 + 右键点已占用槽」
        //    （交换 / 放不进去就地返回），客户端的预测结果把服务端改的覆盖掉
        //    ⇒ 表现就是「动作像没发生」。
        //    现在两端都跑：客户端改本地立刻可见，服务端改权威，再由同步包对齐。
        if (input == ContainerInput.PICKUP && button == 1
                && slotId >= 0 && slotId < self.slots.size()) {
            ItemStack carriedBall = self.getCarried();
            ItemStack targetShell = self.slots.get(slotId).getItem();
            if (!carriedBall.isEmpty() && com.mcmodworkspace.moreballs.BallAmmo.isBall(carriedBall)
                    && !targetShell.isEmpty() && BallClusterItem.isCluster(targetShell)) {
                BallClusterContents existing = BallClusterItem.contents(targetShell);
                // 同球种（matches 只比球种、不比档位）+ 还有空位；withAdded 失败会返回同一引用
                if (existing.hasRoom() && existing.matches(carriedBall)) {
                    BallClusterContents after = existing.withAdded(carriedBall);
                    if (after != existing) {
                        // ⚠️⚠️ **必须「复制 → 改组件 → 写回槽位」**，不能直接改 getItem() 返回的那个对象。
                        //
                        //    这是本方法里相邻两个分支的既有写法，我原先没照着来：
                        //      · ② 落一颗（L85）：`target.set(one)`
                        //      · ③ 取一组（L184/188）：`slot.setByPlayer(shellAfter)`
                        //    只有 ②' 直接 `setContents(targetShell, after)` —— 改的是从槽位取出来的那个
                        //    ItemStack，**不会触发槽位更新**，服务端与客户端都看不到变化 ⇒ 表现为「放不进去」。
                        ItemStack shellAfter = targetShell.copy();
                        BallClusterItem.setContents(shellAfter, after);
                        self.slots.get(slotId).setByPlayer(shellAfter);

                        // 光标上按**实际放进去的数量**扣减（作者 2026-10-18：一次全放进去）。
                        // withAdded 内部是 `add = min(ball.getCount(), room)` ——
                        // 空间够就整摞收下、不够就收 room 个，所以这里不能写死 shrink(1)。
                        // 差值 = 这次真正进壳的颗数。
                        int added = after.size() - existing.size();
                        carriedBall.shrink(added);
                        self.setCarried(carriedBall.isEmpty() ? ItemStack.EMPTY : carriedBall);
                        self.broadcastChanges();
                        ci.cancel();
                        return;
                    }
                }
            }
        }
        // ===== ③ 背包里右键球堆 = 取出最低耐久那一组 =====
        // 只认「右键拿起」
        if (input != ContainerInput.PICKUP || button != 1) {
            return;
        }

        // ③ 光标必须空着
        if (!self.getCarried().isEmpty()) {
            return;
        }
        // 槽位合法性（原版允许 slotId 为 -1，表示点在了界面外面）
        if (slotId < 0 || slotId >= self.slots.size()) {
            return;
        }

        // ④ 目标是球堆
        Slot slot = self.slots.get(slotId);
        ItemStack shell = slot.getItem();
        if (shell.isEmpty() || !BallClusterItem.isCluster(shell)) {
            return;
        }
        BallClusterContents contents = BallClusterItem.contents(shell);
        if (contents.isEmpty()) {
            return;
        }
        if (!slot.mayPickup(player) || !slot.allowModification(player)) {
            return;   // 原版或别的模组把这个槽锁了 —— 尊重它
        }

        // ===== 取「最低耐久那一组」=====
        int groupSize = contents.lowestGroupSize();
        List<ItemStack> balls = contents.balls();
        if (groupSize <= 0 || balls.isEmpty()) {
            return;
        }
        // 同组球组件完全一致 ⇒ 一个数量 groupSize 的栈就代表它们全部
        ItemStack taken = balls.get(0).copyWithCount(groupSize);

        // 剩余写回同一个槽（壳只占一格，掏空了那一格就消失）
        BallClusterContents rest = contents.withoutFirst(groupSize);
        if (rest.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            ItemStack shellAfter = shell.copy();
            BallClusterItem.setContents(shellAfter, rest);
            slot.setByPlayer(shellAfter);
        }

        self.setCarried(taken);   // 放到光标上
        self.broadcastChanges();
        slot.onTake(player, taken);
        ci.cancel();              // 挡掉原版的「拿起整个球堆」
    }

    /**
     * <b>切石机里的球堆：整堆留在材料格，每取一次产物只消耗里面的一颗球</b>
     * （作者 2026-10-16 提出的方案）。
     *
     * <h2>它解决的问题</h2>
     * <p>原版切石机一次消耗「1 个物品」（{@code resultSlot.onTake} 里那句
     * {@code inputSlot.remove(1)}），而球堆 {@code count} 恒为 1 —— 不处理的话
     * 点一下就整个球堆没了。中间试过「摊成一叠 N 颗」，但那会把耐久统一成最低那颗
     * （作者反馈「损耗严重」），已废弃。</p>
     *
     * <h2>现在这一版（作者的原话 + 后续两处修正）</h2>
     * <p>「球堆可以整个放进去，然后，再点击产物拿起的瞬间……球堆回到背包 →
     * 填充一颗球到材料格 → 选中刚刚选的配方 → 完成一次切球 → 把球堆放回材料格 →
     * 选中刚刚的配方」—— 也就是在<b>取产物的那一瞬间</b>做一次原子操作。</p>
     *
     * <p>⚠️ <b>只服务端执行</b>：{@code AbstractContainerMenu#clicked} 在客户端（本地预测）
     * 与服务端都会跑，两端都做这套操作的话<b>每点一次会消耗两颗</b> ——
     * 作者实测报的「球堆放进去点了两次产物就给球堆切没了」就是它。</p>
     *
     * <p>⚠️ <b>光标上有东西时照样能切</b>（作者的第二个修正）：那时产物直接进背包，
     * 不再要求光标必须空 —— 手里拿着别的东西时，材料/产物格本来就点不动产物，
     * 挡掉反而切不了。</p>
     *
     * <h2>两种点击</h2>
     * <ul>
     *   <li><b>左键</b>（光标空）—— 切一颗，产物拿在光标上（与「拿起产物」一致）；
     *       光标上有东西时产物进背包</li>
     *   <li><b>Shift + 左键</b> —— <b>一次做完</b>：能切几颗切几颗，产物全进背包
     *       （背包塞不下就停手，剩下留在材料格里）</li>
     * </ul>
     *
     * <h2>为什么能拦在这里</h2>
     * <p>{@code AbstractContainerMenu#clicked} 是「玩家点击槽位」的唯一入口，
     * 而本类早就注入了它的 HEAD（背包右键取一组那个功能）。这里只是多加一个分支，
     * 用的是原版公开的 {@link StonecutterMenu} 槽位与 {@code clickMenuButton}
     * —— 不碰原版代码，也不需要新开 Mixin。</p>
     *
     * @return true 表示这次点击已被接管（调用方要 {@code ci.cancel()}）
     */
    private static boolean moreballs$cutBalls(AbstractContainerMenu self, int slotId, int button,
                                              ContainerInput input, Player player) {
        // ★ 只在服务端做（客户端那次只是本地预测，两端都做 = 双倍消耗）
        if (player.level().isClientSide()) {
            return false;
        }
        if (!(self instanceof StonecutterMenu menu) || slotId != StonecutterMenu.RESULT_SLOT) {
            return false;
        }

        boolean quickMove = input == ContainerInput.QUICK_MOVE;              // Shift + 左键
        boolean pickUp = input == ContainerInput.PICKUP && button == 0;      // 普通左键
        if (!quickMove && !pickUp) {
            return false;   // 右键 / 中键 / 丢弃一律放行
        }

        Slot inputSlot = menu.getSlot(StonecutterMenu.INPUT_SLOT);
        Slot resultSlot = self.getSlot(StonecutterMenu.RESULT_SLOT);
        ItemStack material = inputSlot.getItem();

        boolean cluster = BallClusterItem.isCluster(material);
        boolean combo = material.is(com.mcmodworkspace.moreballs.ModItems.COMBO_BALL.get());
        if (!cluster && !combo) {
            return false;   // 普通球 → 走原版（本来就是一次切一颗）
        }
        if (cluster && BallClusterItem.contents(material).isEmpty()) {
            return false;   // 空壳
        }

        int selected = menu.getSelectedRecipeIndex();
        if (selected < 0 || resultSlot.getItem().isEmpty()) {
            return false;   // 还没选配方 / 配方算不出东西
        }

        // ===== 组合球**禁止对半切**（作者 2026-11-02）=====
        //
        // 作者报：「四合一球在切开时，按住 shift 一键合成会导致只获得 1 个四分之一球和
        // 随机两个组件的二分之一球，其余物品全部被吞掉。」
        //
        // 根因链：
        //   · 组合球是四象限结构，只有「四等分」才表达得出全部内容；
        //     半球只是球的「上下两半」，对组合球没有意义（作者 2026-10-16 已经说过
        //     「4 合一不能对半切」）。
        //   · `BallCuttingRecipe.matches` 里确实写了这条排除，但配方列表是按
        //     `selectByInput` 算出来的，一旦某个路径漏掉它、玩家又选到了半球配方，
        //     `giveRestPieces` 会按 `quarter = false` 只补 2 份 ⇒ **另外两个象限直接消失**。
        //
        // 所以在**实际产出点**再拦一次：组合球 + 产物是半球 ⇒ 吞掉这次点击，什么都不做。
        // （`return true` 而不是 `false`：`false` 会让原版接手，而原版会真的产出一份半球。）
        if (combo && resultSlot.getItem().is(com.mcmodworkspace.moreballs.ModItems.BALL_HALF.get())) {
            return true;
        }

        // ===== 耐久门槛：组合球与球堆同样要过 80%（作者 2026-10-16）=====
        // 球的耐久记在球自己身上（球堆的耐久记在**壳里那颗球**上），所以统一摊开来看。
        // 球堆里的球都是**同一档位**的（BallClusterContents.matches 要求同档位），
        // 所以「看最低那颗」就等于看整堆 —— 与 BallCuttingRecipe.matches 同一口径。
        //
        // ⚠️ 不达标时必须**吞掉这次点击**（什么都不做），绝不能 return false 漏给原版 ——
        //    原版对产物槽的这次点击会把整个球堆消耗掉（作者实测踩过这个）。
        ItemStack peek = cluster ? BallClusterItem.asBall(inputSlot.getItem()) : material;
        if (peek.isEmpty() || !com.mcmodworkspace.moreballs.BallItem.canBeCut(peek)) {
            return true;   // 门槛不达标 → 点不动
        }

        // ===== 这个点击该不该「点不动」=====
        // 作者 2026-10-16 明确：**要像原版那样** ——
        // 光标上拿着**同种产物**时再点，产物就**叠加上去**；
        // 光标与产物不一致（或叠了会超上限）时，按原版的「点不动」处理（什么也不发生）。
        //
        // ⚠️ 「点不动」必须是**我们接管之后什么都不做**，绝不能 return false 漏给原版 ——
        //    原版对产物槽的这次点击会把整个球堆消耗掉，那就是作者实测踩到的那个 bug。
        ItemStack preview = resultSlot.getItem();
        if (pickUp && !self.getCarried().isEmpty()) {
            ItemStack cur = self.getCarried();
            if (!ItemStack.isSameItemSameComponents(cur, preview)
                    || cur.getCount() + preview.getCount() > cur.getMaxStackSize()) {
                return true;   // 吞掉这次点击，什么都不做
            }
        }

        int rounds = quickMove ? com.mcmodworkspace.moreballs.BallClusterContents.MAX_BALLS : 1;

        int made = 0;
        for (int n = 0; n < rounds; n++) {
            // ===== 这一轮消耗的目标 =====
            //  · 材料是球堆 → 取走壳里耐久最低那一颗，剩余的壳暂存在局部变量里
            //    （不落地也不进背包 —— 那样会凭空多出第二份）
            //  · 材料是组合球本体 → 就是它自己，切完材料格清空
            ItemStack one;
            ItemStack rest;
            if (cluster) {
                ItemStack shell = inputSlot.getItem();
                if (!BallClusterItem.isCluster(shell)
                        || BallClusterItem.contents(shell).isEmpty()) {
                    break;
                }
                one = BallClusterItem.takeOne(shell);
                rest = shell.copy();
            } else {
                one = material;
                rest = ItemStack.EMPTY;
            }
            if (one.isEmpty()) {
                break;
            }
            boolean isCombo = one.is(com.mcmodworkspace.moreballs.ModItems.COMBO_BALL.get());
            boolean hasRest = cluster && !BallClusterItem.contents(rest).isEmpty();

            // ===== 那颗球进材料格 → 重新选中配方 → 算产物 =====
            // （材料换了物品，原版 slotsChanged 会把配方列表重置，所以必须重选）
            inputSlot.set(one);
            menu.clickMenuButton(player, selected);
            ItemStack product = resultSlot.getItem().copy();
            if (product.isEmpty()) {
                // 算不出产物（不该发生）→ 把球堆放回去收手，绝不吞球
                inputSlot.set(hasRest ? rest : ItemStack.EMPTY);
                break;
            }

            // ===== 产物交出去 =====
            // 左键：光标空就拿在手上；光标上已是同种产物就**叠加上去**
            // （能不能叠上面已经判过，这里直接加）
            // Shift 左键：直接进背包；背包满了这一份掉脚下然后收手
            if (pickUp) {
                ItemStack cur = self.getCarried();
                if (cur.isEmpty()) {
                    self.setCarried(product);
                } else {
                    cur.grow(product.getCount());
                    self.setCarried(cur);
                }
            } else if (!player.getInventory().add(product)) {
                // 背包满了：这一份掉脚下，然后收手（剩下的留在材料格里）
                player.drop(product, false);
                inputSlot.set(hasRest ? rest : ItemStack.EMPTY);
                made++;
                break;
            }

            // ===== 组合球：把「来源与产物槽不同的那几份」补进背包 =====
            // 产物槽里只放得下「与第一份同来源的那几份」（同来源可以叠），
            // 其余份在这里发出去；背包塞不下就直接扔地上（作者指定）。
            if (isCombo) {
                // 一次性诊断（作者 2026-11-02：「用光标一个一个点产物槽都不会多产物，
                // 为什么 shift 一键合成就会多呢？」——先看清 shift 这条路上到底发生了什么）
                if (!DIAG_CUT) {
                    DIAG_CUT = true;
                    com.mcmodworkspace.moreballs.MoreBalls.LOGGER.info(
                            "[切球诊断] quickMove={} pickUp={} product={}x{} keptSource={} 材料={}",
                            quickMove, pickUp, product.getItem(), product.getCount(),
                            product.get(com.mcmodworkspace.moreballs.ModComponents.FRAGMENT_SOURCE.get()),
                            one.getItem());
                }
                moreballs$giveRestPieces(player, one, product);
            }

            // ===== 材料复位 =====
            if (cluster) {
                inputSlot.set(hasRest ? rest : ItemStack.EMPTY);
                if (hasRest) {
                    menu.clickMenuButton(player, selected);
                }
            } else {
                inputSlot.set(ItemStack.EMPTY);
            }
            made++;
            // ⚠️ 产物槽要清，但**不能在这里清** —— 见下面 `broadcastChanges()` 之后的说明。
            if (!cluster) {
                break;   // 组合球本体只切一次
            }
            if (!hasRest) {
                break;   // 最后一颗切完 → 收工
            }
        }

        if (made == 0) {
            return false;
        }
        // ===== 产物槽清空 + 同步（作者 2026-11-02）=====
        //
        // 作者原话：「用光标一个一个点产物槽都不会多产物，为什么 shift 一键合成就会多呢？」
        //
        // 根因就在这两句的**顺序**上。`StonecutterMenu.broadcastChanges()` 会把产物槽
        // 按当前材料格+选中配方**重新算一遍**写回去 —— 所以先清、后 broadcast，等于白清：
        // 槽里又冒出一份第一象限的四分之一球，玩家继续 shift 点产物槽就又能拿一份。
        //
        // 光标点击之所以看不出来：那份直接进了光标，玩家一般不会在同一格上连点。
        // shift 则是一次接一次往背包塞，多出来的那份立刻表现为「多一倍」。
        //
        // 正确顺序：先让原版同步完，**最后**再把产物槽压成空，然后才返回。
        self.broadcastChanges();
        resultSlot.set(ItemStack.EMPTY);
        return true;
    }

    /**
     * <b>组合球切开的「其余份」</b>（作者 2026-10-16 定稿的规则）。
     *
     * <p>产物槽里那份代表「与第一份同来源的那几份」（同来源可以叠成一栈，
     * 数量由 {@code BallCuttingRecipe.assemble} 算好），所以这里只发
     * <b>来源与它不同的份</b>，直接进背包；<b>背包也放不下就扔在地上</b> ——
     * 作者明确要求不让它凭空消失。</p>
     *
     * <p>份数随模板走：四分之一 4 份、半球 2 份（见
     * {@link com.mcmodworkspace.moreballs.BallCuttingRecipe#comboPieceSource}）。</p>
     */
    private static void moreballs$giveRestPieces(Player player, ItemStack combo, ItemStack product) {
        Integer keptSource = product.get(
                com.mcmodworkspace.moreballs.ModComponents.FRAGMENT_SOURCE.get());
        // ⚠️ 组合球**一律按四等分补**（作者 2026-11-02）。
        //
        //   组合球是四象限结构，四个象限各自独立。若因为漏拦而走了半球配方
        //   （`product` 是 ball_half），按 `quarter = false` 只补 2 份的话，
        //   **另外两个象限的材质就凭空消失了** —— 正是作者报的「其余物品全部被吞掉」。
        //
        //   这里以「材料是不是组合球」为准而不是看产物：只要切的是组合球，
        //   就一定是四分四片，一份都不能少。（`cutBalls` 那边已经先拦掉了半球，
        //   这里是第二道保险，保证任何路径都不会丢内容。）
        boolean comboMaterial = combo.is(com.mcmodworkspace.moreballs.ModItems.COMBO_BALL.get());
        boolean quarter = comboMaterial
                || product.is(com.mcmodworkspace.moreballs.ModItems.BALL_QUARTER.get());
        int total = quarter ? 4 : 2;
        // ⚠️⚠️ **产物槽已经发了几份同来源的，这里就跳过几份**（作者 2026-11-02）。
        //
        //   原来写的是「source == keptSource 就跳」—— 按**来源**跳、把该来源全跳过。
        //   配合 `assemble` 的 `setCount(Math.max(1, same))`：
        //     · 产物槽代表 2 份、循环只碰到 1 个同来源的 i ⇒ 多发 1 份
        //     · 产物槽代表 1 份、该来源占两个象限      ⇒ 少发 1 份
        //
        //   改成按**份数**跳：`product.getCount()` 是几就跳几次，减到 0 才继续补。
        //   于是「产物槽份数 + 补发份数」恒等于 4（四合一）/ 2（半球）。
        // 产物槽**恒 1 份**（见 assemble），所以这里固定只跳过 1 份 —— 补发恒 3 份。
        // 不读 product.getCount()：那个值一旦和 assemble 不同步，shift 时就会多一倍或少一半。
        int keptLeft = keptSource == null ? 0 : 1;
        for (int i = 0; i < total; i++) {
            int source = com.mcmodworkspace.moreballs.BallCuttingRecipe
                    .comboPieceSource(combo, i, quarter);
            if (source < 0) {
                continue;   // 这个象限本来就是空的
            }
            if (keptSource != null && source == keptSource && keptLeft > 0) {
                keptLeft--;
                continue;   // 产物槽里那一份已经代表它了
            }
            ItemStack piece = new ItemStack(quarter
                    ? com.mcmodworkspace.moreballs.ModItems.BALL_QUARTER.get()
                    : com.mcmodworkspace.moreballs.ModItems.BALL_HALF.get());
            piece.set(com.mcmodworkspace.moreballs.ModComponents.FRAGMENT_SOURCE.get(), source);
            if (!player.getInventory().add(piece)) {
                player.drop(piece, false);
            }
        }
    }

    /**
     * 让原版双击对球堆放行（作者 2026-11-02）。
     *
     * <p>原版 {@code AbstractContainerScreen.mouseClicked} 的双击分支要求
     * {@code canTakeItemForPickAll} 为 true 才会发 {@code PICKUP_ALL}。
     * 球堆是 {@code stacksTo(1)} 的自定义物品，原版这套判据对它返回 false，
     * 双击于是什么都不发生 —— 这就是「双击整理对球堆没反应」的真正原因。</p>
     *
     * <p>这里对球堆一律放行，随后的 {@code PICKUP_ALL} 由
     * {@code moreballs$specialClicks} 接管。</p>
     */
    @Inject(method = "canTakeItemForPickAll", at = @At("HEAD"), cancellable = true, require = 0)
    private void moreballs$allowDoubleClickOnCluster(ItemStack carried, Slot slot,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (slot != null && BallClusterItem.isCluster(slot.getItem())) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 双击一键收纳球堆（作者 2026-11-02）。
     *
     * <p>作者原话：「原版的光标拿着物品双击一键收纳的功能要对球堆也生效，
     * 光标拿着球堆的时候，双击将符合条件的球和球堆内的球整理到光标处；
     * 手里拿着球的时候，先瞬间新建一个符合条件的球堆把光标处的球放进去再进行整理」。</p>
     *
     * @return true = 这次点击已被接管（调用方要 ci.cancel()）
     */
    private static boolean moreballs$tidyIntoCarried(AbstractContainerMenu self, Player player) {
        ItemStack carried = self.getCarried();
        if (carried.isEmpty()) {
            return false;
        }

        boolean tidied = false;

        if (BallClusterItem.isCluster(carried)) {
            // 情况 A：光标上已经是壳 —— 直接往壳里收
            tidied = moreballs$absorbInto(player, carried);
        } else if (BallAmmo.isBall(carried)
                && BallClusterTier.of(carried).isClustered()) {
            // 情况 B：光标上是「用过的球」—— 先就地封成壳放进光标，再收
            ItemStack shell = BallClusterItem.clusterize(carried.copy());
            if (BallClusterItem.isCluster(shell)) {
                self.setCarried(shell);
                tidied = moreballs$absorbInto(player, shell);
            }
        }

        if (tidied) {
            self.broadcastChanges();
        }
        return tidied;
    }

    /**
     * 把背包里所有能并进 target 这个壳的球 / 球堆并进去，并清空那些来源槽。
     *
     * @return 有没有真的并进去过东西
     */
    private static boolean moreballs$absorbInto(Player player, ItemStack target) {
        boolean changed = false;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()) {
                continue;
            }

            if (BallClusterItem.isCluster(slot)) {
                // ===== 球堆：**先逐颗取出再合并**（作者 2026-11-02）=====
                //
                // 作者原话：「双击拿起会把球堆合并，应该是先取出球再合并，
                // 也要注意总量超过 64 时应当把耐久最高的留在原位置不参与合并」。
                //
                // `takeOne` 每次都取**耐久最低**那颗，所以：
                //   · 目标壳满了就停 → 留在原槽里的自然就是**耐久最高**的那些，且位置不变；
                //   · 一颗都没并进去时不动这个槽。
                ItemStack shell = slot.copy();
                boolean any = false;
                while (true) {
                    BallClusterContents cur = BallClusterItem.contents(target);
                    if (!cur.hasRoom()) {
                        break;                       // 满了 → 剩下的留在原位置
                    }
                    ItemStack one = BallClusterItem.takeOne(shell);   // 取最低耐久
                    if (one.isEmpty()) {
                        break;
                    }
                    BallClusterContents after = cur.withAdded(one);
                    if (after == cur) {
                        break;                       // 加不进去 → 停
                    }
                    BallClusterItem.setContents(target, after);
                    any = true;
                    changed = true;
                }
                if (any) {
                    inventory.setItem(i, BallClusterItem.contents(shell).isEmpty()
                            ? ItemStack.EMPTY : shell);
                }
                continue;
            }

            // ===== 散球：直接并 =====
            ItemStack probe = BallClusterItem.asBall(slot);
            if (probe.isEmpty()) {
                continue;
            }
            BallClusterContents cur = BallClusterItem.contents(target);
            if (!cur.hasRoom() || !cur.matches(probe)) {
                continue;
            }
            // withAdded 失败会静默返回 this —— 必须核对结果再动来源槽
            BallClusterContents after = cur.withAdded(probe);
            if (after == cur) {
                continue;
            }
            BallClusterItem.setContents(target, after);
            inventory.setItem(i, ItemStack.EMPTY);
            changed = true;
        }
        return changed;
    }
}

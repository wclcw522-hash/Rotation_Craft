package com.mcmodworkspace.moreballs.mixin;

import com.mcmodworkspace.moreballs.BallClusterItem;
import com.mcmodworkspace.moreballs.BallClusterTier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>Q 键扔球堆时，扔出去的是<b>一颗球</b>，壳留在手上</b>（作者 2026-11-02：
 * 「q 键扔出应该一个一个地扔出球，而不是扔出球堆后解压」）。
 *
 * <h2>为什么需要拦这里</h2>
 * <p>原版 Q 键走 {@code ServerPlayer.drop(boolean)}：</p>
 * <pre>{@code
 * ItemStack selected = inventory.getSelectedItem();
 * ItemStack removed = inventory.removeFromSelected(all);   // ← 整个栈
 * CommonHooks.onPlayerTossEvent(this, removed, false, true);
 * }</pre>
 * <p>球堆是 {@code stacksTo(1)} 的，所以「整个栈」= <b>整个壳</b> —— 一按 Q 就把装着
 * 几十颗球的壳整个扔出去了。作者要的是像普通球那样<b>一颗一颗扔</b>。</p>
 *
 * <h2>为什么区分 Q 键与「背包一键扔出」</h2>
 * <p>两者最终都会走到 {@code Player.drop(ItemStack, boolean)} → {@code onPlayerTossEvent}，
 * 光看那个分不出来。但 <b>{@code ServerPlayer.drop(boolean)} 只有 Q 键（和 Ctrl+Q）会调</b>，
 * 背包的一键扔出是遍历背包逐格调 {@code drop(stack, ...)} —— 所以拦在
 * {@code ServerPlayer.drop(boolean)} 就是精确的「Q 键」。</p>
 *
 * <p>背包一键扔出那边保持原样（整堆扔出），由 {@code BallClusterDropHandler}
 * 在掉落物生成后拆散。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void moreballs$dropOneBallFromCluster(boolean all, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;

        ItemStack selected = self.getInventory().getSelectedItem();
        if (!BallClusterItem.isCluster(selected)) {
            return;   // 不是球堆 → 原版行为
        }
        if (BallClusterItem.contents(selected).isEmpty()) {
            return;   // 空壳 → 交给原版（它自己会处理掉）
        }

        // 取一颗（耐久最低那颗，与右键取球同一口径），壳留在手上的那一格里
        ItemStack shell = selected.copy();
        ItemStack one = BallClusterItem.takeOne(shell);
        if (one.isEmpty()) {
            return;
        }

        // 壳还有内容就写回原格；被掏空就置空（不能留空壳）
        ItemStack remainder = BallClusterItem.contents(shell).isEmpty() ? ItemStack.EMPTY : shell;
        self.getInventory().setItem(self.getInventory().getSelectedSlot(), remainder);
        // 让当前打开的容器界面同步这一格（背包 / 箱子都可能开着）
        self.containerMenu
                .findSlot(self.getInventory(), self.getInventory().getSelectedSlot())
                .ifPresent(slotIndex -> self.containerMenu.setRemoteSlot(slotIndex, remainder));

        // ⚠️ 原版 `ServerPlayer.drop` 里有一句「useItem 空了就 stopUsingItem」。
        //    这里**故意不做**：原版是把选中格整个移除，手上真的空了才算「用完了」；
        //    我们只拿走一颗球、壳还留在那一格，蓄力状态不该被打断。
        //    （另外 `useItem` 是 protected，Mixin 类不在 ServerPlayer 继承链上，也访问不到。）

        // 把那一颗球按原版的方式丢出去（会触发既有的 PlayerTossEvent / 掉落物生成逻辑）
        self.drop(one, true);

        ci.cancel();
    }
}

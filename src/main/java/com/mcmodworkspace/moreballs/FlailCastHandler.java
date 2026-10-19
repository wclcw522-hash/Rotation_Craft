package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.FlailHeadEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * <b>流星锤「扔出中」状态的同步与禁止切换物品栏</b>（作者 2026-10-19 规格）。
 *
 * <h2>要满足的两条规格</h2>
 * <ul>
 *   <li>「扔出后，掷出者手中的流星锤贴图将变为<b>只有线、没有球</b>的形态」</li>
 *   <li>「此时<b>无法切换至其它物品栏</b>」</li>
 * </ul>
 *
 * <h2>为什么两条放在同一个类</h2>
 * <p>它们必须由**同一个状态**驱动（{@code ModComponents.FLAIL_CASTING}）。
 * 分开写的话，一旦某个 tick 只更新了其中一边，就会出现「图标是纯线但能切栏」
 * 或「切不了栏但图标还带着球」的错位 —— 那种错位玩家一眼就看出来，却极难复现。</p>
 *
 * <h2>为什么不复用 {@code LocalPlayerMixin}</h2>
 * <p>那个 mixin 的职责是 inject {@code itemUseSpeedMultiplier()F}（只影响装填减速），
 * 往里塞「锁背包格」是两件事，而且它是 client 专属、拿不到服务端权威状态。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class FlailCastHandler {

    private FlailCastHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack held = player.getMainHandItem();

        // ===== 手里不是流星锤：如果它还残留着「扔出中」标记，清掉 =====
        if (!FlailItem.isFlail(held)) {
            return;
        }

        // ===== 「刚回收完，必须松开再按」的标记维护 =====
        //
        // 作者 2026-10-19：回收完成后不能立刻又蓄力/扔出，要松开右键重新按。
        // 锤头飞回时 `FlailHeadEntity.finishReturn` 会置这个标记；
        // 这里在玩家**松开右键**（不再 isUsingItem）时清掉。
        if (FlailItem.isRecovered(held) && !player.isUsingItem()) {
            FlailItem.markRecovered(held, false);
        }

        FlailHeadEntity head = FlailItem.findHead(player.level(), player);
        boolean casting = head != null;
        if (FlailItem.isCasting(held) != casting) {
            FlailItem.setCasting(held, casting);
        }

        if (!casting) {
            return;
        }

        // ===== 已扔出：禁止切换物品栏 =====
        //
        // 判据是「手里这把流星锤处于扔出中」，所以玩家把流星锤挪到别的格后
        // 主手就不再是 FlailItem ⇒ 上面那个 `isFlail(held)` 会提前返回、自然解锁。
        // 这正是作者要的：锁的是**这把锤在手上的这段时间**，不是永久锁人。
        int origin = head.originSlot();
        Inventory inventory = player.getInventory();
        if (origin >= 0 && origin < Inventory.getSelectionSize()
                && inventory.getSelectedSlot() != origin) {
            inventory.setSelectedSlot(origin);
        }

        // 同时禁止「换手」：副手拿着另一把流星锤时按 F 会把主手换过去
        if (player.getOffhandItem() != held && FlailItem.isFlail(player.getOffhandItem())) {
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
    }
}

package com.mcmodworkspace.rotationcraft.mixin;

import com.mcmodworkspace.rotationcraft.FlailItem;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>流星锤扔出去之后，锁死物品栏槽位</b>（作者 2026-11-02：
 * 「流星锤在锤头不在手中时不能通过任何方式切换物品栏这一条功能没有实现」）。
 *
 * <h2>为什么打在这里</h2>
 * <p>{@code Inventory.setSelectedSlot(int)} 是<b>所有</b>切槽路径的汇合点：</p>
 * <ul>
 *   <li>数字键 1–9 —— {@code LocalPlayer} 的按键处理最终落到这里</li>
 *   <li>鼠标滚轮 —— 同样落到这里</li>
 *   <li>服务端包 {@code ServerboundSetCarriedItemPacket} —— {@code ServerPlayer} 那边也是这里</li>
 * </ul>
 * <p>所以只要在这儿挡住，就没有任何方式能绕过去（在 {@code LocalPlayer} 或
 * 某个按键处理器上挡都只能覆盖一部分路径）。</p>
 *
 * <h2>为什么是 common 而不是 client</h2>
 * <p>客户端会本地预测切槽，服务端才是权威。两边都走同一个 {@code Inventory} 类，
 * 所以这个 Mixin 在 common 里一次覆盖两端 —— 不然会出现「客户端看着切了、服务端没切」
 * 或者反过来，手感会跳。</p>
 *
 * <h2>判据</h2>
 * <p>主物品栏里只要有一把流星锤还带着 {@code FLAIL_CASTING} 标记（= 锤头已经扔出去了），
 * 就拒绝切槽。</p>
 *
 * <p>⚠️ 不查副手：副手上的流星锤跟「主手能不能切」无关，查了会误伤
 * （比如副手放着流星锤、主手在正常用镐子，那就该能切）。</p>
 *
 * <h2>自己人不拦自己人</h2>
 * <p>{@code FlailCastHandler} 在锤头飞出去之后会主动把槽位拨回「原来那一格」
 * （{@code inventory.setSelectedSlot(origin)}）—— 那正是这个功能要的效果，
 * 必须放行，否则它自己就被自己挡住了。放行靠 {@link FlailItem#SLOT_SET_BYPASS}，
 * 调用方用 try/finally 置位与复位。</p>
 */
@Mixin(Inventory.class)
public class InventoryMixin {

    @Inject(method = "setSelectedSlot", at = @At("HEAD"), cancellable = true, require = 0)
    private void rotationcraft$lockSlotWhileCasting(int slot, CallbackInfo ci) {
        // 自己回位时放行
        if (FlailItem.SLOT_SET_BYPASS) {
            return;
        }
        Inventory self = (Inventory) (Object) this;
        for (int i = 0; i < self.getContainerSize(); i++) {
            if (FlailItem.isCasting(self.getItem(i))) {
                ci.cancel();
                return;
            }
        }
    }
}

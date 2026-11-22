package com.mcmodworkspace.rotationcraft.mixin.client;

import net.minecraft.client.renderer.entity.state.WolfRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import com.mcmodworkspace.rotationcraft.client.DogHeldBallAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给 {@link WolfRenderState} 加一个「狗嘴里叼着的球」的渲染状态（作者 2026-11-19）。
 *
 * <h2>为什么需要 Mixin</h2>
 * <p>原版的 {@code FoxRenderState} 是 {@code extends HoldingEntityRenderState}，
 * 那个类自带 {@code heldItem}（{@code ItemStackRenderState}）字段和
 * {@code extractHoldingEntityRenderState(...)} 这个静态填充器 —— 狐狸叼物品就是靠它。</p>
 *
 * <p>但 {@code WolfRenderState extends LivingEntityRenderState}，<b>Mixin 改不了父类</b>，
 * 所以只能自己挂一个同类型的字段，填法照抄原版那一行
 * （{@code itemModelResolver.updateForLiving(state.heldItem, stack, ItemDisplayContext.GROUND, entity)}），
 * 见 {@link WolfRendererMixin}。</p>
 *
 * <p>字段名带 {@code rotationCraft$} 前缀 —— Mixin 的惯例，避免与原版/其它模组撞名。</p>
 */
@Mixin(WolfRenderState.class)
public class WolfRenderStateMixin implements DogHeldBallAccess {

    /** 狗嘴里那件「掉落物形态」的东西；没叼东西时为空 state（由 renderer 每帧 clear + 重填） */
    @Unique
    public final ItemStackRenderState rotationCraft$dogHeldBall = new ItemStackRenderState();

    /** 接口实现 —— 调用方通过 {@link DogHeldBallAccess} 读，**不能**强转本 Mixin 类 */
    @Override
    public ItemStackRenderState rotationCraft$getDogHeldBall() {
        return this.rotationCraft$dogHeldBall;
    }
}

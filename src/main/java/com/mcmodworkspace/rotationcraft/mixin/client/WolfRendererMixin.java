package com.mcmodworkspace.rotationcraft.mixin.client;

import com.mcmodworkspace.rotationcraft.ModAttachments;
import net.minecraft.client.renderer.entity.WolfRenderer;
import net.minecraft.client.renderer.entity.state.WolfRenderState;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.mcmodworkspace.rotationcraft.client.DogHeldBallAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每帧把「狗嘴里叼着的球」填进 {@link WolfRenderState}（作者 2026-11-19）。
 *
 * <h2>照抄原版的哪一行</h2>
 * <p>{@code FoxRenderer.extractRenderState} 里是：</p>
 * <pre>
 * HoldingEntityRenderState.extractHoldingEntityRenderState(entity, state, this.itemModelResolver);
 * </pre>
 * <p>而那个静态方法内部只有一行：</p>
 * <pre>
 * itemModelResolver.updateForLiving(state.heldItem, entity.getMainHandItem(),
 *                                   ItemDisplayContext.GROUND, entity);
 * </pre>
 * <p>所以这里用完全相同的调用，只是喂进去的是<b>模组附件里的球</b>而不是主手物品。</p>
 *
 * <h2>为什么注入在 TAIL</h2>
 * <p>{@code extractRenderState} 末尾就是「所有状态都已收集完」的时刻，
 * 父类 {@code super.extractRenderState(...)} 已经跑过。放在 HEAD 会被后续覆盖，
 * 放在 TAIL 才稳。</p>
 *
 * <h2>为什么带 {@code require = 0}</h2>
 * <p>原版若改了方法签名，这里安静跳过（狗只是不显示嘴里那颗球），
 * <b>不会崩游戏</b> —— 这是本项目对一切 Mixin 的硬性要求。</p>
 */
@Mixin(WolfRenderer.class)
public abstract class WolfRendererMixin {


    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void rotationCraft$extractDogHeldBall(Wolf entity, WolfRenderState state,
                                                  float partialTicks, CallbackInfo ci) {
        ItemStack held = entity.getData(ModAttachments.DOG_HELD_BALL.get());
        if (held.isEmpty()) {
            return;   // 嘴里没东西 ⇒ 保持空 state（下一帧会被 clear 掉）
        }
        // 与 FoxRenderer 里那一行完全同构：GROUND 上下文 ⇒ 物品平躺，像被叼着
        net.minecraft.client.Minecraft.getInstance().getItemModelResolver().updateForLiving(
                ((DogHeldBallAccess) state).rotationCraft$getDogHeldBall(),
                held, ItemDisplayContext.GROUND, entity);
    }
}

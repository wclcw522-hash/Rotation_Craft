package com.mcmodworkspace.rotationcraft.client;




import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.animal.wolf.WolfModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.WolfRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * <b>把狗嘴里叼着的球画出来</b>（作者 2026-11-19：「用掉落物建模叼在嘴里」）。
 *
 * <h2>结构照抄原版 {@code FoxHeldItemLayer}</h2>
 * <p>狐狸叼物品的层级是：{@code FoxRenderer.addLayer(new FoxHeldItemLayer(this))}，
 * 层里从 {@code state.heldItem}（{@code ItemStackRenderState}）取渲染状态，
 * 然后 <b>把 PoseStack 挪到 head 的位置</b>、套上 head 的旋转、再做一次
 * {@code X +90°}（让物品「平躺」而不是立着），最后 {@code item.submit(...)}。</p>
 *
 * <p>这里唯一不同的是数据来源：狐狸读 {@code state.heldItem}（主手），
 * 我们读 {@link WolfRenderStateMixin#rotationCraft$dogHeldBall}（模组附件里的球）。</p>
 *
 * <h2>偏移量</h2>
 * <p>狗的头比狐狸大一圈，所以嘴里那点位置不能直接照搬狐狸的
 * {@code (0.06, 0.27, -0.5)}。这里取 {@code (0.0, 0.22, -0.55)} ——
 * 沿 head 本地坐标系往「前下方」一点，落在吻部。要微调就改这三个数。</p>
 */
public class DogHeldBallLayer extends RenderLayer<WolfRenderState, WolfModel> {

    /** 嘴里那个位置（head 本地坐标，单位是格） */
    private static final float MOUTH_X = 0.0F;
    private static final float MOUTH_Y = 0.22F;
    private static final float MOUTH_Z = -0.55F;

    /** 幼年狗的缩放 —— 原版狐狸那边也是 0.75 */
    private static final float BABY_SCALE = 0.75F;

    public DogHeldBallLayer(RenderLayerParent<WolfRenderState, WolfModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                       WolfRenderState state, float yRot, float xRot) {
        ItemStackRenderState held = ((DogHeldBallAccess) state).rotationCraft$getDogHeldBall();
        if (held == null || held.isEmpty()) {
            return;
        }
        var head = ((WolfHeadAccess) this.getParentModel()).rotationCraft$getHead();

        poseStack.pushPose();
        // ① 先挪到 head 的模型原点（模型单位是 1/16 格）
        poseStack.translate(head.x / 16.0F, head.y / 16.0F, head.z / 16.0F);
        // ② 幼年狗整体缩一圈
        if (state.isBaby) {
            poseStack.scale(BABY_SCALE, BABY_SCALE, BABY_SCALE);
        }
        // ③ 套上 head 的三个旋转，与狐狸那边完全同序
        poseStack.mulPose(Axis.ZP.rotation(state.headRollAngle));
        poseStack.mulPose(Axis.YP.rotationDegrees(yRot));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRot));
        // ④ 挪到嘴部
        poseStack.translate(MOUTH_X, MOUTH_Y, MOUTH_Z);
        // ⑤ 物品「平躺」：狐狸那边也是这一下 X+90°
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));

        held.submit(poseStack, collector, lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }
}

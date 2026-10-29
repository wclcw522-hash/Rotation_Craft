package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallBehavior;
import com.mcmodworkspace.moreballs.entity.BallProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.mojang.math.Axis;

/**
 * 球的投射物渲染器 —— 在 {@link ThrownItemRenderer} 基础上支持<b>按球缩放</b>。
 *
 * <p>原版那个渲染器的缩放是构造参数（写死的），而我们的球共用一个实体类型、
 * 却需要不同大小（金球是「一格内切球」，比普通球大得多）。26.x 又改成了状态分离管线，
 * 所以这里走三步：自己的渲染状态 → 抽状态时把尺寸记进去 → 提交时按尺寸缩放。</p>
 *
 * <p>普通球的倍率是 1.0，走的是和以前完全一样的路径。</p>
 */
public class BallRenderer extends ThrownItemRenderer<BallProjectile> {
    /** 环绕层用全亮 —— 它在水下，不该被头顶的方块阴影压暗 */
    private static final int LIGHT_FULL = 0x00F000F0;

    /** 视觉滚动（作者 2026-10-29）—— 按位移反算旋转，见 {@link BallRollVisual} */
    private final BallRollVisual rollVisual = new BallRollVisual();

    /** 【潮涌】的环绕模型 —— 层定义直接用原版注册好的那个，懒 bake 一次 */
    private TidalSurgeModel surgeModel;

    public BallRenderer(EntityRendererProvider.Context context) {
        super(context, 1.0F, true);
    }

    private TidalSurgeModel surgeModel() {
        if (this.surgeModel == null) {
            this.surgeModel = new TidalSurgeModel(
                    net.minecraft.client.Minecraft.getInstance().getEntityModels()
                            .bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER_SPIN_ATTACK));
        }
        return this.surgeModel;
    }

    @Override
    public ThrownItemRenderState createRenderState() {
        return new BallRenderState();
    }

    @Override
    public void extractRenderState(BallProjectile entity, ThrownItemRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        if (state instanceof BallRenderState ballState) {
            ballState.ballScale = BallBehavior.profileFor(entity.getItem()).entityScale();
            // 这个球有没有 3D 模型（作者 2026-10-29）。null = 退回原版 2D 物品图标路径。
            ballState.ball3dKey = Ball3DModels.keyOf(entity);
            // 视觉滚动：旋转中心和半径都直接取碰撞箱的几何量
            //   中心 = 碰撞箱高 / 2（作者要求「滚动中心和碰撞箱中心重合」）
            //   半径 = 碰撞箱宽 / 2（用于 Δθ = Δs / r）
            ballState.rollPivotY = (float) (entity.getBbHeight() * 0.5D);
            ballState.rollRadius = (float) (entity.getBbWidth() * 0.5D);
            this.rollVisual.update(entity, ballState.rollRadius, ballState.rollOrientation);
            ballState.tidalSurge = entity.isTidalSurge();
            ballState.surgeTicks = entity.tickCount;
            // 【潮涌】环绕要朝向**速度方向**（作者 2026-10-13 第三轮反馈：
            // 原来的朝向被锁死了）。球自己的渲染不按速度转（ThrownItemRenderer 只跟着相机），
            // 所以这里把速度方向换算成 yaw/pitch 交给环绕层用。
            Vec3 velocity = entity.getDeltaMovement();
            if (velocity.lengthSqr() > 1.0E-6D) {
                ballState.surgeYaw = (float) (Mth.atan2(velocity.x, velocity.z) * 180.0D / Math.PI);
                ballState.surgePitch = (float) (Mth.atan2(velocity.y,
                        Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z)) * 180.0D / Math.PI);
            }
        }
    }

    @Override
    public void submit(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        // ===== 3D 模型由**物品模型定义**接管，这里只负责缩放和潮涌环绕 =====
        //
        // 演变过程（作者 2026-10-29 两轮反馈）：
        //   ① 一开始走 NeoForge standalone 模型 + submitBlockModel，
        //      结果球「黑漆漆的看不出来是啥球」—— 那条路拿不到正确的光照上下文。
        //   ② 现在改成：`items/<key>_ball.json` 按 `display_context` 分流，
        //      `ground`（正是 ThrownItemRenderer 用的上下文）指向 3D 模型，
        //      其余上下文保持 2D 图标。
        //
        // 好处：
        //   · 光照 / 遮罩 / 附魔光效全部由原版 `ItemStackRenderState` 接管，不可能偏暗
        //   · 锤头（FlailHeadEntity）的 getItem() 返回内嵌球 ⇒ 自动也是 3D，贴图和球一致
        //   · 物品栏仍然是 2D 图标，因为 gui 上下文没被 cases 覆盖 → 走 fallback
        float scale = (state instanceof BallRenderState ballState) ? ballState.ballScale : 1.0F;

        // 视觉滚动：先套上累积旋转（绕球心），再画模型 / 画潮涌
        poseStack.pushPose();
        if (state instanceof BallRenderState rollState) {
            this.rollVisual.applyRoll(rollState.rollOrientation, rollState.rollPivotY, poseStack);
        }

        if (scale == 1.0F) {
            super.submit(state, poseStack, collector, cameraState);
        } else {
            poseStack.pushPose();
            // 放大是围绕实体原点（脚下）做的，所以往上抬一点，免得大球有半个身子陷进地面
            poseStack.translate(0.0F, (scale - 1.0F) * 0.125F, 0.0F);
            poseStack.scale(scale, scale, scale);
            super.submit(state, poseStack, collector, cameraState);
            poseStack.popPose();
        }
        this.submitSurge(state, poseStack, collector, LIGHT_FULL);
        poseStack.popPose();
    }

    /** 【潮涌】的环绕水纹 —— 球在水里时套在外面那一圈（几何与纹理都来自原版激流） */
    private void submitSurge(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                             int lightCoords) {
        if (!(state instanceof BallRenderState ballState) || !ballState.tidalSurge) {
            return;
        }
        TidalSurgeModel model = this.surgeModel();
        model.setupAnim(ballState);
        // 【潮涌】环绕的朝向：原版那圈水纹「绕的是玩家的身体轴」，玩家开激流时人是躺着的，
        // 身体轴自然就对准了视线（= 速度）方向 —— 所以原版看着是对的。
        // 球没有这套：ThrownItemRenderer 只按相机转，球的朝向跟速度无关，
        // 于是环绕的轴被**锁死在竖直**（作者 2026-10-13 第三轮反馈：「方向被锁定了」）。
        //
        // 这里显式把模型转成「对齐速度方向」：先按 yaw 绕竖轴、再按 pitch 把
        // 「原本朝上的那一面」压到速度方向上。
        //
        // ⚠️ 上一版我误判成「太大」，加了缩放和 Y 偏移 —— 作者指出那不是问题，已撤掉，
        //    现在只做旋转，尺寸保持原样。
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(ballState.surgeYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F - ballState.surgePitch));
        collector.submitModel(model, ballState, poseStack, TidalSurgeModel.TEXTURE,
                lightCoords, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0, null);
        poseStack.popPose();
    }
}

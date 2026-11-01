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

    private static boolean DIAGNOSED = false;

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
            // ===== 组合球：四象限材质 key（作者 2026-11-01 的 3D 组合方案）=====
            //
            //  组合球没有单一材质，以前是「四张 2D 贴图先合成一张再贴一个面」，
            //  颜色组合看着很怪。现在改成「四块真几何拼起来」，所以这里要把
            //  每个象限的来源球映射成 Ball3DModels 的材质 key，交给 submit 分别提交。
            ballState.comboQuadrantKeys = comboQuadrantKeys(entity.getItem());
            // 视觉滚动：中心/半径取**模型几何**的尺寸，不是碰撞箱。
            //
            // 作者 2026-10-29 重绘模型后，球直径是 0.5 格（8 单位），
            // 而碰撞箱仍是 0.25 格 —— 两者不再相等。旋转必须绕**模型几何中心**，
            // 否则球会绕自己下方 0.125 格那个点甩，看起来就是偏心。
            //
            //   模型 y: 0~8 单位 ⇒ 几何中心 y = 4 单位 = 0.25 格
            //   模型直径 8 单位 ⇒ 滚动半径 = 4 单位 = 0.25 格
            ballState.rollPivotY = Ball3DModels.MODEL_HALF_HEIGHT;
            ballState.rollRadius = Ball3DModels.MODEL_HALF_WIDTH;
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
        float scale = (state instanceof BallRenderState ballState) ? ballState.ballScale : 1.0F;
        String key = (state instanceof BallRenderState ballState) ? ballState.ball3dKey : null;

        // ===== 组合球：四象限 3D 组合渲染（作者 2026-11-01）=====
        //
        // 作者要的是「先渲染各部分完整的切球建模，再组合」—— 不再是「先把四张 2D 贴图
        // 合成一张再贴一个面」（那样颜色组合看着很怪）。四份几何都是脚本从整球按
        // x=0 / y=4 裁出来的真角，拼回去就是整颗球；作者明确接受「有一边只能看到
        // 比较长的切球侧面」。
        //
        // ⚠️ 必须放在 `part == null` 那条 2D 兜底之前 —— 组合球的 ball3dKey 本来就是 null
        //    （`combo_ball` 去掉 `_ball` 得到 `combo`，不在 KEYS 里），否则会掉进 2D。
        if (state instanceof BallRenderState comboState && comboState.comboQuadrantKeys != null) {
            poseStack.pushPose();
            // 水纹先画（不受滚动影响，见 submitSurge 的说明）
            this.submitSurge(state, poseStack, collector, LIGHT_FULL);
            // 滚动姿态：四块几何一起转，拼出来的整球就像在滚
            this.rollVisual.applyRoll(comboState.rollOrientation, comboState.rollPivotY, poseStack);
            int comboLight = state.lightCoords == 0 ? LIGHT_FULL : state.lightCoords;
            for (int q = 1; q <= 4; q++) {
                String qKey = comboState.comboQuadrantKeys[q - 1];
                if (qKey == null) {
                    continue;                       // 该象限是空的（二合一球的下半）
                }
                BlockStateModelPart quadrant = Ball3DModels.quadrantPart(qKey, q);
                if (quadrant == null) {
                    continue;                       // 模型还没烘焙好，跳过这一块
                }
                collector.submitBlockModel(
                        poseStack,
                        Ball3DModels.quadrantRenderType(),
                        java.util.List.of(quadrant),
                        new int[] { -1 },
                        comboLight,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                        0);
            }
            poseStack.popPose();
            return;
        }

        BlockStateModelPart part = (key == null) ? null : Ball3DModels.part(key);

        // 一次性自检（作者 2026-10-30：球「也没有转动」——需要先确认 3D 到底生效没有）
        if (!DIAGNOSED) {
            DIAGNOSED = true;
            String diag = "?";
            if (state instanceof BallRenderState rs) {
                // 姿态用四元数存；w < 1 说明已经转过（identity 时 w 恰好 = 1）
                diag = String.format("w=%.4f pivot=%.4f radius=%.4f",
                        rs.rollOrientation.w, rs.rollPivotY, rs.rollRadius);
            }
            com.mcmodworkspace.moreballs.MoreBalls.LOGGER.info(
                    "[渲染自检] 球 key={} part={} 姿态 {}",
                    key,
                    part == null ? "null（走 2D）" : part.getClass().getSimpleName(),
                    diag);
        }

        // ===== 没有 3D 模型（组合球以外的情况 / 模型还没烘焙好）：走原版 2D 物品图标 =====
        if (part == null) {
            if (scale == 1.0F) {
                super.submit(state, poseStack, collector, cameraState);
            } else {
                poseStack.pushPose();
                poseStack.translate(0.0F, (scale - 1.0F) * 0.125F, 0.0F);
                poseStack.scale(scale, scale, scale);
                super.submit(state, poseStack, collector, cameraState);
                poseStack.popPose();
            }
            this.submitSurge(state, poseStack, collector, LIGHT_FULL);
            return;
        }

        // ===== 3D 模型 =====
        //
        // 坐标链只有一层：模型 json 里写的就是世界坐标（x/z -2~2、y 0~4 单位 = 0~0.25 格），
        // 几何中心正好在 y = 2 单位 = 0.125 格 = 碰撞箱中心。
        // 不走 ItemTransform、没有 -0.5 格的居中夹层 —— 位置和旋转中心都不会再漂。
        poseStack.pushPose();

        // 【潮涌】环绕水纹 —— **必须在施加滚动姿态之前画**（作者 2026-11-01：
        // 「潮汐球的水下特效会跟着球一起滚动」）。
        //
        //   水纹是「球外面那一圈激流」，它应该始终对齐**速度方向**、不随球的滚动翻面。
        //   原先这里把它放在 applyRoll 之后 ⇒ 球滚多少度、水纹就被转多少度，
        //   看起来像是水纹长在球皮上一起翻。挪到滚动姿势之前即可。
        this.submitSurge(state, poseStack, collector, LIGHT_FULL);

        // 视觉滚动：绕碰撞箱中心转
        if (state instanceof BallRenderState rollState) {
            this.rollVisual.applyRoll(rollState.rollOrientation, rollState.rollPivotY, poseStack);
        }

        if (scale != 1.0F) {
            poseStack.pushPose();
            poseStack.translate(0.0F, (scale - 1.0F) * 0.125F, 0.0F);
            poseStack.scale(scale, scale, scale);
        }
        // 光照兜底：state.lightCoords 在某些时机是 0（比如刚生成、区块光照还没算好），
        // 传 0 进去整颗球会全黑。0 时退回全亮，宁可亮一点也不要点不着。
        int light = state.lightCoords == 0 ? LIGHT_FULL : state.lightCoords;
        collector.submitBlockModel(
                poseStack,
                Ball3DModels.renderType(key),
                java.util.List.of(part),
                new int[] { -1 },                       // tintLayers：-1 = 不染色（null 会被当成 0 = 黑）
                light,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                0);
        if (scale != 1.0F) {
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /**
     * 组合球的四象限材质 key（下标 0..3 = 象限 1..4）。
     *
     * <p>不是组合球、或者四个象限都读不出材质时返回 {@code null}（走普通球那条路）。</p>
     */
    private static String[] comboQuadrantKeys(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()
                || stack.getItem() != com.mcmodworkspace.moreballs.ModItems.COMBO_BALL.get()) {
            return null;
        }
        java.util.List<net.minecraft.world.item.Item> sources =
                com.mcmodworkspace.moreballs.BallFragments.sources();
        String[] keys = new String[4];
        boolean any = false;
        for (int q = 0; q < 4; q++) {
            Integer src = stack.get(com.mcmodworkspace.moreballs.ModComponents.COMBO_SLOTS.get(q).get());
            if (src == null || src < 0 || src >= sources.size()) {
                continue;
            }
            // 用「那颗来源球的物品栈」问 keyOf，顺序与 Ball3DModels.KEYS 天然对齐
            String k = Ball3DModels.keyOf(new net.minecraft.world.item.ItemStack(sources.get(src)));
            if (k != null) {
                keys[q] = k;
                any = true;
            }
        }
        return any ? keys : null;
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

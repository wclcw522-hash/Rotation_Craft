package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.entity.FlailHeadEntity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * <b>流星锤锤头的渲染器</b> —— 锤头本体 + 那根连向掷出者的线（作者 2026-10-19 规格）。
 *
 * <h2>为什么锤头本体一行代码都不用写</h2>
 * <p>父类 {@link ThrownItemRenderer} 会按实体携带的物品栈渲染，而锤头携带的就是
 * 「那把流星锤」—— 流星锤的物品模型会按内嵌球选图标，所以世界里看起来
 * 就是「那颗球 + 绳子」。这里只在它上面**多画一根线**。</p>
 *
 * <h2>线的画法（26.2 的渲染管线）</h2>
 * <p>26.2 换成了「状态抽取 → 提交」三分段，老的 {@code VertexConsumer} 直连没了。画自定义几何要用</p>
 * <pre>
 * collector.submitLeash(poseStack, leashState);   // 走原版拴绳，不再手画直线
 * </pre>
 * <p>这是原版 {@code GuardianRenderer}（守卫者光束）的写法，不是我自己拼的。</p>
 *
 * <p>⚠️ 线的两个端点都在 {@link #extractRenderState} 里**预先算成「相对锤头」的坐标**，
 * 因为 {@code submit} 阶段拿不到实体（只有状态对象）。</p>
 */
public class FlailHeadRenderer extends ThrownItemRenderer<FlailHeadEntity> {

    /** 光照兜底：`state.lightCoords` 为 0 时用全亮，避免整颗锤头全黑 */
    private static final int LIGHT_FULL = 0x00F000F0;

    public FlailHeadRenderer(EntityRendererProvider.Context context) {
        // 缩放参数用 1.0：锤头的「球」大小由物品图标本身决定，
        // 与球的实体缩放（金球那套 entityScale）无关。
        super(context, 1.0F, true);
    }

    @Override
    public ThrownItemRenderState createRenderState() {
        return new FlailHeadRenderState();
    }

    /**
     * 抽取状态：把「线该画到哪」算成相对锤头的偏移。
     *
     * <p>线的另一端 = 掷出者的<b>主手位置</b>（作者规格：「一根时刻连接锤头与主手的白色线」）。
     * 用主手而不是眼睛：手是玩家能看见的起点，眼睛在屏幕外，线会「从屏幕里冒出来」。</p>
     */
    @Override
    public void extractRenderState(FlailHeadEntity entity, ThrownItemRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        if (!(state instanceof FlailHeadRenderState flailState)) {
            return;
        }

        // 锤头的外观 = 内嵌那颗球的 3D 模型（2026-10-29）。
        flailState.ball3dKey = Ball3DModels.keyOf(entity.getItem());

        Entity owner = entity.getOwner();
        if (owner == null) {
            flailState.hasLine = false;
            return;
        }
        flailState.hasLine = true;

        // 主手的世界坐标（按 partialTick 插值，避免线在高速移动时抖动）
        double ox = owner.xo + (owner.getX() - owner.xo) * partialTick;
        double oy = owner.yo + (owner.getY() - owner.yo) * partialTick
                + owner.getBbHeight() * 0.6D;              // 抬到胸口高度，别从脚下冒出来
        double oz = owner.zo + (owner.getZ() - owner.zo) * partialTick;

        // 拴绳的两端都用**相对锤头渲染原点**的坐标（submit 阶段 poseStack 已以锤头为原点）
        Vec3 fromHead = new Vec3(ox, oy, oz).subtract(entity.position());
        flailState.anchorX = (float) fromHead.x;
        flailState.anchorY = (float) fromHead.y;
        flailState.anchorZ = (float) fromHead.z;
    }

    /**
     * 提交：先让父类画锤头本体，再叠一根白色的线。
     */
    @Override
    public void submit(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        // ⚠️ 不要在这里加 poseStack.scale（作者 2026-10-23 报「锤头跟金球一样大」）。
        //
        //    我上一版为了让 0.2 的碰撞箱看起来正常，在这里乘了 2.5 倍 ——
        //    而球自己的碰撞箱是 0.25，锤头 0.2×2.5 = 0.5，**比球整整大一倍**。
        //    `ThrownItemRenderer` 本来就是按实体尺寸画物品模型的，
        //    保持 1:1 就对了：锤头 0.2 比球 0.25 略小一点，观感正好。
        // ===== 锤头也用 3D 模型（作者 2026-10-29：「锤头又又又变成旧版了」）=====
        //
        // 之前退回 2D 是因为「回收后锤头消失」怀疑是 3D 分支引入的 —— 但逻辑侧
        // （FlailCastHandler / shatter / finishReturn / returnFlailToOwner / isCasting）
        // 逐条查过都没问题，且 2D 版同样报这个现象，所以不是 3D 的锅。
        //
        // 这里做成「3D 优先、失败退回 2D」：
        //   · part != null → 画 3D（和球实体同一个模型，贴图一致）
        //   · part == null（模型没烘焙好 / 找不到 key）→ 走 super.submit 的 2D 图标，
        //     绝不整帧不画 —— 那才会真的"消失"
        String key = (state instanceof FlailHeadRenderState flailState) ? flailState.ball3dKey : null;
        BlockStateModelPart part = (key == null) ? null : Ball3DModels.part(key);

        if (part != null) {
            poseStack.pushPose();
            // 球模型是 0.5 格，锤头碰撞箱是 0.2 格 ⇒ 缩到 0.4 保持原尺寸
            final float shrink = 0.4F;
            poseStack.scale(shrink, shrink, shrink);
            int light = state.lightCoords == 0 ? LIGHT_FULL : state.lightCoords;
            collector.submitBlockModel(
                    poseStack,
                    Ball3DModels.renderType(key),
                    java.util.List.of(part),
                    new int[] { -1 },
                    light,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    0);
            poseStack.popPose();
        } else {
            super.submit(state, poseStack, collector, cameraState);
        }

        if (!(state instanceof FlailHeadRenderState flailState) || !flailState.hasLine) {
            return;
        }

        // ===== 用**原版拴绳**渲染，而不是自己画直线 =====
        //
        // 作者 2026-10-19：「线太奇怪了，应该调用拴绳的代码连接掷出者和锤头」。
        // 拴绳自带松弛下垂、纹理、光照，比自己手画的 1px 线自然得多。
        //
        // `OrderedSubmitNodeCollector.submitLeash(PoseStack, EntityRenderState.LeashState)`
        // 的 LeashState 有两个关键点：
        //   start / end —— **相对实体渲染原点**的坐标（poseStack 已在此原点）
        //   slack       —— 是否允许下垂（拴绳的松弛效果）
        // ⚠️ 物品栈空了就别画绳（作者 2026-10-19 报「锤头消失只剩绳」）：
        //    回收完成那一瞬 `discard()` 已下发，但客户端这一帧还可能留着实体壳，
        //    此时 getItem() 为空 ⇒ 锤头本体不渲染、绳却照画。加这一道闸。
        if (flailState.item.isEmpty()) {   // ItemStackRenderState.isEmpty()
            return;
        }

        var leash = new net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState();
        leash.start = Vec3.ZERO;                                  // 锤头这端
        leash.end = new Vec3(flailState.anchorX, flailState.anchorY, flailState.anchorZ);
        leash.offset = Vec3.ZERO;
        leash.slack = true;
        collector.submitLeash(poseStack, leash);
    }


}

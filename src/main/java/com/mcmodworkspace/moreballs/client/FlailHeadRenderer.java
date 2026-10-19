package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.entity.FlailHeadEntity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
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
 * collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -&gt; { ... });
 * </pre>
 * <p>这是原版 {@code GuardianRenderer}（守卫者光束）的写法，不是我自己拼的。</p>
 *
 * <p>⚠️ 线的两个端点都在 {@link #extractRenderState} 里**预先算成「相对锤头」的坐标**，
 * 因为 {@code submit} 阶段拿不到实体（只有状态对象）。</p>
 */
public class FlailHeadRenderer extends ThrownItemRenderer<FlailHeadEntity> {

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

        Vec3 fromHead = new Vec3(ox, oy, oz).subtract(entity.position());
        flailState.lineX = (float) fromHead.x;
        flailState.lineY = (float) fromHead.y;
        flailState.lineZ = (float) fromHead.z;
    }

    /**
     * 提交：先让父类画锤头本体，再叠一根白色的线。
     */
    @Override
    public void submit(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        super.submit(state, poseStack, collector, cameraState);

        if (!(state instanceof FlailHeadRenderState flailState) || !flailState.hasLine) {
            return;
        }

        // RenderTypes.lines() 是原版给「线段」准备的 RenderType（LINES）。
        // 每个线段要成对提交顶点：addVertex(起点) → addVertex(终点)。
        collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
            vertex(buffer, pose, 0.0F, 0.0F, 0.0F);                                  // 锤头这一点
            vertex(buffer, pose, flailState.lineX, flailState.lineY, flailState.lineZ); // 主手那一点
        });
    }

    /**
     * 提交一个顶点 —— 照抄原版 {@code GuardianRenderer.vertex} 的字段顺序。
     *
     * <p>白色：作者规格「一根时刻连接锤头与主手的<b>白色</b>线」。
     * {@code lines()} 不带光照，所以颜色直接就是最终颜色，不会被环境压暗。</p>
     */
    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z) {
        // ⚠️⚠️ **setUv 不能少**（作者 2026-10-19 报「按住右键立马崩溃」）。
        //    漏了它，顶点就是「缺元素」的 —— 运行期直接抛
        //    `IllegalStateException: Missing elements in vertex`，而且是在渲染线程里，
        //    表现为「一按右键就崩」。原版 GuardianRenderer.vertex 是有 .setUv(u, v) 的。
        buffer.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(0.0F, 0.0F)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}

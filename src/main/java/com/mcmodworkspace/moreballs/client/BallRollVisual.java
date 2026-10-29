package com.mcmodworkspace.moreballs.client;

import java.util.HashMap;
import java.util.Map;

import com.mcmodworkspace.moreballs.entity.BallProjectile;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * <b>球的视觉滚动</b>（作者 2026-10-29：「我想给球添加上物理滚动效果」）。
 *
 * <h2>为什么视觉旋转要单独做</h2>
 * <p>滚动阻力早就有了 —— {@link com.mcmodworkspace.moreballs.BallRoll} 里那套
 * 「按还能弹多高决定何时转滚 + μN 摩擦公式」一直在跑。但那只是<b>运动</b>上的滚：
 * 球的位置沿着地面滑，外观<b>一点都不转</b>，看起来就是在冰上平移的方块。</p>
 *
 * <p>物理上滚动和滑动的区别只有一个：接触点速度为零 ⇒
 * <b>球心前进的距离 = 表面转过的弧长</b>：</p>
 *
 * <pre>
 *   Δθ = |Δpos| / r          （r = 球半径 0.125 格）
 *   轴  = 前进方向 × 上方向 = (dz, 0, -dx)
 * </pre>
 *
 * <h2>⚠️ 必须用四元数逐帧累积，不能「累积角度 + 换轴」</h2>
 * <p>第一版是这么写的：</p>
 * <pre>
 *   acc[0] += delta;           // 角度一直加
 *   acc[1] = ax; acc[2] = az;  // 轴每帧换成最新的
 * </pre>
 * <p>球<b>直线滚动</b>时看不出问题，一旦转向就彻底乱 —— 因为「之前那些旋转」是按旧轴做的，
 * 却被当成绕新轴转过了一个总角度。作者 2026-10-29 反馈「轴乱到天上去了」就是这个。</p>
 *
 * <p>正确做法：每帧只施加**这一帧的增量**，左乘到累积四元数上。
 * 旋转的复合不满足交换律，只有这样才能正确保持姿态。</p>
 *
 * <h2>为什么在渲染器里算而不是在实体里算</h2>
 * <ul>
 *   <li>纯视觉，不影响任何判定 —— 放渲染层最省事，也不用同步包</li>
 *   <li>复位 / 资源重载时天然归零，不会出现「球不动了还在转」</li>
 * </ul>
 * <p>代价是要记住上一帧位置。{@code extractRenderState} 每帧都会调、且拿不到上一次的
 * 渲染状态，所以按实体 id 记在渲染器自己的表里。</p>
 */
final class BallRollVisual {

    /** 球半径（格）—— 碰撞箱 0.25，半径取一半 */
    static final float RADIUS = 0.125F;

    /** 一帧位移小于这个值就当没动（避免静止时抖动累积成缓慢自转） */
    private static final double MOVE_EPSILON = 1.0E-4D;

    /** 按实体 id 记住上一次的位置 */
    private final Map<Integer, Vec3> lastPositions = new HashMap<>();

    /** 按实体 id 累积姿态（四元数） */
    private final Map<Integer, Quaternionf> orientations = new HashMap<>();

    /** 复用对象，避免每帧 new */
    private final Quaternionf scratch = new Quaternionf();

    /**
     * 每帧更新一次累积姿态，结果写进 {@code out}。
     */
    void update(BallProjectile entity, Quaternionf out) {
        int id = entity.getId();
        Vec3 now = entity.position();
        Vec3 last = this.lastPositions.put(id, now);

        Quaternionf acc = this.orientations.computeIfAbsent(id, k -> new Quaternionf());
        out.set(acc);

        if (last == null) {
            return;
        }

        double dx = now.x - last.x;
        double dz = now.z - last.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < MOVE_EPSILON) {
            // 没在水平移动：垂直落体不该让球旋转（自由落体不产生滚动）
            return;
        }

        // 滚动轴 = 前进方向 × 上方向 = (dx,0,dz) × (0,1,0) = (dz, 0, -dx)
        // 归一化后配正角速度，正好是「球朝前进方向翻滚」
        float ax = (float) dz;
        float az = (float) -dx;
        float len = (float) Math.sqrt(ax * ax + az * az);
        ax /= len;
        az /= len;

        float delta = (float) (horizontal / RADIUS);   // 这一帧转过的弧角

        // 关键：只把「这一帧的增量」左乘上去，而不是替换轴
        this.scratch.rotationAxis(delta, ax, 0.0F, az);
        acc.premul(this.scratch);
        acc.normalize();        // 长时间累积会漂，归一化压回去
        out.set(acc);
    }

    /**
     * 把累积姿态叠到 PoseStack 上。
     *
     * <p>⚠️ 旋转必须绕<b>球心</b>做，而实体的渲染原点在<b>脚下</b> —— 所以先抬到球心、
     * 转、再落回去。少了这两下，球会绕着脚底打转（像被钉在地上转的风车）。</p>
     */
    void applyRoll(Quaternionf orientation, PoseStack poseStack) {
        poseStack.translate(0.0F, RADIUS, 0.0F);
        poseStack.mulPose(orientation);
        poseStack.translate(0.0F, -RADIUS, 0.0F);
    }

    /** 实体没了就清掉，别让表无限长 */
    void forget(int entityId) {
        this.lastPositions.remove(entityId);
        this.orientations.remove(entityId);
    }
}

package com.mcmodworkspace.moreballs.client;

import java.util.HashMap;
import java.util.Map;

import com.mcmodworkspace.moreballs.entity.BallProjectile;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
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
 * <b>球心前进的距离 = 表面转过的弧长</b>。所以角速度不用自己编，直接由位移反算：</p>
 *
 * <pre>
 *   Δθ = |Δpos| / r          （r = 球半径）
 * </pre>
 *
 * <p>轴取「水平前进方向 × 上方向」，也就是让球朝前进方向翻滚。</p>
 *
 * <h2>为什么在渲染器里算而不是在实体里算</h2>
 * <ul>
 *   <li>纯视觉，不影响任何判定 —— 放渲染层最省事，也不用同步包</li>
 *   <li>复位/重载时天然归零，不会出现「球不动了还在转」</li>
 * </ul>
 * <p>代价是要记住上一帧位置。{@code extractRenderState} 每帧都会调、且拿不到上一次的
 * 渲染状态，所以按实体 id 记在渲染器自己的表里。</p>
 */
final class BallRollVisual {

    /** 球半径（格）—— 碰撞箱 0.25，半径取一半 */
    static final float RADIUS = 0.125F;

    /** 一帧位移小于这个值就当没动（避免静止时的抖动累积成缓慢自转） */
    private static final double MOVE_EPSILON = 1.0E-4D;

    /** 累积角超过 2π 就绕回来，防止长时间滚动让 float 精度掉光 */
    private static final float TWO_PI = (float) (Math.PI * 2.0D);

    /** 按实体 id 记住上一次的位置 */
    private final Map<Integer, Vec3> lastPositions = new HashMap<>();

    /** 按实体 id 累积当前旋转：{角度, 轴x, 轴z} */
    private final Map<Integer, float[]> angles = new HashMap<>();

    /**
     * 每个渲染帧更新一次累积角度。
     *
     * @return 该实体当前的 {角度(rad), 轴x, 轴z}；还没动过就返回 null
     */
    float[] update(BallProjectile entity) {
        int id = entity.getId();
        Vec3 now = entity.position();
        Vec3 last = this.lastPositions.put(id, now);

        float[] acc = this.angles.get(id);
        if (acc == null) {
            acc = new float[] { 0.0F, 0.0F, 1.0F };
            this.angles.put(id, acc);
        }
        if (last == null) {
            return acc;
        }

        double dx = now.x - last.x;
        double dz = now.z - last.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < MOVE_EPSILON) {
            // 没在水平移动：垂直落体不该让球旋转（自由落体不产生滚动）
            return acc;
        }

        // 滚动轴 = 前进方向 × 上方向 = (dx,0,dz) × (0,1,0) = (-dz, 0, dx)
        // 这样正向角速度对应的就是「球朝前进方向翻滚」。
        //
        // ⚠️ 这里我一开始把两个方向搞反了（写成 dz, 0, -dx），结果是球**倒着滚**：
        //    位置往前、表面往后转，视觉上像在打滑，作者 2026-10-29 反馈「旋转中心偏了」
        //    —— 其实中心是对的，是转向反了造成的错觉。
        float ax = (float) dz;
        float az = (float) -dx;
        float len = (float) Math.sqrt(ax * ax + az * az);
        ax /= len;
        az /= len;

        float delta = (float) (horizontal / RADIUS);
        acc[0] += delta;
        if (acc[0] > TWO_PI) {
            acc[0] -= TWO_PI * (float) Math.floor(acc[0] / TWO_PI);
        }
        acc[1] = ax;
        acc[2] = az;
        return acc;
    }

    /**
     * 把累积旋转叠到 PoseStack 上。
     *
     * <p>⚠️ 旋转必须绕**球心**做，而实体的渲染原点在**脚下** —— 所以先抬到球心、
     * 转、再落回去。少了这两下，球会绕着脚底打转（像被钉在地上转的风车）。</p>
     */
    void applyRoll(float angle, float axisX, float axisZ, PoseStack poseStack) {
        if (Mth.abs(angle) < 1.0E-3F) {
            return;
        }
        poseStack.translate(0.0F, RADIUS, 0.0F);
        poseStack.mulPose(new Quaternionf().rotateAxis(angle, axisX, 0.0F, axisZ));
        poseStack.translate(0.0F, -RADIUS, 0.0F);
    }

    /** 实体没了就清掉，别让表无限长 */
    void forget(int entityId) {
        this.lastPositions.remove(entityId);
        this.angles.remove(entityId);
    }
}

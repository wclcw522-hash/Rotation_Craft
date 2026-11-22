package com.mcmodworkspace.rotationcraft.client;

import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;

/**
 * 球的渲染状态 —— 只为多带一个「这个球该画多大」。
 *
 * <p>26.x 的渲染管线改成了「状态分离」：实体先在 {@code extractRenderState} 里被读成一份
 * 纯数据快照，再交给 {@code submit} 画出来 —— {@code submit} 是拿不到实体的。
 * 所以球的尺寸倍率必须在这里跟着快照一起传下去。</p>
 */
public class BallRenderState extends ThrownItemRenderState {

    /**
     * 视觉滚动 —— 累积姿态（四元数），由 {@link BallRollVisual} 每帧更新。
     *
     * <p>为什么是四元数而不是「角度 + 轴」：球转向时滚动轴会变，
     * 而旋转的复合不满足交换律 —— 用「最新的轴 + 累积的角度」去还原姿态，
     * 一转向就彻底乱掉（作者 2026-10-29 报「轴乱到天上去了」）。</p>
     */
    public final org.joml.Quaternionf rollOrientation = new org.joml.Quaternionf();

    /**
     * 滚动旋转中心（格）—— **碰撞箱中心**，即 {@code 碰撞箱高度 / 2}。
     *
     * <p>作者 2026-10-29：「直接让滚动中心和碰撞箱中心重合」。所以这个值不写死，
     * 每次抽取渲染状态时按实体当前碰撞箱重算 —— 碰撞箱将来改了，旋转中心自动跟着改。</p>
     */
    public float rollPivotY = 0.125F;

    /** 滚动半径（格）= 碰撞箱宽 / 2，用于把位移换算成滚动角度 */
    public float rollRadius = 0.125F;

    /** 这个球的渲染尺寸倍率；1.0 就是普通球 */
    public float ballScale = 1.0F;

    /**
     * 这个球该用哪份 3D 模型（{@link Ball3DModels#KEYS} 里的 key）。
     *
     * <p>{@code null} 表示没有 3D 模型 —— 比如组合球，或者别处塞进来的非本模组物品。
     * 那时渲染器会退回原版 {@code ThrownItemRenderer} 的 2D 物品图标路径。</p>
     */
    public String ball3dKey = null;

    /**
     * <b>组合球的四象限材质 key</b>（作者 2026-11-01 的 3D 组合方案）——
     * 下标 0..3 对应象限 1..4（左上 / 右上 / 左下 / 右下），{@code null} 表示该象限空。
     *
     * <p>{@code null} 整个数组 = 这不是组合球（或读不出数据），走普通球那条路。</p>
     */
    public String[] comboQuadrantKeys = null;
    /** 【潮涌】是否激活 —— 球在水里时由服务端的同步位置位，决定要不要画那圈环绕水纹 */
    public boolean tidalSurge = false;

    /** 【潮涌】的旋转相位（用实体年龄，和原版激流同一种走法） */
    public float surgeTicks = 0.0F;

    /** 【潮涌】环绕要对齐的**速度方向** —— 偏航角（度） */
    public float surgeYaw = 0.0F;

    /** 【潮涌】环绕要对齐的**速度方向** —— 俯仰角（度） */
    public float surgePitch = 0.0F;
}

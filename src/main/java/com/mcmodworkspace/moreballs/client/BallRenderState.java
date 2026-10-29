package com.mcmodworkspace.moreballs.client;

import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;

/**
 * 球的渲染状态 —— 只为多带一个「这个球该画多大」。
 *
 * <p>26.x 的渲染管线改成了「状态分离」：实体先在 {@code extractRenderState} 里被读成一份
 * 纯数据快照，再交给 {@code submit} 画出来 —— {@code submit} 是拿不到实体的。
 * 所以球的尺寸倍率必须在这里跟着快照一起传下去。</p>
 */
public class BallRenderState extends ThrownItemRenderState {

    /** 这个球的渲染尺寸倍率；1.0 就是普通球 */
    public float ballScale = 1.0F;

    /**
     * 这个球该用哪份 3D 模型（{@link Ball3DModels#KEYS} 里的 key）。
     *
     * <p>{@code null} 表示没有 3D 模型 —— 比如组合球，或者别处塞进来的非本模组物品。
     * 那时渲染器会退回原版 {@code ThrownItemRenderer} 的 2D 物品图标路径。</p>
     */
    public String ball3dKey = null;
    /** 【潮涌】是否激活 —— 球在水里时由服务端的同步位置位，决定要不要画那圈环绕水纹 */
    public boolean tidalSurge = false;

    /** 【潮涌】的旋转相位（用实体年龄，和原版激流同一种走法） */
    public float surgeTicks = 0.0F;

    /** 【潮涌】环绕要对齐的**速度方向** —— 偏航角（度） */
    public float surgeYaw = 0.0F;

    /** 【潮涌】环绕要对齐的**速度方向** —— 俯仰角（度） */
    public float surgePitch = 0.0F;
}

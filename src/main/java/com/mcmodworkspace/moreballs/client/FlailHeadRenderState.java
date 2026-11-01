package com.mcmodworkspace.moreballs.client;

import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;

/**
 * 锤头渲染用的状态对象 —— 存「那根线该画到哪」。
 *
 * <p>为什么要单独一个状态类：26.2 的渲染是「状态抽取 → 提交」两段，
 * {@code submit} 阶段<b>拿不到实体</b>（只有状态对象）。所以线的终点必须在
 * {@link FlailHeadRenderer#extractRenderState} 里算好、存在这里。</p>
 *
 * <p>坐标是<b>相对锤头</b>的偏移，因为 {@code submit} 时 poseStack 已经以锤头为原点。</p>
 */
public class FlailHeadRenderState extends ThrownItemRenderState {

    /**
     * 锤头该用的 3D 模型 key（作者 2026-10-29）。
     *
     * <p>锤头渲染的是「内嵌那颗球」的外观，所以 key 是从 {@code entity.getItem()} 推的
     * —— {@code FlailHeadEntity} 覆写了 {@code getItem()} 让它返回内嵌球，正好对上。</p>
     *
     * <p>{@code null} 表示退回原版 2D 物品图标路径。</p>
     */
    public String ball3dKey = null;

    /** 是否该画线（掷出者不在了就不画） */
    public boolean hasLine;

    /** 【潮涌】是否激活 —— 锤头在水里时由服务端的同步位置位，决定要不要画那圈环绕水纹 */
    public boolean tidalSurge;

    /** 【潮涌】环绕要对齐的**速度方向** —— 偏航角（度） */
    public float surgeYaw;

    /** 【潮涌】环绕要对齐的**速度方向** —— 俯仰角（度） */
    public float surgePitch;

    /** 线另一端（掷出者主手）相对锤头的偏移 */
    public float anchorX;
    public float anchorY;
    public float anchorZ;
}

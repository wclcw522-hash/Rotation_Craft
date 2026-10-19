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

    /** 是否该画线（掷出者不在了就不画） */
    public boolean hasLine;

    /** 线另一端（掷出者主手）相对锤头的偏移 */
    public float lineX;
    public float lineY;
    public float lineZ;
}

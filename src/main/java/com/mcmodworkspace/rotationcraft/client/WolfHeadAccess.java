package com.mcmodworkspace.rotationcraft.client;

import net.minecraft.client.model.geom.ModelPart;

/**
 * 让外部（本模组的渲染层）能读到 {@code WolfModel.head}（作者 2026-11-19）。
 *
 * <h2>为什么是接口</h2>
 * <p>与 {@link DogHeldBallAccess} 同一个原因：<b>Mixin 类运行时不作为真实类型存在</b>，
 * 强转它会在启动时抛
 * {@code Resolution error: unable to find corresponding type ...}。
 * 所以由 {@code WolfModelMixin} 让 {@code WolfModel} 实现本接口，
 * 调用方强转成接口即可。</p>
 *
 * <h2>为什么需要</h2>
 * <p>{@code WolfModel.head} 是 {@code protected final}，而本模组的渲染层在另一个包，
 * 够不到它。原版 {@code FoxHeldItemLayer} 能直接写 {@code getParentModel().head}，
 * 是因为它自己就在 {@code net.minecraft.client.renderer.entity.layers} 包里。</p>
 */
public interface WolfHeadAccess {

    /** 狗的头部模型部件（用于把物品摆到嘴边） */
    ModelPart rotationCraft$getHead();
}

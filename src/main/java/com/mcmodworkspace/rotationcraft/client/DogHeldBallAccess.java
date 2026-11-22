package com.mcmodworkspace.rotationcraft.client;

import net.minecraft.client.renderer.item.ItemStackRenderState;

/**
 * 让「狗嘴里叼着的球」的渲染状态能被跨包读到（作者 2026-11-19）。
 *
 * <h2>为什么必须是接口，不能直接强转 Mixin 类</h2>
 * <p>⚠️ <b>Mixin 类在运行时不作为真实类型存在</b> —— 它只是编译期的「模板」，
 * 成员会被织入目标类（这里是 {@code WolfRenderState}）。所以这样写会在启动时炸：</p>
 *
 * <pre>
 * ((WolfRenderStateMixin) (Object) state).rotationCraft$dogHeldBall
 * → Resolution error: unable to find corresponding type for
 *   com/mcmodworkspace/rotationcraft/mixin/client/WolfRenderStateMixin
 *   in hierarchy of net/minecraft/client/renderer/entity/WolfRenderer
 * </pre>
 *
 * <p>正确姿势：**定义接口 → Mixin 让目标类 implements 它 → 调用方强转成接口**。
 * 运行时 {@code WolfRenderState} 真的实现了这个接口，转换是合法的。</p>
 */
public interface DogHeldBallAccess {

    /** 狗嘴里那件东西的渲染状态；没叼东西时是 empty state */
    ItemStackRenderState rotationCraft$getDogHeldBall();
}

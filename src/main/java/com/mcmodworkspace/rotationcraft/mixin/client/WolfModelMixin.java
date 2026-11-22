package com.mcmodworkspace.rotationcraft.mixin.client;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.animal.wolf.WolfModel;
import com.mcmodworkspace.rotationcraft.client.WolfHeadAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 把 {@link WolfModel#head} 暴露出来（作者 2026-11-19）。
 *
 * <h2>为什么要这一层</h2>
 * <p>{@code WolfModel.head} 是 {@code protected final}，而本模组的渲染层
 * {@code client.DogHeldBallLayer} 在 <b>另一个包</b>里，访问不到它。</p>
 *
 * <p>原版 {@code FoxHeldItemLayer} 能直接写 {@code getParentModel().head}，
 * 是因为它自己就在 {@code net.minecraft.client.renderer.entity.layers} 包里 ——
 * 我们不可能把类塞进 {@code net.minecraft.*} 去。</p>
 *
 * <p>可选的三条路里选了这条最保守的：</p>
 * <ul>
 *   <li><b>AccessTransformer</b> —— 要改 AT 配置，影响面比一个只读的 getter 大；</li>
 *   <li><b>反射</b> —— 本项目明令禁止；</li>
 *   <li><b>本 Mixin 加一个只读 getter</b> —— 不改任何原有行为，只多一个方法 ✓</li>
 * </ul>
 */
@Mixin(WolfModel.class)
public abstract class WolfModelMixin implements WolfHeadAccess {

    @Shadow
    protected ModelPart head;

    /** 接口实现：给 {@code DogHeldBallLayer} 拿头部的模型变换 */
    @Override
    public ModelPart rotationCraft$getHead() {
        return this.head;
    }
}

package com.mcmodworkspace.moreballs.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.mcmodworkspace.moreballs.entity.BallProjectile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;

/**
 * <b>球在世界里的 3D 模型</b>（作者 2026-10-29：把 Blockbench 建的球模型做进 mod）。
 *
 * <h2>为什么用 standalone 模型</h2>
 * <p>26.x 已经没有 {@code BakedModel} 了，渲染管线重构成「按需烘焙的独立模型」。
 * NeoForge 为此开了 {@link ModelEvent.RegisterStandalone} —— 可以把任意一个模型 json
 * 当成独立资源烘焙出来，用 {@link ModelManager#getStandaloneModel} 取到
 * {@link BlockStateModelPart}，再交给 {@code OrderedSubmitNodeCollector#submitBlockModel} 画。</p>
 *
 * <p>这条路子是「不改物品模型、只在实体渲染器里换几何」，所以：</p>
 * <ul>
 *   <li><b>物品栏 / 手持 / 掉落物仍然是原来的 2D 图标</b>（{@code item/generated}），不受影响</li>
 *   <li>只有扔出去、躺在地上的球实体用 3D 模型</li>
 * </ul>
 *
 * <h2>17 份模型</h2>
 * <p>几何完全一样（Blockbench 里那 44 个立方体），只有贴图不同 —— 所以生成 17 个
 * {@code models/item/ball3d_<key>.json}，一个个注册。加新球只要加一份 json + 这里加一个 key。</p>
 */
@EventBusSubscriber(modid = com.mcmodworkspace.moreballs.MoreBalls.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class Ball3DModels {

    /** 全部有 3D 模型的球（顺序无所谓，但要和 models/item/ball3d_<key>.json 对得上） */
    public static final String[] KEYS = {
            "wooden", "cobblestone", "iron", "gold", "hollow_iron", "amethyst",
            "copper", "hollow_copper", "redstone", "diamond", "lapis", "tidal",
            "emerald", "dripstone", "preserve", "talisman", "echo"
    };

    /** 这三个用的是单独生成的「填满版」贴图（在 textures/item/ball3d/ 下），不是原版球图标 */
    private static final Set<String> SPECIAL_TEX = Set.of("tidal", "preserve", "talisman");

    private static final Map<String, StandaloneModelKey<BlockStateModelPart>> MODELS = new HashMap<>();
    private static final Map<String, Identifier> TEXTURES = new HashMap<>();

    static {
        for (String k : KEYS) {
            MODELS.put(k, new StandaloneModelKey<>(() -> "rotation_craft:ball3d_" + k));
            // ⚠️ 必须走 block 图集（textures/block/ball3d/）——
            // standalone 模型用 simpleModelWrapper 烘焙时绑的是方块图集，
            // 引用 item 图集里的贴图会被直接拒收（日志：Rejecting block model ... outside of supported atlas）。
            TEXTURES.put(k, Identifier.fromNamespaceAndPath("rotation_craft", "block/ball3d/" + k));
        }
    }

    private Ball3DModels() {
    }

    @SubscribeEvent
    public static void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
        for (String k : KEYS) {
            event.register(MODELS.get(k), SimpleUnbakedStandaloneModel.simpleModelWrapper(
                    Identifier.fromNamespaceAndPath("rotation_craft", "item/ball3d_" + k)));
        }
    }

    /**
     * 从球实体身上推导该用哪份 3D 模型。
     *
     * @return 模型 key；没有对应 3D 模型时返回 {@code null}（调用方应退回原版 2D 渲染）
     */
    public static String keyOf(BallProjectile entity) {
        return keyOf(entity.getItem());
    }

    /** 同上，直接吃物品栈。{@code iron_ball} → {@code iron}；组合球等没有 3D 模型的返回 null */
    public static String keyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"rotation_craft".equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        if (!path.endsWith("_ball")) {
            return null;
        }
        String k = path.substring(0, path.length() - "_ball".length());
        return MODELS.containsKey(k) ? k : null;
    }

    /** 取烘焙好的模型。模型没就绪（比如资源重载中途）时返回 null。 */
    public static BlockStateModelPart part(String key) {
        StandaloneModelKey<BlockStateModelPart> k = MODELS.get(key);
        if (k == null) {
            return null;
        }
        return Minecraft.getInstance().getModelManager().getStandaloneModel(k);
    }

    /**
     * 该球 3D 模型用的 RenderType（带 alpha 裁剪，空心球要看得出镂空）。
     *
     * <p>⚠️ 用 {@code itemCutout} 而不是 {@code entityCutout}（作者 2026-10-29：「黑漆漆的看不出来是啥球」）。
     * 我们的几何是「方块模型那一套 quads」（{@link BlockStateModelPart}），
     * 而 {@code entityCutout} 是按**实体模型的法线**做漫反射的 —— 两者不是同一套光照，
     * 套上去整体偏暗，贴图颜色出不来。{@code itemCutout} 才是给物品/方块模型用的那条管线。</p>
     */
    public static net.minecraft.client.renderer.rendertype.RenderType renderType(String key) {
        return RenderTypes.itemCutout(TEXTURES.getOrDefault(key, TEXTURES.get("iron")));
    }
}

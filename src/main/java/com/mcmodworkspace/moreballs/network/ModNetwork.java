package com.mcmodworkspace.moreballs.network;

import com.mcmodworkspace.moreballs.MoreBalls;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包注册。
 *
 * <p>目前三个，全是「客户端 → 服务端」的请求：</p>
 * <ul>
 *   <li>{@link OpenPouchPayload} —— 按 G 键请求打开饰品栏里的收纳袋</li>
 *   <li>{@link SelectAmmoPayload} —— 径向菜单里选中某种弹药</li>
 *   <li>{@link TidyBallsPayload} —— 一键整理（背包界面那个小按钮）</li>
 * </ul>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class ModNetwork {

    private ModNetwork() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // G 键：请求打开饰品栏里的收纳袋
        registrar.playToServer(
                OpenPouchPayload.TYPE,
                OpenPouchPayload.STREAM_CODEC,
                OpenPouchPayload::handle);

        // 径向菜单：选中某种弹药
        registrar.playToServer(
                SelectAmmoPayload.TYPE,
                SelectAmmoPayload.STREAM_CODEC,
                SelectAmmoPayload::handle);

        // 一键整理：背包界面那个小按钮（作者 2026-10-15 要求）
        registrar.playToServer(
                TidyBallsPayload.TYPE,
                TidyBallsPayload.STREAM_CODEC,
                TidyBallsPayload::handle);
    }
}

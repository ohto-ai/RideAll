package com.example.rideall.listener;

import com.example.rideall.carry.CarryManager;
import com.example.rideall.util.CarryData;
import com.example.rideall.util.Names;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 生存搬运 + shift 下车。
 * 手持带 rideall 数据的物品右键生物 → CarryManager 挂载（仅乘客列表操作，移动由原版处理）。
 * 白名单模式：只有 allow 名单里的类型可搬运；黑名单模式：deny 名单里的类型禁止搬运。
 * 消耗按游戏模式：创造模式不消耗，生存/冒险模式消耗 1 个物品（不看是否 OP）。
 * shift：先自己下车；自己没有骑乘时，放下头顶的搬运实体。
 */
public final class CarryListener implements Listener {

    private static final Component PREFIX = LegacyComponentSerializer.legacySection().deserialize("§8[§6RideAll§8]§r ");

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();

        boolean offHand = event.getHand() == EquipmentSlot.OFF_HAND;
        ItemStack item = offHand
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            return;
        }

        CarryData.CarrySettings settings = CarryData.read(item);
        if (settings.isEmpty()) {
            return; // 不是搬运物品，不干扰
        }

        String typeKey = clicked.getType().getKey().toString();
        if (!settings.allows(typeKey)) {
            send(player, Component.text(CarryData.LIST_DENY.equals(settings.listMode()) ? "该物品禁止装载 " : "该物品不能装载 ", NamedTextColor.RED)
                .append(Names.type(typeKey)));
            return; // 不取消事件、不消耗
        }

        if (CarryManager.getCarried(player).contains(clicked)) {
            send(player, Names.type(typeKey).append(Component.text(" 已经在你的搬运列表里", NamedTextColor.YELLOW)));
            return;
        }

        // 仅修改乘客列表：append 直接骑玩家，chain 叠放到当前最高实体的顶部
        boolean mounted = CarryManager.carry(player, clicked, CarryData.MODE_CHAIN.equals(settings.mode()));
        if (!mounted) {
            send(player, Component.text("装载失败：", NamedTextColor.RED)
                .append(Names.type(typeKey)).append(Component.text(" 无法骑乘到你的头上", NamedTextColor.RED)));
            return;
        }

        event.setCancelled(true); // 阻止原版交互（如剪羊毛、坐船）
        if (player.getGameMode() == GameMode.CREATIVE) {
            send(player, Component.text("已将 ", NamedTextColor.GREEN)
                .append(Names.type(typeKey)).append(Component.text(" 装载到你的头上", NamedTextColor.GREEN)));
        } else {
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                item = new ItemStack(Material.AIR);
            }
            if (offHand) {
                player.getInventory().setItemInOffHand(item);
            } else {
                player.getInventory().setItemInMainHand(item);
            }
            send(player, Component.text("已将 ", NamedTextColor.GREEN)
                .append(Names.type(typeKey)).append(Component.text(" 装载到你的头上，物品已消耗", NamedTextColor.GREEN)));
        }
    }

    /**
     * shift 下车：优先级是先自己下车，自己没有骑乘时才放下头顶的搬运实体。
     * force 挂载：下蹲照常，但 shift 无法下车/放下乘客
     * （原版潜行下马由 VehicleExitEvent 拦截，见 onVehicleExit）。
     */
    @EventHandler(ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isInsideVehicle()) {
            if (CarryManager.isForceRide(player)) {
                return; // force 挂载：不主动下车，原版下马会被拦截
            }
            Entity vehicle = player.getVehicle();
            player.leaveVehicle();
            send(player, Component.text("你已从 ", NamedTextColor.GREEN)
                .append(Names.entity(vehicle)).append(Component.text(" 上下来", NamedTextColor.GREEN)));
            return;
        }
        int released = CarryManager.releaseShiftable(player);
        if (released > 0) {
            send(player, Component.text("已把头顶上的 ", NamedTextColor.GREEN)
                .append(Component.text(released, NamedTextColor.GOLD))
                .append(Component.text(" 个乘客整体放下", NamedTextColor.GREEN)));
        } else if (CarryManager.hasForceEntries(player)) {
            send(player, Component.text("存在无法用 shift 放下的乘客（force 挂载）", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        CarryManager.releaseAll(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        CarryManager.onEntityDeath(event.getEntity());
    }

    /** 被临时驯服的马在最后一个乘客离开后还原；非玩家载具的 force 标记在乘客离开后清理。 */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        CarryManager.restoreTempTame(event.getVehicle());
        CarryManager.restoreForceVehicle(event.getVehicle());
    }

    /**
     * force 挂载：玩家因下蹲触发的原版下马被拦截（下蹲本身不受影响）。
     * 用 EntityDismountEvent 而非 VehicleExitEvent：后者只对 Vehicle 接口的载具触发，
     * 玩家载具上的隐形云不是 Vehicle，拦不住。
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityDismount(EntityDismountEvent event) {
        if (event.getDismounted() instanceof Player player
                && player.isSneaking()
                && CarryManager.isForceRide(event.getDismounted())) {
            event.setCancelled(true);
        }
    }

    private static void send(Player player, Component content) {
        player.sendMessage(Component.empty().append(PREFIX).append(content));
    }
}

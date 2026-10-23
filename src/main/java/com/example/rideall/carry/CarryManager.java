package com.example.rideall.carry;

import io.papermc.paper.chunk.system.entity.EntityLookup;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 搬运管理器：只操作乘客列表（addPassenger），移动完全交给原版，绝不自行移动实体。
 *
 * 结构（GSit 同款）：玩家 ← [区域效果云] ← 生物。
 * 仅当乘客是【玩家】时插入隐形区域效果云：云的类型尺寸（高 0.5）驱动原版附件公式，
 * 把乘客抬离载具玩家的头部 0.5 格——乘客的碰撞箱不再覆盖载具玩家的眼睛；
 * 非玩家乘客直接挂载（原版位置）。云不渲染、无碰撞、不拦截点击。
 *
 * 记录与实际骑乘状态可能失配：每次访问前清洗失效条目；若条目的骑乘链根变成了
 * 另一个在线玩家（例如叠在身上的玩家自己下车），条目自动转登记到该玩家名下。
 */
public final class CarryManager {

    /** 临时驯服标记：未驯服的马挂上乘客后，原版 RunAroundLikeCrazyGoal 会把乘客甩下去。 */
    public static final NamespacedKey TEMP_TAME_KEY = new NamespacedKey("rideall", "temp-tame");

    /** force 挂载标记：乘客（骑乘链上）与载具都不能用 shift 脱离。 */
    public static final NamespacedKey FORCE_KEY = new NamespacedKey("rideall", "force");

    /** 一个被搬运的条目。 */
    private static final class Entry {
        final Entity mob;
        final AreaEffectCloud separator;
        final boolean force;

        Entry(Entity mob, AreaEffectCloud separator, boolean force) {
            this.mob = mob;
            this.separator = separator;
            this.force = force;
        }
    }

    private static final Map<UUID, List<Entry>> CARRIED = new HashMap<>();

    private CarryManager() {
    }

    /** 玩家正在搬运的实体（按叠放顺序；失效条目会被清洗掉）。 */
    public static List<Entity> getCarried(Player player) {
        prune();
        List<Entry> entries = CARRIED.get(player.getUniqueId());
        if (entries == null) {
            return List.of();
        }
        List<Entity> out = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            out.add(entry.mob);
        }
        return out;
    }

    /** 该实体是否正被某人搬运（失效条目视为未搬运）。 */
    public static boolean isCarried(Entity entity) {
        prune();
        for (List<Entry> entries : CARRIED.values()) {
            for (Entry entry : entries) {
                if (entry.mob == entity) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把实体搬到玩家头顶（仅乘客列表操作）——与 mountOnPlayer 同机制（非 force）。
     */
    public static boolean carry(Player player, Entity mob, boolean chain) {
        return mountOnPlayer(player, mob, chain, false);
    }

    /**
     * oride 指令在【玩家载具】上的挂载（仅乘客列表操作）：与搬运同机制——
     * 记录条目、玩家乘客插入隐形分离云（抬离载具头部）、shift 可整体放下。
     * force 时该挂载不能用 shift 脱离（乘客与载具都不行）。
     * 失败返回 false。
     */
    public static boolean mountOnPlayer(Player vehicle, Entity rider, boolean chain, boolean force) {
        prune();
        releaseFromAny(rider); // 转移
        if (rider.isInsideVehicle()) {
            rider.leaveVehicle();
        }

        List<Entry> entries = CARRIED.computeIfAbsent(vehicle.getUniqueId(), k -> new ArrayList<>());
        Entity below = chain && !entries.isEmpty() ? entries.get(entries.size() - 1).mob : vehicle;

        AreaEffectCloud separator = null;
        if (rider instanceof Player) {
            // 先挂载、后生成（GSit 同款顺序）；挂载用 NMS force 绕过原版限制
            SeparatorCloud cloud = new SeparatorCloud(vehicle.getLocation());
            net.minecraft.world.entity.Entity belowHandle = ((CraftEntity) below).getHandle();
            if (!cloud.startRiding(belowHandle, true)
                    || !((CraftEntity) rider).getHandle().startRiding(cloud, true)
                    || !spawnSeparator(cloud)) {
                cloud.stopRiding();
                cloud.discard();
                return false;
            }
            separator = (AreaEffectCloud) cloud.getBukkitEntity();
            if (force) {
                separator.getPersistentDataContainer().set(FORCE_KEY, PersistentDataType.BYTE, (byte) 1);
            }
        } else if (!below.addPassenger(rider)) {
            return false;
        }

        entries.add(new Entry(rider, separator, force));
        return true;
    }

    /** 给非玩家载具打上 force 标记（乘客离开后由 restoreForceVehicle 清理）。 */
    public static void markForceVehicle(Entity vehicle) {
        vehicle.getPersistentDataContainer().set(FORCE_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    /** 非玩家载具的 force 标记：最后一个乘客离开后移除。 */
    public static void restoreForceVehicle(Entity vehicle) {
        if (!(vehicle instanceof Player)
                && vehicle.getPersistentDataContainer().has(FORCE_KEY, PersistentDataType.BYTE)
                && vehicle.getPassengers().isEmpty()) {
            vehicle.getPersistentDataContainer().remove(FORCE_KEY);
        }
    }

    /**
     * 玩家自己的挂载链接是否为 force（只检查【直接载具】）：
     * force 只约束该挂载链接上的两个对象，不向整条链传递。
     */
    public static boolean isForceRide(Entity rider) {
        Entity vehicle = rider.getVehicle();
        return vehicle != null && vehicle.getPersistentDataContainer().has(FORCE_KEY, PersistentDataType.BYTE);
    }

    /** 是否有 force 条目（载具侧提示用）。 */
    public static boolean hasForceEntries(Player player) {
        prune();
        List<Entry> entries = CARRIED.get(player.getUniqueId());
        if (entries == null) {
            return false;
        }
        for (Entry entry : entries) {
            if (entry.force) {
                return true;
            }
        }
        return false;
    }

    /** shift 放下：存在 force 条目时不动作；否则整体放下，返回放下的数量。 */
    public static int releaseShiftable(Player player) {
        prune();
        List<Entry> entries = CARRIED.get(player.getUniqueId());
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        for (Entry entry : entries) {
            if (entry.force) {
                return 0;
            }
        }
        int count = entries.size();
        releaseAll(player);
        return count;
    }

    /** 把分离载具加入世界（Paper 的实体查找器直接注册，参考 GSit）。 */
    private static boolean spawnSeparator(SeparatorCloud cloud) {
        Object getter = cloud.level().getEntities();
        if (!(getter instanceof EntityLookup lookup)) {
            return false;
        }
        return lookup.addNewEntity(cloud);
    }

    /**
     * 载具是未驯服的马时临时视为驯服（避免原版甩客），并用 PDC 标记，
     * 最后一个乘客离开时由 restoreTempTame 还原。
     */
    public static void tempTameIfNeeded(Entity vehicle) {
        if (vehicle instanceof AbstractHorse horse && !horse.isTamed()) {
            horse.setTamed(true);
            horse.getPersistentDataContainer().set(TEMP_TAME_KEY, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /** 被临时驯服的马在最后一个乘客离开后还原为未驯服。 */
    public static void restoreTempTame(Entity vehicle) {
        if (vehicle instanceof AbstractHorse horse
                && horse.getPersistentDataContainer().has(TEMP_TAME_KEY, PersistentDataType.BYTE)
                && horse.getPassengers().isEmpty()) {
            horse.setTamed(false);
            horse.getPersistentDataContainer().remove(TEMP_TAME_KEY);
        }
    }

    /** 清洗失效条目；骑乘链根变成其他在线玩家时，把条目转登记到该玩家名下。 */
    private static void prune() {
        for (Map.Entry<UUID, List<Entry>> mapEntry : new ArrayList<>(CARRIED.entrySet())) {
            Player owner = Bukkit.getPlayer(mapEntry.getKey());
            List<Entry> entries = mapEntry.getValue();
            if (owner == null || !owner.isOnline()) {
                entries.clear(); // 兜底（退出事件已处理）
            } else {
                for (Entry entry : new ArrayList<>(entries)) {
                    if (!entry.mob.isValid() || !entry.mob.isInsideVehicle()) {
                        entries.remove(entry);
                        releaseEntry(entry);
                        continue;
                    }
                    Entity root = entry.mob;
                    while (root.isInsideVehicle()) {
                        root = root.getVehicle();
                    }
                    if (root == owner) {
                        continue;
                    }
                    entries.remove(entry);
                    if (root instanceof Player newOwner && newOwner.isOnline()) {
                        // 叠在身上的玩家自己下车了：链条归其管理（例如再按 shift 放下）
                        CARRIED.computeIfAbsent(newOwner.getUniqueId(), k -> new ArrayList<>()).add(entry);
                    } else {
                        releaseEntry(entry);
                    }
                }
            }
            if (entries.isEmpty()) {
                CARRIED.remove(mapEntry.getKey());
            }
        }
    }

    /** 把实体（及其上方叠放的条目）从任何搬运者处放下；实体是玩家时上方链条转交其管理。 */
    public static void releaseFromAny(Entity entity) {
        prune();
        for (Map.Entry<UUID, List<Entry>> mapEntry : new ArrayList<>(CARRIED.entrySet())) {
            List<Entry> entries = mapEntry.getValue();
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if (entry.mob == entity) {
                    List<Entry> above = new ArrayList<>(entries.subList(i + 1, entries.size()));
                    entries.subList(i, entries.size()).clear();
                    releaseEntry(entry);
                    if (entity instanceof Player player && !above.isEmpty()) {
                        CARRIED.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).addAll(above);
                    } else {
                        for (Entry e : above) {
                            releaseEntry(e);
                        }
                    }
                    if (entries.isEmpty()) {
                        CARRIED.remove(mapEntry.getKey());
                    }
                    return;
                }
            }
        }
    }

    /**
     * 放下玩家正在搬运的所有实体（整体下车）：
     * 只断开最底层的连接，上方的链式结构原样保留、跟随底层实体整体下落，不弹出、不散开。
     * 最底层实体是玩家时，上方的条目转交其管理（再按 shift 即可把头顶乘客放下）。
     */
    public static void releaseAll(Player player) {
        prune();
        List<Entry> entries = CARRIED.remove(player.getUniqueId());
        if (entries == null) {
            return;
        }
        Entry bottom = entries.get(0);
        if (bottom.mob.isValid() && bottom.mob.isInsideVehicle()) {
            bottom.mob.leaveVehicle();
        }
        removeSeparator(bottom.separator);
        // 上方条目转交给最底部的实体（若是玩家）：整体下车后由其继续管理
        if (entries.size() > 1 && bottom.mob instanceof Player newOwner) {
            CARRIED.computeIfAbsent(newOwner.getUniqueId(), k -> new ArrayList<>())
                .addAll(new ArrayList<>(entries.subList(1, entries.size())));
        }
    }

    /** 搬运的实体死亡时清理记录。 */
    public static void onEntityDeath(Entity entity) {
        releaseFromAny(entity);
    }

    /** 插件停用时放下所有实体。 */
    public static void releaseAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            releaseAll(player);
        }
    }

    private static void releaseEntry(Entry entry) {
        if (entry.mob.isValid() && entry.mob.isInsideVehicle()) {
            entry.mob.leaveVehicle();
        }
        removeSeparator(entry.separator);
    }

    private static void removeSeparator(AreaEffectCloud separator) {
        if (separator == null || !separator.isValid()) {
            return;
        }
        if (separator.isInsideVehicle()) {
            separator.leaveVehicle();
        }
        separator.remove();
    }
}

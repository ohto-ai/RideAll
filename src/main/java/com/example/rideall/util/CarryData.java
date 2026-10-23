package com.example.rideall.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 读写物品 minecraft:custom_data 组件中的 rideall 数据（真实 NBT，可用 NBT 编辑器手写）：
 * {rideall:{allow:[...], deny:[...], list:"allow"|"deny", mode:"append"|"chain"}}
 */
public final class CarryData {

    public static final String KEY = "rideall";
    public static final String ALLOW = "allow";
    public static final String DENY = "deny";
    public static final String LIST = "list";
    public static final String MODE = "mode";

    public static final String LIST_ALLOW = "allow"; // 白名单模式：只有 allow 里的类型可以搬运
    public static final String LIST_DENY = "deny";   // 黑名单模式：deny 里的类型禁止搬运
    public static final String MODE_APPEND = "append";
    public static final String MODE_CHAIN = "chain";

    /** 未填写 mode 时使用的默认叠放模式（来自 config.yml 的 default-mode）。 */
    private static String defaultMode = MODE_APPEND;

    private CarryData() {
    }

    /** 设置默认叠放模式（插件启动与 /oride config defaultmode 时调用）。 */
    public static void setDefaultMode(String mode) {
        defaultMode = MODE_CHAIN.equals(mode) ? MODE_CHAIN : MODE_APPEND;
    }

    /** 归一化实体类型 id：小写、补全 minecraft: 前缀（"Cow"/"cow" → "minecraft:cow"）。 */
    public static String normalizeType(String value) {
        String t = value.trim().toLowerCase(Locale.ROOT);
        return t.contains(":") ? t : "minecraft:" + t;
    }

    /** 读取物品的完整搬运设置；物品为空或无数据时返回空设置。 */
    public static CarrySettings read(ItemStack bukkit) {
        if (bukkit == null || bukkit.getType() == Material.AIR) {
            return CarrySettings.empty();
        }
        net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(bukkit);
        CustomData customData = nms.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return CarrySettings.empty();
        }
        CompoundTag rideAll = customData.copyTag().getCompound(KEY);
        if (rideAll.isEmpty()) {
            return CarrySettings.empty();
        }
        List<String> allow = readList(rideAll, ALLOW);
        List<String> deny = readList(rideAll, DENY);
        String listMode = LIST_DENY.equalsIgnoreCase(rideAll.getString(LIST)) ? LIST_DENY : LIST_ALLOW;
        String mode = MODE_CHAIN.equalsIgnoreCase(rideAll.getString(MODE)) ? MODE_CHAIN : defaultMode;
        return new CarrySettings(allow, deny, listMode, mode);
    }

    /** 全量写入；两个名单都为空时移除整个 rideall 键（物品不再是搬运物品）。 */
    public static ItemStack write(ItemStack bukkit, CarrySettings settings) {
        net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(bukkit);
        CustomData existing = nms.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = existing == null ? new CompoundTag() : existing.copyTag();

        if (settings.allow().isEmpty() && settings.deny().isEmpty()) {
            root.remove(KEY);
        } else {
            CompoundTag rideAll = new CompoundTag();
            putList(rideAll, ALLOW, settings.allow());
            putList(rideAll, DENY, settings.deny());
            rideAll.put(LIST, StringTag.valueOf(LIST_DENY.equals(settings.listMode()) ? LIST_DENY : LIST_ALLOW));
            rideAll.put(MODE, StringTag.valueOf(MODE_CHAIN.equals(settings.mode()) ? MODE_CHAIN : MODE_APPEND));
            root.put(KEY, rideAll);
        }

        if (root.isEmpty()) {
            nms.remove(DataComponents.CUSTOM_DATA);
        } else {
            nms.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }
        return CraftItemStack.asBukkitCopy(nms);
    }

    private static List<String> readList(CompoundTag rideAll, String key) {
        ListTag list = rideAll.getList(key, Tag.TAG_STRING);
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }

    private static void putList(CompoundTag rideAll, String key, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        rideAll.put(key, list);
    }

    /**
     * 物品的搬运设置。列表可直接修改，改完后用 CarryData.write 写回。
     */
    public record CarrySettings(
            List<String> allow,
            List<String> deny,
            String listMode,
            String mode
    ) {
        public static CarrySettings empty() {
            return new CarrySettings(List.of(), List.of(), LIST_ALLOW, MODE_APPEND);
        }

        public boolean isEmpty() {
            return allow.isEmpty() && deny.isEmpty();
        }

        public CarrySettings withAllow(List<String> allow) {
            return new CarrySettings(allow, deny, listMode, mode);
        }

        public CarrySettings withDeny(List<String> deny) {
            return new CarrySettings(allow, deny, listMode, mode);
        }

        public CarrySettings withListMode(String listMode) {
            return new CarrySettings(allow, deny, listMode, mode);
        }

        public CarrySettings withMode(String mode) {
            return new CarrySettings(allow, deny, listMode, mode);
        }

        /** 该实体类型是否允许被此物品搬运（按当前白/黑名单模式）。 */
        public boolean allows(String typeKey) {
            String key = normalizeType(typeKey);
            if (LIST_DENY.equals(listMode)) {
                for (String entry : deny) {
                    if (normalizeType(entry).equals(key)) {
                        return false;
                    }
                }
                return true;
            }
            for (String entry : allow) {
                if (normalizeType(entry).equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }
}

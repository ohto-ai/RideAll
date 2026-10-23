package com.example.rideall.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 实体名称组件：自定义名原样保留，玩家用显示名，其余用原版翻译键（客户端按语言包本地化）。
 */
public final class Names {

    private Names() {
    }

    /** 实体名：自定义名 → 玩家显示名 → 原版翻译键（金色）。 */
    public static Component entity(Entity entity) {
        Component custom = entity.customName();
        if (custom != null) {
            return custom;
        }
        if (entity instanceof Player player) {
            return player.name();
        }
        return type(entity.getType().getKey().toString());
    }

    /** 实体类型名（金色）：原版翻译键（如 entity.minecraft.cow → "牛"/"Cow"），键不存在时退回原始 id。 */
    public static Component type(String typeKey) {
        return type(typeKey, NamedTextColor.GOLD);
    }

    /** 实体类型名（指定颜色）。 */
    public static Component type(String typeKey, NamedTextColor color) {
        String id = CarryData.normalizeType(typeKey);
        return Component.translatable("entity." + id.replace(':', '.'), id).color(color);
    }
}

package com.example.rideall.carry;

import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * 隐形分离载具（GSit 的 PlayerSitEntity 同款处理方式）：
 * 区域效果云的尺寸来自实体类型注册表（高 0.5），原版附件公式
 * PASSENGER = (0, height, 0) 使每层云把乘客抬升 0.5 格——
 * 客户端天然可见，不需要任何位置重写或同步数据技巧。
 * 空 tick：不生成粒子、不施效果、不过期。
 */
public class SeparatorCloud extends AreaEffectCloud {

    public SeparatorCloud(Location location) {
        super(EntityType.AREA_EFFECT_CLOUD, ((CraftWorld) location.getWorld()).getHandle());
        persist = false;
        setRadius(0.0F);
        setDuration(Integer.MAX_VALUE);
        setNoGravity(true);
        setInvulnerable(true);
        setPos(location.getX(), location.getY(), location.getZ());
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean canChangeDimensions() {
        return false;
    }

    @Override
    public boolean dismountsUnderwater() {
        return false;
    }
}

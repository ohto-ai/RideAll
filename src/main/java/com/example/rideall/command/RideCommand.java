package com.example.rideall.command;

import com.example.rideall.carry.CarryManager;
import com.example.rideall.util.CarryData;
import com.example.rideall.util.Names;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * /oride —— 任意实体骑乘任意实体 + 搬运物品编辑。
 * 通过 Brigadier 注册，实体参数使用原版选择器（与 /tp 相同的解析与补全）。
 */
public final class RideCommand {

    /** lore 中 RideAll 渲染块的起始标记行。 */
    private static final String LORE_HEADER = "§8[§6RideAll§8]";

    private static final Component PREFIX = LegacyComponentSerializer.legacySection().deserialize("§8[§6RideAll§8]§r ");

    private static JavaPlugin plugin;

    private RideCommand() {
    }

    public static void register(JavaPlugin plugin) {
        RideCommand.plugin = plugin;
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(
                Commands.literal("oride")
                    .requires(source -> source.getSender().hasPermission("oride.use"))
                    .executes(ctx -> {
                        sendUsage(ctx.getSource().getSender());
                        return 1;
                    })
                    .then(Commands.literal("append").then(entityPair(ctx -> mount(ctx, false))))
                    .then(Commands.literal("chain").then(entityPair(ctx -> mount(ctx, true))))
                    .then(Commands.literal("force_append").then(entityPair(ctx -> mount(ctx, false, true))))
                    .then(Commands.literal("force_chain").then(entityPair(ctx -> mount(ctx, true, true))))
                    .then(Commands.literal("dismount")
                        .then(Commands.argument("entities", ArgumentTypes.entities())
                            .executes(RideCommand::dismount)))
                    .then(Commands.literal("eject")
                        .then(Commands.argument("entities", ArgumentTypes.entities())
                            .executes(RideCommand::eject)))
                    .then(Commands.literal("item")
                        .then(Commands.literal("allow")
                            .then(Commands.argument("type", ArgumentTypes.resource(RegistryKey.ENTITY_TYPE))
                                .executes(RideCommand::itemAllow)))
                        .then(Commands.literal("deny")
                            .then(Commands.argument("type", ArgumentTypes.resource(RegistryKey.ENTITY_TYPE))
                                .executes(RideCommand::itemDeny)))
                        .then(Commands.literal("remove")
                            .then(Commands.argument("type", StringArgumentType.greedyString())
                                .suggests(RideCommand::suggestRemovableTypes)
                                .executes(RideCommand::itemRemove)))
                        .then(Commands.literal("clear").executes(RideCommand::itemClear))
                        .then(Commands.literal("list").executes(RideCommand::itemList))
                        .then(Commands.literal("render").executes(RideCommand::itemRender))
                        .then(Commands.literal("whitelist").executes(RideCommand::itemWhitelist))
                        .then(Commands.literal("blacklist").executes(RideCommand::itemBlacklist))
                        .then(Commands.literal("mode")
                            .then(Commands.literal("append").executes(ctx -> itemMode(ctx, CarryData.MODE_APPEND)))
                            .then(Commands.literal("chain").executes(ctx -> itemMode(ctx, CarryData.MODE_CHAIN)))))
                    .then(Commands.literal("config")
                        .executes(RideCommand::configShow)
                        .then(Commands.literal("autorender")
                            .then(Commands.literal("on").executes(ctx -> configSet(ctx, true)))
                            .then(Commands.literal("off").executes(ctx -> configSet(ctx, false))))
                        .then(Commands.literal("defaultmode")
                            .then(Commands.literal("append").executes(ctx -> configDefaultMode(ctx, CarryData.MODE_APPEND)))
                            .then(Commands.literal("chain").executes(ctx -> configDefaultMode(ctx, CarryData.MODE_CHAIN)))))
                    .build(),
                "让任意实体骑乘任意实体，或编辑手中物品的搬运列表",
                List.of()
            );
        });
    }

    /** <骑手> <载具> 两个原版实体选择器参数。 */
    private static ArgumentBuilder<CommandSourceStack, ?> entityPair(Command<CommandSourceStack> executor) {
        return Commands.argument("rider", ArgumentTypes.entity())
            .then(Commands.argument("vehicle", ArgumentTypes.entity()).executes(executor));
    }

    private static Entity resolveSingle(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return ctx.getArgument(name, EntitySelectorArgumentResolver.class).resolve(ctx.getSource()).get(0);
    }

    /** append：乘客加入载具的乘客列表；chain：乘客叠放到载具乘客链的最顶层。 */
    private static int mount(CommandContext<CommandSourceStack> ctx, boolean chain) throws CommandSyntaxException {
        return mount(ctx, chain, false);
    }

    /** force：乘客与载具均无法用 shift 脱离（管理员用）。 */
    private static int mount(CommandContext<CommandSourceStack> ctx, boolean chain, boolean force) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Entity rider = resolveSingle(ctx, "rider");
        Entity vehicle = resolveSingle(ctx, "vehicle");

        if (rider == vehicle) {
            msg(sender, Component.text("实体不能骑乘自己：", NamedTextColor.RED).append(Names.entity(rider)));
            return 1;
        }
        if (!rider.getWorld().equals(vehicle.getWorld())) {
            msg(sender, Component.text("骑手与载具不在同一个世界：", NamedTextColor.RED)
                .append(Names.entity(rider)).append(Component.text(" / ", NamedTextColor.RED)).append(Names.entity(vehicle)));
            return 1;
        }

        // 正被搬运的实体先放下（连同分隔实体），再挂载
        if (CarryManager.isCarried(rider)) {
            CarryManager.releaseFromAny(rider);
        }
        if (CarryManager.isCarried(vehicle)) {
            CarryManager.releaseFromAny(vehicle);
        }

        if (rider.isInsideVehicle()) {
            rider.leaveVehicle(); // 强制转移：无限制
        }

        // 玩家载具：与搬运同机制（记录条目、玩家乘客插云抬升、shift 可放下）
        if (vehicle instanceof Player playerVehicle) {
            boolean success = CarryManager.mountOnPlayer(playerVehicle, rider, chain, force);
            if (success) {
                msg(sender, Component.text("已将 ", NamedTextColor.GREEN)
                    .append(Names.entity(rider))
                    .append(Component.text(chain ? " 叠放到 " : " 骑乘到 ", NamedTextColor.GREEN))
                    .append(Names.entity(vehicle))
                    .append(Component.text(chain ? " 乘客链的顶部" : " 上", NamedTextColor.GREEN)));
            } else {
                msg(sender, Component.text("无法让 ", NamedTextColor.RED)
                    .append(Names.entity(rider)).append(Component.text(" 骑乘 ", NamedTextColor.RED))
                    .append(Names.entity(vehicle)).append(Component.text("（可能形成骑乘循环）", NamedTextColor.RED)));
            }
            return 1;
        }

        // 其余载具：原路径（chain 走位 + 直接挂载）
        Entity top = vehicle;
        if (chain) {
            // 沿每层最后一位乘客向上，找到乘客链的最顶层
            while (!top.getPassengers().isEmpty()) {
                List<Entity> passengers = top.getPassengers();
                top = passengers.get(passengers.size() - 1);
                if (top == rider) {
                    msg(sender, Names.entity(rider)
                        .append(Component.text(" 已经在 ", NamedTextColor.YELLOW)).append(Names.entity(vehicle))
                        .append(Component.text(" 的乘客链上", NamedTextColor.YELLOW)));
                    return 1;
                }
            }
        } else {
            if (vehicle.getPassengers().contains(rider)) {
                msg(sender, Names.entity(rider)
                    .append(Component.text(" 已经在骑乘 ", NamedTextColor.YELLOW)).append(Names.entity(vehicle)));
                return 1;
            }
        }

        boolean success;
        try {
            success = top.addPassenger(rider);
        } catch (IllegalArgumentException e) {
            success = false;
        }
        if (success) {
            CarryManager.tempTameIfNeeded(vehicle); // 未驯服的马临时视为驯服，避免原版甩客
            if (force) {
                CarryManager.markForceVehicle(vehicle); // 乘客无法用 shift 脱离
            }
            msg(sender, Component.text("已将 ", NamedTextColor.GREEN)
                .append(Names.entity(rider))
                .append(Component.text(chain ? " 叠放到 " : " 骑乘到 ", NamedTextColor.GREEN))
                .append(Names.entity(vehicle))
                .append(Component.text(chain ? " 乘客链的顶部" : " 上", NamedTextColor.GREEN)));
        } else {
            msg(sender, Component.text("无法让 ", NamedTextColor.RED)
                .append(Names.entity(rider)).append(Component.text(" 骑乘 ", NamedTextColor.RED))
                .append(Names.entity(vehicle)).append(Component.text("（可能形成骑乘循环）", NamedTextColor.RED)));
        }
        return 1;
    }

    /** /oride dismount <实体...> —— 让实体从载具上下来。 */
    private static int dismount(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        List<Entity> entities = ctx.getArgument("entities", EntitySelectorArgumentResolver.class).resolve(ctx.getSource());
        for (Entity entity : entities) {
            if (CarryManager.isCarried(entity)) {
                CarryManager.releaseFromAny(entity);
                msg(sender, Component.text("已放下 ", NamedTextColor.GREEN).append(Names.entity(entity)));
                continue;
            }
            if (entity.isInsideVehicle()) {
                Entity vehicle = entity.getVehicle();
                entity.leaveVehicle();
                msg(sender, Component.text("已让 ", NamedTextColor.GREEN)
                    .append(Names.entity(entity)).append(Component.text(" 从 ", NamedTextColor.GREEN))
                    .append(Names.entity(vehicle)).append(Component.text(" 上下来", NamedTextColor.GREEN)));
            } else {
                msg(sender, Names.entity(entity).append(Component.text(" 当前未在骑乘", NamedTextColor.YELLOW)));
            }
        }
        return 1;
    }

    /** /oride eject <实体...> —— 让实体的所有乘客下来。 */
    private static int eject(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        List<Entity> entities = ctx.getArgument("entities", EntitySelectorArgumentResolver.class).resolve(ctx.getSource());
        for (Entity entity : entities) {
            boolean any = false;
            if (entity instanceof Player player && !CarryManager.getCarried(player).isEmpty()) {
                CarryManager.releaseAll(player); // 先放下搬运实体（连同分隔实体）
                any = true;
            }
            if (entity.eject()) {
                any = true;
            }
            if (any) {
                msg(sender, Component.text("已让 ", NamedTextColor.GREEN)
                    .append(Names.entity(entity)).append(Component.text(" 的所有乘客下来", NamedTextColor.GREEN)));
            } else {
                msg(sender, Names.entity(entity).append(Component.text(" 没有乘客", NamedTextColor.YELLOW)));
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // /oride item —— 搬运物品编辑
    // ------------------------------------------------------------------

    /** 手持物品检查；不满足时返回 null。 */
    private static ItemStack heldItem(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, "§c该子命令只能由玩家执行");
            return null;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            msg(player, "§c请手持一个物品");
            return null;
        }
        return hand;
    }

    /** 写回物品并（自动渲染开启时）同步 lore。 */
    private static void saveSettings(Player player, ItemStack hand, CarryData.CarrySettings settings) {
        ItemStack result = CarryData.write(hand, settings);
        if (autoRender()) {
            result = renderLore(result);
        }
        player.getInventory().setItemInMainHand(result);
    }

    /** /oride item allow <实体类型> —— 加入允许名单。 */
    private static int itemAllow(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ItemStack hand = heldItem(sender);
        if (hand == null) {
            return 1;
        }
        EntityType type = ctx.getArgument("type", EntityType.class);
        CarryData.CarrySettings settings = CarryData.read(hand);
        Set<String> allow = new LinkedHashSet<>(settings.allow());
        boolean added = allow.add(type.getKey().toString());
        saveSettings((Player) sender, hand, settings.withAllow(new ArrayList<>(allow)));
        if (added) {
            msg(sender, Component.text("已把 ", NamedTextColor.GREEN)
                .append(Names.type(type.getKey().toString())).append(Component.text(" 加入允许名单", NamedTextColor.GREEN)));
        } else {
            msg(sender, Names.type(type.getKey().toString()).append(Component.text(" 已在允许名单中", NamedTextColor.YELLOW)));
        }
        return 1;
    }

    /** /oride item deny <实体类型> —— 加入禁止名单。 */
    private static int itemDeny(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ItemStack hand = heldItem(sender);
        if (hand == null) {
            return 1;
        }
        EntityType type = ctx.getArgument("type", EntityType.class);
        CarryData.CarrySettings settings = CarryData.read(hand);
        Set<String> deny = new LinkedHashSet<>(settings.deny());
        boolean added = deny.add(type.getKey().toString());
        saveSettings((Player) sender, hand, settings.withDeny(new ArrayList<>(deny)));
        if (added) {
            msg(sender, Component.text("已把 ", NamedTextColor.GREEN)
                .append(Names.type(type.getKey().toString())).append(Component.text(" 加入禁止名单", NamedTextColor.GREEN)));
        } else {
            msg(sender, Names.type(type.getKey().toString()).append(Component.text(" 已在禁止名单中", NamedTextColor.YELLOW)));
        }
        return 1;
    }

    /** remove 的补全：只提示当前物品两个名单里已有的类型（可删除的）。 */
    private static CompletableFuture<Suggestions> suggestRemovableTypes(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getSender() instanceof Player player) {
            CarryData.CarrySettings settings = CarryData.read(player.getInventory().getItemInMainHand());
            Set<String> types = new LinkedHashSet<>(settings.allow());
            types.addAll(settings.deny());
            for (String type : types) {
                builder.suggest(type);
                // 额外建议省略 minecraft: 前缀的形式，方便输入 cow<Tab>
                if (type.startsWith("minecraft:")) {
                    builder.suggest(type.substring("minecraft:".length()));
                }
            }
        }
        return builder.buildFuture();
    }

    /** /oride item remove <实体类型> —— 把该类型从两个名单中去除。 */
    private static int itemRemove(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ItemStack hand = heldItem(sender);
        if (hand == null) {
            return 1;
        }
        String wanted = CarryData.normalizeType(ctx.getArgument("type", String.class));
        CarryData.CarrySettings settings = CarryData.read(hand);
        Set<String> allow = new LinkedHashSet<>(settings.allow());
        Set<String> deny = new LinkedHashSet<>(settings.deny());
        boolean removed = allow.removeIf(entry -> CarryData.normalizeType(entry).equals(wanted));
        removed |= deny.removeIf(entry -> CarryData.normalizeType(entry).equals(wanted));
        saveSettings((Player) sender, hand, settings.withAllow(new ArrayList<>(allow)).withDeny(new ArrayList<>(deny)));
        if (removed) {
            msg(sender, Component.text("已把 ", NamedTextColor.GREEN)
                .append(Names.type(wanted)).append(Component.text(" 从名单中移除", NamedTextColor.GREEN)));
        } else {
            msg(sender, Names.type(wanted).append(Component.text(" 不在名单中", NamedTextColor.YELLOW)));
        }
        return 1;
    }

    /** /oride item clear —— 清空搬运数据（物品变回普通物品）。 */
    private static int itemClear(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ItemStack hand = heldItem(sender);
        if (hand == null) {
            return 1;
        }
        saveSettings((Player) sender, hand, CarryData.CarrySettings.empty());
        msg(sender, "§a已清空该物品的搬运数据");
        return 1;
    }

    /** /oride item list —— 查看搬运设置。 */
    private static int itemList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ItemStack hand = heldItem(sender);
        if (hand == null) {
            return 1;
        }
        CarryData.CarrySettings settings = CarryData.read(hand);
        if (settings.isEmpty()) {
            msg(sender, "§e该物品没有搬运数据");
            return 1;
        }
        Component content = Component.text("名单模式：", NamedTextColor.GREEN)
            .append(Component.text(CarryData.LIST_DENY.equals(settings.listMode()) ? "黑名单" : "白名单", NamedTextColor.GOLD));
        if (!settings.allow().isEmpty()) {
            content = content.append(Component.text("，允许：", NamedTextColor.GREEN)).append(typeList(settings.allow()));
        }
        if (!settings.deny().isEmpty()) {
            content = content.append(Component.text("，禁止：", NamedTextColor.GREEN)).append(typeList(settings.deny()));
        }
        content = content.append(Component.text("，叠放模式：" + modeText(settings.mode()), NamedTextColor.GREEN));
        msg(sender, content);
        return 1;
    }

    /** /oride item whitelist —— 切换为白名单（只允许名单内的类型）。 */
    private static int itemWhitelist(CommandContext<CommandSourceStack> ctx) {
        return itemListMode(ctx, CarryData.LIST_ALLOW);
    }

    /** /oride item blacklist —— 切换为黑名单（名单内的类型禁止）。 */
    private static int itemBlacklist(CommandContext<CommandSourceStack> ctx) {
        return itemListMode(ctx, CarryData.LIST_DENY);
    }

    private static int itemListMode(CommandContext<CommandSourceStack> ctx, String listMode) {
        CommandSender sender = ctx.getSource().getSender();
        ItemStack hand = heldItem(sender);
        if (hand == null) {
            return 1;
        }
        CarryData.CarrySettings settings = CarryData.read(hand);
        if (settings.isEmpty()) {
            msg(sender, "§e该物品没有搬运数据，请先执行 §6/oride item allow|deny <实体类型>");
            return 1;
        }
        saveSettings((Player) sender, hand, settings.withListMode(listMode));
        msg(sender, CarryData.LIST_DENY.equals(listMode)
            ? "§a已切换为黑名单（名单内的类型禁止搬运）"
            : "§a已切换为白名单（只有名单内的类型可以搬运）");
        return 1;
    }

    /** /oride item mode append|chain —— 设置叠放模式。 */
    private static int itemMode(CommandContext<CommandSourceStack> ctx, String mode) {
        CommandSender sender = ctx.getSource().getSender();
        ItemStack hand = heldItem(sender);
        if (hand == null) {
            return 1;
        }
        CarryData.CarrySettings settings = CarryData.read(hand);
        if (settings.isEmpty()) {
            msg(sender, "§e该物品没有搬运数据，请先执行 §6/oride item allow|deny <实体类型>");
            return 1;
        }
        saveSettings((Player) sender, hand, settings.withMode(mode));
        msg(sender, "§a叠放模式已设为 §6" + modeText(mode));
        return 1;
    }

    /** /oride item render —— 手动同步：白名单渲染允许名单，黑名单渲染禁止名单；无数据移除残留渲染块。 */
    private static int itemRender(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        ItemStack hand = heldItem(sender);
        if (hand == null) {
            return 1;
        }
        CarryData.CarrySettings settings = CarryData.read(hand);
        ((Player) sender).getInventory().setItemInMainHand(renderLore(hand));
        if (settings.isEmpty()) {
            msg(sender, "§e该物品没有搬运数据，已移除旧的渲染块");
        } else {
            msg(sender, "§a已把搬运设置渲染到物品上");
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 配置（自动渲染 / 默认叠放模式）
    // ------------------------------------------------------------------

    private static int configShow(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        msg(sender, "§a自动渲染 lore：§6" + (autoRender() ? "开" : "关")
            + " §a，默认叠放模式：§6" + modeText(plugin.getConfig().getString("default-mode", CarryData.MODE_APPEND)));
        msg(sender, "§7切换：§6/oride config autorender on|off §7、§6/oride config defaultmode append|chain");
        return 1;
    }

    private static int configSet(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        CommandSender sender = ctx.getSource().getSender();
        plugin.getConfig().set("auto-render", enabled);
        plugin.saveConfig();
        msg(sender, "§a自动渲染已§6" + (enabled ? "开启" : "关闭")
            + "§a，之后的 §6/oride item §a修改会立即同步到物品 lore");
        return 1;
    }

    private static int configDefaultMode(CommandContext<CommandSourceStack> ctx, String mode) {
        CommandSender sender = ctx.getSource().getSender();
        plugin.getConfig().set("default-mode", mode);
        plugin.saveConfig();
        CarryData.setDefaultMode(mode);
        msg(sender, "§a默认叠放模式已设为 §6" + modeText(mode) + "§a（新物品与未填写 mode 的物品生效）");
        return 1;
    }

    private static boolean autoRender() {
        return plugin.getConfig().getBoolean("auto-render", true);
    }

    // ------------------------------------------------------------------
    // lore 渲染
    // ------------------------------------------------------------------

    /** 渲染搬运设置到 lore 块（白名单渲染允许名单、黑名单渲染禁止名单；无内容移除旧块）。 */
    private static ItemStack renderLore(ItemStack hand) {
        CarryData.CarrySettings settings = CarryData.read(hand);
        List<String> entries = CarryData.LIST_DENY.equals(settings.listMode()) ? settings.deny() : settings.allow();
        if (entries.isEmpty()) {
            return stripLore(hand); // 没有可渲染的名单内容，移除旧块
        }
        ItemMeta meta = hand.getItemMeta();
        List<Component> newLore = new ArrayList<>();
        List<Component> existing = meta.lore();
        if (existing != null) {
            int marker = loreMarker(existing);
            newLore.addAll(marker >= 0 ? existing.subList(0, marker) : existing);
        }
        newLore.add(LegacyComponentSerializer.legacySection().deserialize(LORE_HEADER));
        newLore.add(LegacyComponentSerializer.legacySection().deserialize(
                CarryData.LIST_DENY.equals(settings.listMode()) ? "§7黑名单（禁止以下）：" : "§7白名单（只允许以下）："));
        for (String type : entries) {
            // 用原版翻译键（translate 组件）：客户端按自己的语言包显示实体名（如"牛"/"Cow"），
            // 键不存在（如模组实体）时退回显示原始 id
            newLore.add(Component.text("- ").color(NamedTextColor.GRAY).append(Names.type(type, NamedTextColor.WHITE)));
        }
        newLore.add(LegacyComponentSerializer.legacySection().deserialize("§7模式: §f" + modeText(settings.mode())));
        meta.lore(newLore);
        hand.setItemMeta(meta);
        return hand;
    }

    /** 移除 lore 中的 RideAll 渲染块（清空数据时同步用）。 */
    private static ItemStack stripLore(ItemStack hand) {
        ItemMeta meta = hand.getItemMeta();
        List<Component> existing = meta.lore();
        if (existing == null) {
            return hand;
        }
        int marker = loreMarker(existing);
        if (marker < 0) {
            return hand;
        }
        meta.lore(existing.subList(0, marker));
        hand.setItemMeta(meta);
        return hand;
    }

    /** 返回渲染块标记行的下标，没有则 -1。 */
    private static int loreMarker(List<Component> lore) {
        for (int i = 0; i < lore.size(); i++) {
            if (LegacyComponentSerializer.legacySection().serialize(lore.get(i)).equals(LORE_HEADER)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static void sendUsage(CommandSender sender) {
        msg(sender, "§e用法：");
        msg(sender, "§7/oride append <乘客> <载具> — 把乘客加入载具的乘客列表");
        msg(sender, "§7/oride chain <乘客> <载具> — 把乘客叠放到载具乘客链的最顶层");
        msg(sender, "§7/oride force_append|force_chain <乘客> <载具> — 同 append/chain，但无法用 shift 脱离（管理员用）");
        msg(sender, "§7/oride dismount <实体> — 让实体从载具上下来");
        msg(sender, "§7/oride eject <实体> — 让实体的所有乘客下来");
        msg(sender, "§7/oride item allow|deny <实体类型> — 加入允许/禁止名单");
        msg(sender, "§7/oride item whitelist|blacklist — 切换白/黑名单模式");
        msg(sender, "§7/oride item remove <实体类型> — 从名单中移除该类型");
        msg(sender, "§7/oride item clear|list — 清空 / 查看搬运设置");
        msg(sender, "§7/oride item mode append|chain — 设置叠放模式");
        msg(sender, "§7/oride item render — 把搬运设置渲染成物品 lore");
        msg(sender, "§7/oride config — 查看配置；autorender on|off、defaultmode append|chain 修改配置");
        msg(sender, "§e实体参数与原版 tp 一致：@选择器 / 玩家名 / UUID。按 §7shift§e 下车，或让头顶乘客下车");
    }

    private static void msg(CommandSender sender, String message) {
        sender.sendMessage("§8[§6RideAll§8]§r " + message);
    }

    private static void msg(CommandSender sender, Component message) {
        sender.sendMessage(Component.empty().append(PREFIX).append(message));
    }

    /** 实体类型列表（每个名字用原版翻译键）。 */
    private static Component typeList(Iterable<String> types) {
        Component out = Component.empty();
        boolean first = true;
        for (String type : types) {
            if (!first) {
                out = out.append(Component.text(", ", NamedTextColor.DARK_GRAY));
            }
            first = false;
            out = out.append(Names.type(type));
        }
        return out;
    }

    private static String modeText(String mode) {
        return CarryData.MODE_CHAIN.equals(mode) ? "chain" : "append";
    }
}

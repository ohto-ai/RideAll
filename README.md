# RideAll

<p align="center">
  <img src="docs/banner.svg" alt="RideAll banner" width="640">
</p>

Paper 1.20.6 插件：让**任意实体骑乘任意实体**，无任何限制 —— 玩家骑玩家、生物骑玩家、display 实体当载具或骑手，全都可以。

另外提供一套**生存搬运物品**机制：管理员把搬运规则写入手持物品的真实 NBT，普通玩家持该物品右键生物即可把它搬到头上，生存/冒险模式成功使用后物品消耗 1 个。

## 指令（`oride.use` 权限，默认 OP；避开原版 `/ride`）

实体参数使用**原版选择器**（与 `/tp` 相同的解析与补全）：`@e/@p/@a/@n/@r/@s` 选择器、玩家名、UUID。

| 指令 | 作用 |
|---|---|
| `/oride append <乘客> <载具>` | 把乘客加入载具的乘客列表 |
| `/oride chain <乘客> <载具>` | 把乘客叠放到载具**乘客链的最顶层** |
| `/oride force_append <乘客> <载具>` | 同 append，但**无法用 shift 脱离**（管理员用） |
| `/oride force_chain <乘客> <载具>` | 同 chain，但**无法用 shift 脱离**（管理员用） |
| `/oride dismount <实体>` | 让实体从载具上下来（支持多选） |
| `/oride eject <实体>` | 让实体的所有乘客下来（支持多选） |
| `/oride item allow <实体类型>` | 加入**允许名单**（如 `cow`、`block_display`） |
| `/oride item deny <实体类型>` | 加入**禁止名单** |
| `/oride item whitelist` / `blacklist` | 切换**白名单/黑名单**模式 |
| `/oride item remove <实体类型>` | 把该类型从两个名单中移除 |
| `/oride item clear` | 清空搬运数据（物品变回普通物品） |
| `/oride item list` | 查看名单与叠放模式 |
| `/oride item mode append\|chain` | 设置叠放模式 |
| `/oride item render` | 把搬运设置渲染成物品 lore |
| `/oride config [autorender on\|off] [defaultmode append\|chain]` | 查看/修改配置 |

示例：

```
/oride append Steve Alex            # Alex 加入 Steve 的乘客列表
/oride chain @e[type=cow] @p        # 牛叠放到该玩家乘客链的最顶层
/oride dismount @e[type=cow]        # 所有牛从载具上下来
```

## 搬运物品

- 数据存放在真实的 `minecraft:custom_data` 组件中（**不是** PDC 的 `PublicBukkitValues`），玩家可以直接用 NBT 编辑器手动编辑：

```
minecraft:custom_data: {rideall: {allow: ["minecraft:cow"], deny: [], list: "allow", mode: "chain"}}
```

- **白名单/黑名单**（`list` 字段，缺省 `allow`）：
  - 白名单（`allow`）：只有允许名单里的类型可以搬运；
  - 黑名单（`deny`）：禁止名单里的类型不能搬运，其余都可以。
  - 渲染 lore 时只渲染生效的那部分：白名单渲染允许名单（"白名单（只允许以下）"），黑名单渲染禁止名单（"黑名单（禁止以下）"）。
- 匹配规则宽容：大小写不敏感，`minecraft:` 前缀可省略（手动写 `cow` 也行）。
- 右键名单允许的生物 → 装载；**创造模式不消耗，生存/冒险模式消耗 1 个物品**（不看是否 OP）。
- 右键不允许的生物 → 提示不能装载，物品不消耗，原版交互不受影响。
- 使用物品**不需要任何权限**，物品本身就是凭证。

## 装载结构（GSit 同款处理）

装载**只操作乘客列表**（`addPassenger`），移动完全交给原版，插件不做任何自定义移动：

```
玩家 ← [隐形区域效果云] ← 乘客（chain 模式叠放到上一个搬运实体的顶部）
```

- **玩家乘客**：插入一层**隐形区域效果云**（GSit `PlayerSitEntity` 同款：空 tick、无粒子、无碰撞、不过期、不可交互）。云的尺寸来自实体类型（高 0.5），原版附件公式 PASSENGER = (0, height, 0) 把乘客抬离载具玩家头部 0.5 格——**乘客的碰撞箱不再覆盖载具玩家的眼睛**，点击容器等交互恢复正常。
- **非玩家乘客**：直接挂载（原版位置），不做特殊处理。
- **`/oride append|chain` 挂到玩家身上时与搬运物品同机制**：玩家乘客同样插云抬升（碰撞箱不盖眼睛）、记录条目，载具玩家按 shift 可整体放下。

- **放下**：按 shift 放下时，只断开最底层的连接，**链式结构原样整体下落**，不弹出、不散开；分离云一并移除。
- **force 挂载**：`force_append`/`force_chain` 挂载的乘客与载具都无法用 shift 脱离（乘客下蹲被禁用、载具 shift 放下跳过 force 条目），只能用 `/oride dismount|eject` 解除（管理员用）。
- **链条转交**：叠在身上的玩家自己下车后，其上方的链条自动转交该玩家管理（再按 shift 即可放下）。

意外掉落自愈：如果被搬运的实体意外脱离（如水下强制下坐），会被视为不再搬运——可以重新抓取，chain 也不会再挂到掉落实体头上。

## shift 下车

- 按 `shift`：**先自己下车**（骑在别人身上时）。
- 自己没有骑乘时按 `shift`：放下**头顶的搬运实体**。

## 配置（config.yml）

| 键 | 默认 | 说明 |
|---|---|---|
| `auto-render` | `true` | 修改物品后自动渲染 lore（`/oride config autorender on\|off` 切换） |
| `default-mode` | `append` | 默认叠放模式（`/oride config defaultmode append\|chain` 切换） |

## 限制说明

- display / marker / interaction 实体没有点击碰撞箱，右键事件不会触发，搬运物品够不到它们（`/oride` 指令可以，通过 UUID 定位）。
- 原版实体选择器无法匹配 display 类实体（原版限制），指令里可用其 UUID。
- 骑手与载具必须在同一个世界；实体不能骑乘自己或形成骑乘循环。

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/RideAll-<version>.jar`，放入 Paper 1.20.6 服务器的 `plugins/` 目录即可。

## 项目结构

```
src/main/java/com/example/rideall/
├── RideAllPlugin.java          # 主类
├── carry/
│   └── CarryManager.java       # 搬运管理（乘客列表 + 隐形展示实体分隔）
├── command/
│   └── RideCommand.java        # /oride 指令（Brigadier 注册）
├── listener/
│   └── CarryListener.java      # 搬运物品交互 + shift 下车
└── util/
    ├── CarryData.java          # custom_data NBT 读写（NMS 隔离在此）
    └── Names.java              # 实体名组件（原版翻译键）
```

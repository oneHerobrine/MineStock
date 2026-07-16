# MineStock 持仓 GUI 功能说明

## 新增功能

### 持仓 GUI 界面
- **命令**: `/mstock pg` 或 `/stock pg` 或 `/st pg`
- **功能**: 以图形界面（箱子 GUI）方式展示玩家的股票持仓

### 功能特点

1. **直观展示**
   - 每只持仓股票显示为一个物品
   - 绿色染料（上涨）或红色染料（下跌）区分盈亏状态
   - 物品名称显示股票代码和名称

2. **详细信息**
   - 持仓数量
   - 持仓成本（平均买入价）
   - 当前价格（实时获取）
   - 持仓盈亏百分比（▲/▼）
   - 浮动盈亏金额

3. **交互功能**
   - 点击任意股票物品：关闭 GUI 并自动执行 `/mstock <股票代码>` 查看该股票详情
   - 点击右下角红色屏障：关闭 GUI

4. **空状态**
   - 如果没有持仓，显示灰色玻璃板提示"暂无持仓"

## 与原有功能的关系

- **原有命令保持不变**: `/mstock portfolio` 和 `/mstock p` 仍然以文字方式显示持仓列表
- **新增 GUI 命令**: `/mstock pg` 是全新的命令，专门用于图形界面展示
- **互不干扰**: 两种方式可以根据玩家喜好自由选择使用

## 技术实现

### 新增文件
1. `src/main/java/dev/onelili/mstock/ui/PortfolioGUI.java`
   - 负责构建和打开持仓 GUI 界面
   - 异步获取所有持仓的实时股价
   - 计算盈亏状态并渲染物品

2. `src/main/java/dev/onelili/mstock/listener/PortfolioGUIListener.java`
   - 监听 GUI 点击事件
   - 处理股票物品点击和关闭按钮
   - 自动清理 viewer 状态

### 修改文件
1. `MStockCommand.java`: 添加 `pg` 子命令处理和 Tab 补全
2. `MineStock.java`: 注册 `PortfolioGUIListener` 事件监听器
3. `plugin.yml`: 更新命令用法说明

## 使用示例

```
/mstock pg          # 打开持仓 GUI
/stock pg           # 同上（别名）
/st pg              # 同上（别名）

/mstock portfolio   # 传统文字方式查看持仓（保持原样）
/mstock p           # 同上（简写）
```

## Tab 补全

输入 `/mstock ` 后按 Tab 键，会显示：
```
buy  sell  portfolio  pg  recommended  reload
```

## 版本信息

- **插件版本**: 1.2.3
- **新增于**: 2026-07-16
- **兼容性**: Bukkit/Spigot/Paper 1.20.1+, Folia

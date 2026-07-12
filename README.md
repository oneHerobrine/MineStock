# MineStock

一个 Minecraft 股票模拟交易插件，让玩家在游戏中使用服务器经济货币买卖真实市场的 A 股和美股。

## 功能概览

- **实时行情** — 接入东方财富（A 股）和公开 API（美股），查询任意支持股票的实时价格与涨跌幅
- **K 线图表** — 在聊天框内渲染块状 K 线图，支持 30 天 / 90 天 / 365 天周期切换，hover 可查看每日 OHLC 数据
- **今日推荐** — 内置精选股票列表，点击代码直达详情页
- **买入 / 卖出** — 聊天框内完成下单流程，含价格确认和手续费计算
- **我的持仓** — 展示所有持仓的股数与均价，点击股票名称可查看详情，一键发起卖出
- **Vault 经济集成** — 所有交易直接扣减/增加服务器金币余额，支持余额不足提示

## 依赖

| 依赖 | 版本 |
|------|------|
| Paper / Spigot | 1.20.1+ |
| Vault | 1.7+ |
| Java | 21+ |

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mstock` | 显示帮助 | `minestock.use` |
| `/mstock <代码>` | 查看股票实时行情与 K 线 | `minestock.use` |
| `/mstock recommended` | 今日推荐列表 | `minestock.use` |
| `/mstock buy <代码> <数量>` | 买入股票 | `minestock.use` |
| `/mstock sell <代码> <数量>` | 卖出股票 | `minestock.use` |
| `/mstock portfolio` | 查看我的持仓 | `minestock.use` |
| `/mstock reload` | 重载配置文件 | `minestock.admin` |

## 支持的股票代码

- **A 股** — 6 位数字代码，如 `600519`（贵州茅台）、`000001`（平安银行）
- **美股** — 英文代码，如 `AAPL`、`TSLA`、`NVDA`

## 数据存储

使用 SQLite（H2 嵌入式数据库）本地持久化玩家持仓，无需额外数据库服务。

## 构建

```bash
mvn clean package
```

## 作者

oneLiLi

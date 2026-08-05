# fanmaiji.top 后台数据自动下载框架(S0-4)

> 状态:**脚本框架,未联真**。需要老板把真实账密填进 `.env` 后才能试跑(AI 永不代输密码)。
> 用途:每天自动下载三类文件 → 丢进 ERP 导入中心:①出货明细(销售)②系统补货记录(出库上架)③商品列表(SKU 别名初始化)。

## 使用

```bash
cd tools/fanmaiji-export
cp .env.example .env        # 填 FANMAIJI_USER / FANMAIJI_PASS
pip3 install playwright pyyaml python-dotenv && python3 -m playwright install chromium
python3 export.py --type sales --month 2026-07     # 出货明细
python3 export.py --type replenish --month 2026-07 # 系统补货记录
python3 export.py --type products                  # 商品列表
python3 export.py --all                            # 三样全下(默认上个月+本月)
```

下载文件落在 `downloads/<YYYY-MM-DD>/`,文件名带类型+区间,可直接喂 ERP 导入中心。

## 首次联真时要做的事(老板配合一次)

1. 填 `.env`
2. 跑 `python3 export.py --probe`:有头模式打开浏览器,人工登录一次,脚本记录登录后各菜单的真实 URL/按钮 → 回填 `config.yaml` 的 selectors 区(目前是根据调研摸底写的占位值,菜单名对但选择器待核)
3. 之后即可无头定时跑(可挂 crontab,见 config.yaml 注释)

## 安全边界

- 账密只存本地 `.env`(已在 .gitignore,永不入库)
- 脚本只做「登录→导出→下载」,不碰后台任何修改类操作
- 查询窗口约束:出货明细后台只能查当月+上月 → 每月至少跑一次,别断档(报告 §B.4)

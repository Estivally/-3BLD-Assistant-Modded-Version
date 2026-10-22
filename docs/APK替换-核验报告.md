# 原 APK 内置图片与联想词替换 —— 核验报告

日期：2026-10-11
产物：`dist/三盲助手-6.2.2-联想图重制版.apk`（38 MB）

---

## 一、做了什么

把原版 `三盲助手_6.2.2.apk`（`zd.cornermemory`）里的**内置联想图**和
**内置联想词表**整体换成最终版本：

| 项 | 来源 | 数量 |
|---|---|---|
| 联想图 | `联想图-审查/图片-最终/` | 576 张 JPEG |
| 联想词 | `联想图-审查/最终清单.tsv` | 576 条 |

原 APK 的内部机制（本次沿用，未改动）：

- 图：`res/drawable/<资源名>.<ext>`，资源名 = 编码小写
  （`AA→a`、`AB→ab` …；特例 `DO→do1`、`IF→if1`）
- 词：只在 `zd/cornermemory/utils/Dictionary.smali` 的 `initDic()` 里，
  键是**双写字母**（`AA`/`BB`…），值即联想词
- 图与词的绑定在 `initPic()` 里走**资源 ID**（`0x7f08xxxx`）。
  资源 ID 由**资源名**钉死（`public.xml`），所以只要名字不变、
  只换文件内容，`initPic()` 无需改动

---

## 二、核验结果（全部通过）

### 1. 图片 —— 逐张 md5 比对

```
最终清单编码数            : 576
图片-最终 能匹配到的图     : 576 / 576
APK 内 res/drawable 图片   : 576 / 576 与最终图 md5 完全一致
内容不同                  : 0
APK 内缺失                : 0
同名多扩展名重复资源       : 0 组
```

### 2. 词表 —— 从成品 APK 的 dex 反解后逐条比对

（不是看源码树，是把 `signed.apk` 的 `classes.dex` 反编译回来读 `initDic`）

```
const-string 值个数        : 1152   （576 键 + 576 值）
Map.put 调用              : 576
解析出词条                : 576
与最终清单一致            : 576
不符 / 缺失               : 0 / 0
```

抽样（dex 实际内容）：

| 编码 | 词 | 编码 | 词 |
|---|---|---|---|
| AA | 圣诞树🎄 | AB | 敖丙 |
| AC | AC米兰 | AD | 阿迪达斯 |
| TW | 台湾 | ZX | 主席 |

### 3. 结构 —— 与原版逐项对齐

```
资源条目数          3950  vs  3950   ✅
ZIP 条目数          1486  vs  1486   ✅
9-patch 数量         112  vs   112   ✅
仅原版有的资源        0 个
仅重建版有的资源      0 个
配置集合不一致        0 个
结论：✅ 结构完全对齐
```

### 4. 签名

```
v1 (JAR signing)          : true
v2 (APK Signature v2)     : true
v3 (APK Signature v3)     : true
```

### 5. 包名 / 版本（与原版一致）

```
package   : zd.cornermemory
versionCode: 115
versionName: 6.2.2
```

---

## 三、注意事项

1. **签名用的是 debug keystore**，与原版的占位证书不同
   → **覆盖安装会失败，必须先卸载原版**。
2. 真机运行本机未验证（`adb devices` 为空），需要人工实测。
3. 回滚材料都在 `D:\Program\dsh\rebuild\`：
   - `src9_orig_lx/`（576 张原图）
   - `Dictionary.smali.orig`（原词表）
   - `original.apk`（原版包）

---

## 四、构建链路（复现用）

```
D:\Program\dsh\rebuild\build_lx.sh
  1) apply_lx_update.py    换图 + 重写 initDic()
  2) apktool b             重打包
  3) compare_9patch.py + verify_rebuild.py   校验
  4) zipalign + apksigner  对齐 + 签名
```

内容级核验（本次新增）：
`verify_lx_content.py` —— 图 md5 + dex 内词表双向比对。

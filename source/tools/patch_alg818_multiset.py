# -*- coding: utf-8 -*-
"""一次性改写 Alg818Activity.java：多套公式集接线 + 薄弱榜/清单 + 查找全源 + 分组序交替。"""
import io
import re
import sys

P = r"D:\Program\dsh\rebuild\src_java\zd\cornermemory\ui\Alg818Activity.java"
s = io.open(P, encoding="utf-8").read()
orig = s
log = []


def rep(old, new, count=1, tag=""):
    global s
    n = s.count(old)
    if n != count:
        print("FAIL %s: 期望 %d 处，实际 %d 处" % (tag, count, n))
        print(repr(old[:120]))
        sys.exit(1)
    s = s.replace(old, new)
    log.append("%s x%d" % (tag, n))


# ---- 1. onActivityResult：加公式集管理页回传分支 ----
rep("""        if (requestCode != REQ_IMPORT) {
            return;
        }""",
    """        if (requestCode == REQ_SETS) {
            boolean imp = data != null
                    && data.getBooleanExtra(Alg818SetListActivity.EXTRA_START_IMPORT, false);
            if (imp) {
                pickImportType();
                return;
            }
            if (SRC_CUSTOM.equals(src)) {
                if (!Alg818CustomSet.exists(this)) {
                    switchSrc(SRC_BUILTIN);
                } else {
                    group = "";
                    SPUtils.getInstance().put(groupKey(), "");
                    loadCases();
                    setCategory(cat);
                    Log.i(TAG, "公式集已切换，重新装载: " + all.size() + " 个 case");
                }
            }
            return;
        }
        if (requestCode != REQ_IMPORT) {
            return;
        }""", tag="onActivityResult")

# ---- 2. applyImported 改为多套 create + 名称 + 上限 ----
rep("""    private void applyImported(String text, String type) {
        if (!Alg818CustomSet.save(this, text)) {
            Toast.makeText(this, str("alg818_import_read_fail"), Toast.LENGTH_SHORT).show();
            return;
        }
        SPUtils.getInstance().put(K_CUSTOM_TYPE, type);
        SPUtils.getInstance().put(K_SRC, SRC_CUSTOM);
        src = SRC_CUSTOM;
        loadCases();
        group = "";
        SPUtils.getInstance().put(K_GROUP + src, "");
        Log.i(TAG, "自定义集导入完成: " + all.size() + " 个 case，分组 " + groups.size());
        setCategory(CAT_CORNER);
        Toast.makeText(this, fmt1(str("alg818_import_saved"), Integer.valueOf(all.size())),
                Toast.LENGTH_SHORT).show();
    }""",
    """    private void applyImported(String text, String type, String name) {
        if (Alg818CustomSet.list(this).size() >= 20) {
            Toast.makeText(this, str("alg818_set_limit"), Toast.LENGTH_LONG).show();
            return;
        }
        Alg818CustomSet.Result r = Alg818CustomSet.parse(text, type);
        Alg818CustomSet.SetInfo si = Alg818CustomSet.create(this, name, type, text, r);
        if (si == null) {
            Toast.makeText(this, str("alg818_import_read_fail"), Toast.LENGTH_SHORT).show();
            return;
        }
        SPUtils.getInstance().put(K_CUSTOM_TYPE, type);
        SPUtils.getInstance().put(K_SRC, SRC_CUSTOM);
        src = SRC_CUSTOM;
        loadCases();
        group = "";
        SPUtils.getInstance().put(groupKey(), "");
        Log.i(TAG, "自定义集导入完成: " + all.size() + " 个 case，分组 " + groups.size()
                + "，套 " + si.id + "「" + si.name + "」");
        setCategory(CAT_CORNER);
        Toast.makeText(this, fmt2(str("alg818_set_saved"), si.name, Integer.valueOf(all.size())),
                Toast.LENGTH_SHORT).show();
    }

    /** 从文件名猜一个公式集名字（去掉扩展名，太长截断）。 */
    private String defaultSetName(Uri uri) {
        String name = null;
        android.database.Cursor cur = null;
        try {
            cur = getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cur.getString(idx);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读文件名失败", t);
        } finally {
            if (cur != null) {
                try {
                    cur.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (name == null || name.trim().length() == 0) {
            name = str("alg818_src_custom");
        }
        name = name.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if (name.length() > 24) {
            name = name.substring(0, 24);
        }
        return name;
    }""", tag="applyImported")

# ---- 3. 预览弹窗：名称输入 + 可疑公式提示 ----
rep("""    /** 预览：识别出多少编码 / 公式 / 分组；有问题先摆出来再决定导不导。 */
    private void showImportPreview(final String text, final String type) {
        final Alg818CustomSet.Result r = Alg818CustomSet.parse(text, type);""",
    """    /** 预览：识别出多少编码 / 公式 / 分组；顺便让用户给这一套起个名字。 */
    private void showImportPreview(final String text, final String type, String defName) {
        final Alg818CustomSet.Result r = Alg818CustomSet.parse(text, type);
        final EditText nameEt = new EditText(this);
        nameEt.setSingleLine(true);
        nameEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameEt.setHint(str("alg818_import_name"));
        nameEt.setText(defName == null ? "" : defName);
        nameEt.setSelection(nameEt.getText().length());
        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(20);
        nameBox.setPadding(padH, dp(8), padH, 0);
        TextView nameLab = new TextView(this);
        nameLab.setText(str("alg818_import_name"));
        nameLab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        nameLab.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        nameBox.addView(nameLab);
        nameBox.addView(nameEt, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));""",
    tag="preview-head")

rep("""        appendProblems(sb, r);
        new AlertDialog.Builder(this)
                .setTitle(str("alg818_import_title"))
                .setMessage(sb.toString())
                .setPositiveButton(str("alg818_import_ok"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        applyImported(text, type);
                    }
                })""",
    """        if (r.suspectCount > 0) {
            StringBuilder ex = new StringBuilder();
            for (int i = 0; i < r.suspectExamples.size(); i++) {
                if (i > 0) {
                    ex.append("；");
                }
                ex.append(r.suspectExamples.get(i));
            }
            sb.append('\\n').append(fmt2(str("alg818_import_suspect"),
                    Integer.valueOf(r.suspectCount), ex.toString()));
        }
        appendProblems(sb, r);
        new AlertDialog.Builder(this)
                .setTitle(str("alg818_import_title"))
                .setMessage(sb.toString())
                .setView(nameBox)
                .setPositiveButton(str("alg818_import_ok"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String nm = nameEt.getText() == null ? "" : nameEt.getText().toString();
                        applyImported(text, type, nm);
                    }
                })""", tag="preview-body")

rep("        showImportPreview(text, importType);",
    "        showImportPreview(text, importType, defaultSetName(uri));", tag="preview-call")

# ---- 4. loadCustom：类型优先取当前这一套 ----
rep("        String hint = SPUtils.getInstance().getString(K_CUSTOM_TYPE, Alg818CustomSet.TYPE_AUTO);",
    """        Alg818CustomSet.SetInfo curSet = Alg818CustomSet.current(this);
        String hint = curSet == null || curSet.type == null
                ? SPUtils.getInstance().getString(K_CUSTOM_TYPE, Alg818CustomSet.TYPE_AUTO)
                : curSet.type;""", tag="loadCustom-hint")

# ---- 5. groupKey 助手 ----
rep("    /** 切公式源：内置 818 / 浅梦 202602 / 自定义。三套 case 身份不同，进度各自独立。 */",
    """    /** 分组筛选按「源 + 公式集」分开记：浅梦一套、内置一套、每个自定义集各一套。 */
    private String groupKey() {
        return groupKeyOf(src);
    }

    private String groupKeyOf(String s) {
        if (SRC_CUSTOM.equals(s)) {
            return K_GROUP + SRC_CUSTOM + "_" + Alg818CustomSet.currentId(this);
        }
        return K_GROUP + s;
    }

    /** 切公式源：内置 818 / 浅梦 202602 / 自定义。三套 case 身份不同，进度各自独立。 */""",
    tag="groupKey-helper")

rep("K_GROUP + src", "groupKey()", count=5, tag="groupKey-sites")
rep('K_GROUP + s, ""', 'groupKeyOf(s), ""', tag="groupKey-loadCases")

# ---- 6. 常量 / 字段 ----
rep("    private static final int REQ_IMPORT = 4201;",
    """    private static final int REQ_IMPORT = 4201;
    /** 公式集管理页（多套自定义集） */
    private static final int REQ_SETS = 4202;""", tag="const-reqsets")

rep('    private static final String K_BEST = "alg818_best_";',
    """    private static final String K_BEST = "alg818_best_";
    /** 最优公式所属分组（清单页显示用），键 = alg818_bgrp_<caseKey> */
    private static final String K_BGRP = "alg818_bgrp_";""", tag="const-bgrp")

rep("    private String importType = Alg818CustomSet.TYPE_AUTO;",
    """    private String importType = Alg818CustomSet.TYPE_AUTO;

    /** 公式源行末尾的两枚入口：公式集管理 / 浅梦说明 */
    private TextView tvSets;
    private TextView tvReadme;""", tag="fields")

# ---- 7. bindViews / bindEvents / updateTabs ----
rep('        srcCustom = (TextView) find("alg818_src_custom");',
    """        srcCustom = (TextView) find("alg818_src_custom");
        tvSets = (TextView) find("alg818_src_sets");
        tvReadme = (TextView) find("alg818_readme");""", tag="bindViews")

rep("""        bindClick("alg818_src_import", new Runnable() {
            @Override
            public void run() {
                pickImportType();
            }
        });""",
    """        bindClick("alg818_src_import", new Runnable() {
            @Override
            public void run() {
                pickImportType();
            }
        });
        bindClick("alg818_src_sets", new Runnable() {
            @Override
            public void run() {
                openSets();
            }
        });
        bindClick("alg818_readme", new Runnable() {
            @Override
            public void run() {
                openReadme();
            }
        });""", tag="bindEvents")

rep("        styleTab(srcCustom, SRC_CUSTOM.equals(src));",
    """        styleTab(srcCustom, SRC_CUSTOM.equals(src));
        if (tvReadme != null) {
            // 说明讲的是浅梦那两套 Excel，内置/自定义源下没有意义
            tvReadme.setVisibility(SRC_QM.equals(src) ? View.VISIBLE : View.GONE);
        }""", tag="updateTabs-readme")

# ---- 8. 两个入口方法 ----
rep("    // ---------------------------------------------------------------- 自定义集导入",
    """    // ---------------------------------------------------------------- 公式集管理 / 说明

    /** 公式集管理页：多套自定义集（命名 / 切换 / 删除）。 */
    private void openSets() {
        try {
            startActivityForResult(new Intent(this, Alg818SetListActivity.class), REQ_SETS);
        } catch (Throwable t) {
            Log.e(TAG, "打开公式集管理失败", t);
        }
    }

    /** 浅梦公式集说明（两份 Excel 的「说明」sheet + 各分页整页备注）。 */
    private void openReadme() {
        try {
            startActivity(new Intent(this, QmReadmeActivity.class));
        } catch (Throwable t) {
            Log.e(TAG, "打开说明页失败", t);
        }
    }

    // ---------------------------------------------------------------- 自定义集导入""",
    tag="open-methods")

# ---- 9. 最优公式记分组 ----
rep("""    /** 把 alg 选为该 case 的最优；再点一次同一条则取消 */
    private void toggleBest(String key, String alg) {
        SPUtils sp = SPUtils.getInstance();
        String cur = sp.getString(K_BEST + key, "");
        if (alg.equals(cur)) {
            sp.remove(K_BEST + key);
            Toast.makeText(this, str("alg818_best_clear"), Toast.LENGTH_SHORT).show();
            Log.i(TAG, "取消最优 " + key);
        } else {
            sp.put(K_BEST + key, alg);
            Toast.makeText(this, str("alg818_best_set"), Toast.LENGTH_SHORT).show();
            Log.i(TAG, "最优 " + key + " = " + alg);
        }
        renderAlgs();
    }""",
    """    /** 把 alg 选为该 case 的最优；再点一次同一条则取消。顺手记下所属分组，清单页要显示。 */
    private void toggleBest(Case c, String alg) {
        String key = c.key;
        SPUtils sp = SPUtils.getInstance();
        String cur = sp.getString(K_BEST + key, "");
        if (alg.equals(cur)) {
            sp.remove(K_BEST + key);
            sp.remove(K_BGRP + key);
            Toast.makeText(this, str("alg818_best_clear"), Toast.LENGTH_SHORT).show();
            Log.i(TAG, "取消最优 " + key);
        } else {
            sp.put(K_BEST + key, alg);
            if (c.cat != null && c.cat.length() > 0) {
                sp.put(K_BGRP + key, c.cat);
            } else {
                sp.remove(K_BGRP + key);
            }
            Toast.makeText(this, str("alg818_best_set"), Toast.LENGTH_SHORT).show();
            Log.i(TAG, "最优 " + key + " = " + alg + "（分组 " + (c.cat == null ? "" : c.cat) + "）");
        }
        renderAlgs();
    }""", tag="toggleBest")

rep("                toggleBest(c.key, alg);", "                toggleBest(c, alg);", tag="toggleBest-call")

# ---- 10. 分组序 + 混合也交替 ----
rep("""            });
            return;
        }
        if (cat == CAT_MIXED) {
            interleave(fresh);
            return;
        }""",
    """            });
            if (cat == CAT_MIXED) {
                // 分组序 + 混合：分组顺序保留，但角棱交替（原来角组全排完才排棱组）
                interleave(fresh);
            }
            return;
        }
        if (cat == CAT_MIXED) {
            Collections.sort(fresh, new Comparator<Case>() {
                @Override
                public int compare(Case a, Case b) {
                    return a.code.compareTo(b.code);
                }
            });
            interleave(fresh);
            return;
        }""", tag="sortFresh-group")

rep("""        Comparator<Case> byCode = new Comparator<Case>() {
            @Override
            public int compare(Case a, Case b) {
                return a.code.compareTo(b.code);
            }
        };
        Collections.sort(cs, byCode);
        Collections.sort(es, byCode);
        fresh.clear();""",
    """        fresh.clear();""", tag="interleave-nosort")

rep("    /** 角棱交替：角1 棱1 角2 棱2 …（各自先按编码排好） */",
    "    /** 角棱交替：角1 棱1 角2 棱2 …（按传进来的顺序，调用方先排好） */", tag="interleave-doc")

# ---- 11. findCase 认大写前缀 ----
rep("""    private Case findCase(String q) {
        Case hit = byKey.get(q);
        if (hit != null) {
            return hit;
        }""",
    """    private Case findCase(String q) {
        Case hit = byKey.get(q);
        if (hit == null && q.length() > 1 && (q.charAt(0) == 'Q' || q.charAt(0) == 'C')) {
            // 内部键是小写前缀（qCAD / cCAD），用户可能输成大写
            hit = byKey.get(Character.toLowerCase(q.charAt(0)) + q.substring(1));
        }
        if (hit != null) {
            return hit;
        }""", tag="findCase-upper")

# ---- 12. locate：三个源全搜 ----
m = re.search(r"    private void locate\(String raw\) \{.*?\n    \}\n", s, re.S)
if not m:
    print("FAIL locate: 没匹配到方法体")
    sys.exit(1)
new_locate = """    private void locate(String raw) {
        String q = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.US)
                .replace(" ", "").replace("-", "");
        if (q.length() == 0) {
            return;
        }
        // 三个源依次找：先当前源，再内置 / 浅梦 / 自定义；找到就切过去把卡放队首
        String start = src;
        String[] orderTry = {start, SRC_BUILTIN, SRC_QM, SRC_CUSTOM};
        for (int i = 0; i < orderTry.length; i++) {
            String s = orderTry[i];
            if (SRC_CUSTOM.equals(s) && !Alg818CustomSet.exists(this)) {
                continue;
            }
            if (!s.equals(src)) {
                switchSrc(s);
            }
            Case hit = findCase(q);
            if (hit != null) {
                Log.i(TAG, "查找到 " + hit.key + "（" + typeName(hit) + " " + hit.code + "）@ " + s);
                focusCase(hit);
                return;
            }
        }
        if (!start.equals(src)) {
            switchSrc(start);
        }
        Toast.makeText(this, fmt1(str("alg818_find_none"), q), Toast.LENGTH_SHORT).show();
        Log.i(TAG, "查找未命中: " + q + "（内置 / 浅梦 / 自定义都试过）");
    }
"""
s = s[:m.start()] + new_locate + s[m.end():]
log.append("locate x1")

io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("OK 共 %d 步: %s" % (len(log), ", ".join(log)))
print("字节 %d -> %d" % (len(orig.encode("utf-8")), len(s.encode("utf-8"))))

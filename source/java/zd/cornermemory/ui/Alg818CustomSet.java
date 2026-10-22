package zd.cornermemory.ui;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import zd.cornermemory.utils.SPUtils;

/**
 * opt25：818 自定义公式集（解析 / 校验 / 存储）。
 *
 * <p><b>文件格式</b>：纯文本，一行一条公式，列顺序固定
 * {@code 分组 \t 编码 \t 公式 \t 交换子 \t 起手}，第 6 列可选「类型」（角 / 棱）。
 * 分隔符自动识别制表符、半角逗号、全角逗号；首行若是表头（含「编码」「公式」「分组」）自动跳过。
 * 同一编码写多行就是多条候选公式，第一条记为「荐」。
 *
 * <p><b>类型判定</b>：第 6 列 &gt; 导入时手选的类型 &gt; 自动（分组名含 UFR/角 → 角块，
 * 含 UF/棱 → 棱块；3 字母编码看缓冲位 J=角 / G=棱）。判不出来就报错，不猜。
 *
 * <p>存到 {@code getFilesDir()/alg818_sets/<id>.txt}，一套一个文件，原文落地，不进 SP
 * （SP 只存 JSON 索引 {@code alg818_sets_index} 与当前套 {@code alg818_cur_set}）。
 * 旧版单套 {@code alg818_custom.txt} 会在首次读取时自动迁移成第一套。
 */
public final class Alg818CustomSet {

    private static final String TAG = "Alg818Set";

    /** 旧版单套文件名（仅用于迁移到多套目录） */
    public static final String FILE = "alg818_custom.txt";

    /** 多套目录（在 filesDir 下） */
    public static final String DIR = "alg818_sets";

    /** 索引（JSON 数组）与当前套 id 的 SP 键 */
    private static final String K_INDEX = "alg818_sets_index";
    private static final String K_CUR = "alg818_cur_set";

    /** 上限：文件 2MB / 2 万行 */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 20000;

    public static final String TYPE_CORNER = "C";
    public static final String TYPE_EDGE = "E";
    public static final String TYPE_AUTO = "M";

    private static final String GROUP_NONE = "未分组";

    /** 一套公式集的元信息（存在 SP 索引里） */
    public static final class SetInfo {
        public String id = "";
        public String name = "";
        public String type = TYPE_AUTO;
        public int cases;
        public int corner;
        public int edge;
        public int groups;
        public int bytes;
        public long at;

        public String typeName() {
            if (TYPE_CORNER.equals(type)) {
                return "角块";
            }
            if (TYPE_EDGE.equals(type)) {
                return "棱块";
            }
            return "混合";
        }

        public String brief() {
            return typeName() + " · " + cases + " 个编码 · " + groups + " 个分组";
        }
    }

    /** 一个编码（同一编码的多行公式合并到这里） */
    public static final class Item {
        public final String type;
        public final String code;
        public final String group;
        public String comm = "";
        public String hand = "";
        public final List<String> algs = new ArrayList<String>();

        Item(String type, String code, String group) {
            this.type = type;
            this.code = code;
            this.group = group;
        }

        public boolean isCorner() {
            return TYPE_CORNER.equals(type);
        }
    }

    /** 解析结果 */
    public static final class Result {
        public final List<Item> items = new ArrayList<Item>();
        public final List<String> groups = new ArrayList<String>();
        public final List<String> errors = new ArrayList<String>();
        /** 有效公式行数 */
        public int rows;
        /** 空公式被跳过的行数 */
        public int skipped;
        /** 完全重复（类型+编码+公式）丢弃的条数 */
        public int duplicated;
        public int formulaCount;
        public int cornerCount;
        public int edgeCount;
        /** 含可疑记号（不在 UDLRFBMESxyz 里）的公式条数 */
        public int suspectCount;
        /** 可疑公式的前几条示例 */
        public final List<String> suspectExamples = new ArrayList<String>();

        public boolean ok() {
            return !items.isEmpty();
        }

        /** 给日志/对话框用的第一行摘要 */
        public String brief() {
            return items.size() + " 个编码 / " + formulaCount + " 条公式 / "
                    + groups.size() + " 个分组（角 " + cornerCount + " · 棱 " + edgeCount + "）";
        }
    }

    private Alg818CustomSet() {
    }

    // ------------------------------------------------------------------ 解析

    public static Result parse(String text, String typeHint) {
        Result r = new Result();
        if (text == null || text.trim().length() == 0) {
            r.errors.add("文件是空的");
            return r;
        }
        String t = text;
        if (t.charAt(0) == '\uFEFF') {
            t = t.substring(1);
        }
        String hint = normType(typeHint);
        if (hint == null) {
            hint = TYPE_AUTO;
        }
        String[] lines = t.split("\r\n|\n|\r");
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        Set<String> seen = new LinkedHashSet<String>();
        Set<String> groupSet = new LinkedHashSet<String>();
        boolean headerChecked = false;

        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line == null) {
                continue;
            }
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            if (!headerChecked) {
                headerChecked = true;
                if (looksLikeHeader(line)) {
                    Log.i(TAG, "跳过表头: " + line);
                    continue;
                }
            }
            if (r.rows >= MAX_ROWS) {
                r.errors.add("超过 " + MAX_ROWS + " 行，后面的忽略");
                break;
            }
            String[] f = splitLine(line);
            if (f == null) {
                r.errors.add("第 " + (n + 1) + " 行：没找到制表符或逗号，无法分列");
                continue;
            }
            if (f.length < 3) {
                r.errors.add("第 " + (n + 1) + " 行：只有 " + f.length + " 列，至少要 分组/编码/公式 三列");
                continue;
            }
            String group = clean(f[0]);
            String code = normCode(f[1]);
            String alg = normAlg(f[2]);
            String comm = f.length > 3 ? cleanExtra(f[3]) : "";
            String hand = f.length > 4 ? cleanExtra(f[4]) : "";
            String explicit = f.length > 5 ? clean(f[5]) : "";

            if (code.length() == 0) {
                r.errors.add("第 " + (n + 1) + " 行：编码为空");
                continue;
            }
            if (alg.length() == 0) {
                r.skipped++;
                continue;
            }
            if (code.length() < 2 || code.length() > 3) {
                r.errors.add("第 " + (n + 1) + " 行：编码「" + code + "」长度应为 2-3");
                continue;
            }
            if (!isLetters(code)) {
                r.errors.add("第 " + (n + 1) + " 行：编码「" + code + "」含非法字符");
                continue;
            }
            String type = resolveType(explicit, group, code, hint);
            if (type == null) {
                r.errors.add("第 " + (n + 1) + " 行：判断不出「" + code
                        + "」是角块还是棱块（加第 6 列类型，或导入时手选类型）");
                continue;
            }
            if (group.length() == 0) {
                group = GROUP_NONE;
            }
            if (!seen.add(type + "|" + code + "|" + alg)) {
                r.duplicated++;
                continue;
            }
            groupSet.add(group);
            String key = type + "|" + code;
            Item it = map.get(key);
            if (it == null) {
                it = new Item(type, code, group);
                map.put(key, it);
            }
            if (it.comm.length() == 0 && comm.length() > 0) {
                it.comm = comm;
            }
            if (it.hand.length() == 0 && hand.length() > 0) {
                it.hand = hand;
            }
            it.algs.add(alg);
            r.rows++;
            r.formulaCount++;
            if (suspectAlg(alg)) {
                r.suspectCount++;
                if (r.suspectExamples.size() < 3) {
                    r.suspectExamples.add(code + " " + alg);
                }
            }
        }

        r.items.addAll(map.values());
        r.groups.addAll(groupSet);
        for (int i = 0; i < r.items.size(); i++) {
            if (r.items.get(i).isCorner()) {
                r.cornerCount++;
            } else {
                r.edgeCount++;
            }
        }
        return r;
    }

    private static String[] splitLine(String line) {
        if (line.indexOf('\t') >= 0) {
            return normalize(line.split("\t", -1));
        }
        if (line.indexOf('，') >= 0) {
            return normalize(splitCsv(line, '，'));
        }
        if (line.indexOf(',') >= 0) {
            return normalize(splitCsv(line, ','));
        }
        return null;
    }

    /** 逗号分列：认 "..." 引号（Excel 导出的交换子里常有逗号），"" 表示一个引号 */
    private static String[] splitCsv(String line, char sep) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
                continue;
            }
            if (c == sep && !inQuote) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[out.size()]);
    }

    /** 交换子里的逗号（如 [R' D' R,U']）没加引号时会多分列，这里把多出来的并回交换子。 */
    private static String[] normalize(String[] f) {
        if (f.length <= 4) {
            return f;
        }
        String last = clean(f[f.length - 1]);
        String prev = f.length > 5 ? clean(f[f.length - 2]) : "";
        if (f.length == 5) {
            if (isHandLike(last)) {
                return f;
            }
            return new String[]{f[0], f[1], f[2], join(f, 3, 5), ""};
        }
        if (normType(last) != null) {
            return new String[]{f[0], f[1], f[2], join(f, 3, f.length - 2), prev, last};
        }
        if (isHandLike(last)) {
            return new String[]{f[0], f[1], f[2], join(f, 3, f.length - 1), last, ""};
        }
        return new String[]{f[0], f[1], f[2], join(f, 3, f.length), "", ""};
    }

    private static String join(String[] f, int from, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < toExclusive && i < f.length; i++) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(f[i]);
        }
        return sb.toString();
    }

    /** 起手列长这样：中/上/U/R'/空（短、无逗号） */
    private static boolean isHandLike(String s) {
        String v = cleanExtra(s);
        if (v.length() == 0) {
            return true;
        }
        if (v.length() > 6 || v.indexOf(',') >= 0) {
            return false;
        }
        return true;
    }

    private static boolean looksLikeHeader(String line) {
        return line.contains("编码") || line.contains("公式")
                || line.contains("分组") || line.contains("交换子");
    }

    private static String resolveType(String explicit, String group, String code, String hint) {
        String e = normType(explicit);
        if (e != null && !TYPE_AUTO.equals(e)) {
            return e;
        }
        if (TYPE_CORNER.equals(hint) || TYPE_EDGE.equals(hint)) {
            return hint;
        }
        // 自动：分组名优先（UFR 必须先于 UF 判断，否则「UFR总表」会被当成棱块）
        String g = group == null ? "" : group.toUpperCase();
        if (g.contains("UFR") || g.contains("角")) {
            return TYPE_CORNER;
        }
        if (g.contains("UF") || g.contains("棱")) {
            return TYPE_EDGE;
        }
        if (code.length() == 3) {
            char b = code.charAt(0);
            if (b == 'J') {
                return TYPE_CORNER;
            }
            if (b == 'G') {
                return TYPE_EDGE;
            }
        }
        return null;
    }

    private static String normType(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toUpperCase();
        if (v.length() == 0) {
            return null;
        }
        if (v.equals("C") || v.equals("角") || v.equals("角块") || v.equals("角塊")
                || v.contains("CORNER")) {
            return TYPE_CORNER;
        }
        if (v.equals("E") || v.equals("棱") || v.equals("棱块") || v.equals("棱塊")
                || v.contains("EDGE")) {
            return TYPE_EDGE;
        }
        if (v.equals("M") || v.contains("混合") || v.contains("MIX") || v.contains("自动")) {
            return TYPE_AUTO;
        }
        return null;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        if (v.length() > 1 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
            v = v.substring(1);
        }
        if (v.length() > 1) {
            char last = v.charAt(v.length() - 1);
            if (last == '"' || last == '\'') {
                v = v.substring(0, v.length() - 1);
            }
        }
        return v.trim();
    }

    /** 交换子 / 起手：占位符一律当空 */
    private static String cleanExtra(String s) {
        String v = clean(s);
        if (v.length() == 0) {
            return "";
        }
        String low = v.toLowerCase();
        if (v.equals("(空)") || v.equals("（空）") || v.equals("-") || v.equals("—")
                || v.equals("无") || low.startsWith("not found") || low.equals("null")) {
            return "";
        }
        return v;
    }

    /** 编码：大写、去掉空格 / 连字符 / 间隔点 */
    private static String normCode(String s) {
        String v = clean(s).toUpperCase();
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '-' || c == '_' || c == '\u00b7' || c == '\u3000') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 公式：压紧多余空白（全角空格也压），去掉首尾引号 */
    private static String normAlg(String s) {
        String v = clean(s).replace('\u3000', ' ');
        v = v.replaceAll("\\s+", " ").trim();
        return v;
    }

    private static boolean isLetters(String code) {
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }

    /** 公式里是否含可疑记号（只做提示，不拦导入） */
    public static boolean suspectAlg(String alg) {
        if (alg == null || alg.length() == 0) {
            return false;
        }
        String[] toks = alg.trim().split("\\s+");
        for (int i = 0; i < toks.length; i++) {
            if (suspectToken(toks[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean suspectToken(String t) {
        if (t == null || t.length() == 0) {
            return false;
        }
        if (t.length() > 3) {
            return true;
        }
        char c = t.charAt(0);
        if ("UDLRFBMESxyzrludfb".indexOf(c) < 0) {
            return true;
        }
        for (int i = 1; i < t.length(); i++) {
            char d = t.charAt(i);
            if (d != '2' && d != '\'' && d != 'w') {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 存储（多套）

    public static boolean exists(Context ctx) {
        return !list(ctx).isEmpty();
    }

    /** 列出全部公式集（按加入顺序）；首次调用会把旧版单套迁移过来。 */
    public static List<SetInfo> list(Context ctx) {
        List<SetInfo> out = new ArrayList<SetInfo>();
        if (ctx == null) {
            return out;
        }
        migrateLegacy(ctx);
        JSONArray a = loadIndex(ctx);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) {
                continue;
            }
            SetInfo si = toInfo(o);
            if (si.id.length() == 0) {
                continue;
            }
            File f = fileOf(ctx, si.id);
            if (!f.exists() || f.length() == 0) {
                Log.w(TAG, "公式集文件缺失，跳过: " + si.id + " " + si.name);
                continue;
            }
            out.add(si);
        }
        return out;
    }

    public static SetInfo get(Context ctx, String id) {
        if (ctx == null || id == null || id.length() == 0) {
            return null;
        }
        List<SetInfo> all = list(ctx);
        for (int i = 0; i < all.size(); i++) {
            if (id.equals(all.get(i).id)) {
                return all.get(i);
            }
        }
        return null;
    }

    /** 当前这一套的 id（没有就返回空串） */
    public static String currentId(Context ctx) {
        if (ctx == null) {
            return "";
        }
        String id = SPUtils.getInstance().getString(K_CUR, "");
        if (id == null) {
            id = "";
        }
        if (id.length() > 0 && get(ctx, id) != null) {
            return id;
        }
        List<SetInfo> all = list(ctx);
        if (!all.isEmpty()) {
            String first = all.get(0).id;
            setCurrent(ctx, first);
            return first;
        }
        return "";
    }

    public static void setCurrent(Context ctx, String id) {
        if (ctx == null) {
            return;
        }
        try {
            SPUtils.getInstance().put(K_CUR, id == null ? "" : id);
        } catch (Throwable ignored) {
        }
    }

    public static SetInfo current(Context ctx) {
        return get(ctx, currentId(ctx));
    }

    /** 读某一套的原文 */
    public static String readText(Context ctx, String id) {
        if (ctx == null || id == null || id.length() == 0) {
            return null;
        }
        File f = fileOf(ctx, id);
        if (!f.exists() || f.length() == 0) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return decode(bo.toByteArray());
        } catch (Throwable t) {
            Log.e(TAG, "读取公式集失败", t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 兼容旧调用：读当前这一套 */
    public static String load(Context ctx) {
        return readText(ctx, currentId(ctx));
    }

    /** 新建一套并设为当前 */
    public static SetInfo create(Context ctx, String name, String type, String text, Result r) {
        if (ctx == null || text == null || text.length() == 0) {
            return null;
        }
        if (loadIndex(ctx).length() >= 20) {
            Log.w(TAG, "公式集已达上限 20 套，拒绝新建");
            return null;
        }
        return createInternal(ctx, name, type, text, r);
    }

    /** 真正写盘 + 写索引；刻意不调用 list()/migrateLegacy()，否则迁移旧数据时会无限递归。 */
    private static SetInfo createInternal(Context ctx, String name, String type, String text, Result r) {
        if (ctx == null || text == null || text.length() == 0) {
            return null;
        }
        String id = "s" + System.currentTimeMillis();
        File f = fileOf(ctx, id);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.e(TAG, "写公式集失败", t);
            return null;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
        JSONArray a = loadIndex(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", cleanName(name));
            o.put("type", normType(type) == null ? TYPE_AUTO : type);
            o.put("cases", r == null ? 0 : r.items.size());
            o.put("corner", r == null ? 0 : r.cornerCount);
            o.put("edge", r == null ? 0 : r.edgeCount);
            o.put("groups", r == null ? 0 : r.groups.size());
            o.put("bytes", text.length());
            o.put("at", System.currentTimeMillis());
            a.put(o);
        } catch (Throwable t) {
            Log.e(TAG, "写索引项失败", t);
        }
        saveIndex(ctx, a);
        setCurrent(ctx, id);
        Log.i(TAG, "新建公式集 " + id + " " + cleanName(name) + "（"
                + (r == null ? "?" : r.brief()) + "）");
        SetInfo si = new SetInfo();
        si.id = id;
        si.name = cleanName(name);
        si.type = normType(type) == null ? TYPE_AUTO : type;
        si.cases = r == null ? 0 : r.items.size();
        si.corner = r == null ? 0 : r.cornerCount;
        si.edge = r == null ? 0 : r.edgeCount;
        si.groups = r == null ? 0 : r.groups.size();
        si.bytes = text.length();
        si.at = System.currentTimeMillis();
        return si;
    }

    public static boolean rename(Context ctx, String id, String name) {
        if (ctx == null || id == null) {
            return false;
        }
        JSONArray a = loadIndex(ctx);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) {
                try {
                    o.put("name", cleanName(name));
                } catch (Throwable t) {
                    return false;
                }
                saveIndex(ctx, a);
                Log.i(TAG, "重命名公式集 " + id + " → " + cleanName(name));
                return true;
            }
        }
        return false;
    }

    public static boolean delete(Context ctx, String id) {
        if (ctx == null || id == null) {
            return false;
        }
        JSONArray a = loadIndex(ctx);
        JSONArray b = new JSONArray();
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (id.equals(o.optString("id", ""))) {
                found = true;
                continue;
            }
            b.put(o);
        }
        if (!found) {
            return false;
        }
        saveIndex(ctx, b);
        File f = fileOf(ctx, id);
        if (f.exists()) {
            if (!f.delete()) {
                Log.w(TAG, "公式集文件删不掉: " + f.getName());
            }
        }
        if (id.equals(SPUtils.getInstance().getString(K_CUR, ""))) {
            JSONObject first = b.length() > 0 ? b.optJSONObject(0) : null;
            setCurrent(ctx, first == null ? "" : first.optString("id", ""));
        }
        Log.i(TAG, "删除公式集 " + id + "，剩 " + b.length() + " 套");
        return true;
    }

    private static JSONArray loadIndex(Context ctx) {
        String raw = SPUtils.getInstance().getString(K_INDEX, "");
        if (raw != null && raw.length() > 0) {
            try {
                return new JSONArray(raw);
            } catch (Throwable t) {
                Log.w(TAG, "公式集索引损坏，重建: " + t);
            }
        }
        return new JSONArray();
    }

    private static void saveIndex(Context ctx, JSONArray a) {
        try {
            SPUtils.getInstance().put(K_INDEX, a.toString());
        } catch (Throwable t) {
            Log.e(TAG, "保存公式集索引失败", t);
        }
    }

    private static SetInfo toInfo(JSONObject o) {
        SetInfo si = new SetInfo();
        si.id = o.optString("id", "");
        si.name = o.optString("name", "");
        si.type = o.optString("type", TYPE_AUTO);
        si.cases = o.optInt("cases", 0);
        si.corner = o.optInt("corner", 0);
        si.edge = o.optInt("edge", 0);
        si.groups = o.optInt("groups", 0);
        si.bytes = o.optInt("bytes", 0);
        si.at = o.optLong("at", 0);
        return si;
    }

    private static File dirOf(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "建目录失败: " + d.getAbsolutePath());
        }
        return d;
    }

    private static File fileOf(Context ctx, String id) {
        return new File(dirOf(ctx), id + ".txt");
    }

    /** 旧版单套 alg818_custom.txt → 多套索引的第一套 */
    private static void migrateLegacy(Context ctx) {
        File legacy = new File(ctx.getFilesDir(), FILE);
        if (!legacy.exists() || legacy.length() == 0) {
            return;
        }
        JSONArray a = loadIndex(ctx);
        if (a.length() > 0) {
            File bak = new File(dirOf(ctx), "legacy-" + System.currentTimeMillis() + ".bak");
            if (legacy.renameTo(bak)) {
                Log.i(TAG, "旧单套文件已归档: " + bak.getName());
            }
            return;
        }
        FileInputStream in = null;
        String text = null;
        try {
            in = new FileInputStream(legacy);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            text = decode(bo.toByteArray());
        } catch (Throwable t) {
            Log.e(TAG, "迁移旧单套失败", t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (text == null || text.length() == 0) {
            return;
        }
        String type = SPUtils.getInstance().getString("alg818_custom_type", TYPE_AUTO);
        Result r = parse(text, type);
        SetInfo si = createInternal(ctx, "我的公式集", type, text, r);
        Log.i(TAG, "已迁移旧版单套自定义集 → " + (si == null ? "失败" : si.id));
    }

    private static String cleanName(String s) {
        String n = s == null ? "" : s.trim();
        if (n.length() == 0) {
            return "未命名公式集";
        }
        if (n.length() > 24) {
            n = n.substring(0, 24);
        }
        return n;
    }

    /** 先按 UTF-8 严格解码，失败回落 GBK（Windows 上 Excel 另存的 CSV 多是 GBK）。 */
    public static String decode(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(data)).toString();
        } catch (Throwable t) {
            Log.w(TAG, "UTF-8 解码失败，按 GBK 再试: " + t);
            try {
                return new String(data, "GBK");
            } catch (Throwable t2) {
                return new String(data);
            }
        }
    }
}

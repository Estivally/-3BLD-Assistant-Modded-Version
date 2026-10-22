package zd.cornermemory.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 「批量导入」的解析层：把 4 种来源（Anki .apkg / CSV·TSV / ZIP 图片包 / 文件夹）
 * 统一解析成 <b>一个临时 {@code .lxres}</b>，再交给现有的
 * {@link LxPicImportExportUtils#importCustomAssets} 应用。
 *
 * <p><b>为什么不自己写 apply 逻辑</b>：现有管线已经实现了 hash 去重、与内置词相同则
 * 删除覆盖、图片统一重编码、进度回调、以及导入前的完整性校验。复用它等于零语义漂移 ——
 * 批量导入和「导入资源包」写出来的 SPUtils 必然一致。
 *
 * <p><b>临时包的格式必须严格对齐</b>（读入侧 {@code AssetItem} 没有 imageEntryName 字段，
 * 条目名是从 code 反推的，且校验会检查条目存在）：
 * <pre>
 *   manifest.json                    packageType=lx_custom_assets, version=1
 *   images/&lt;2字母编码&gt;.jpg          仅 hasCustomImage=true 的条目
 * </pre>
 *
 * <p><b>编码归一化</b>：所有来源一律先过 {@link LxCodeUtil#normalize}，保证写进 manifest 的
 * code 是 2 字母大写 —— 否则 SPUtils 的 key 会是 {@code "A_LXPIC"} 而 UI 永远读不到。
 */
public final class BatchImportParser {

    private static final String TAG = "BatchImport";

    public static final String KIND_APKG = "apkg";
    public static final String KIND_CSV = "csv";
    public static final String KIND_ZIP = "zip";
    public static final String KIND_FOLDER = "folder";

    /** 新版 Anki 格式（collection.anki21b 是 zstd 压缩），本工程没有 zstd 解码能力。 */
    public static class NewAnkiFormatException extends Exception {
        public NewAnkiFormatException(String message) {
            super(message);
        }
    }

    /** 解析阶段的分步提示（不定进度）。 */
    public interface StepListener {
        void onStep(String message);
    }

    /** 解析结果（只用于预览与展示，不持久化）。 */
    public static class Result {
        public String sourceLabel = "";
        public int total;
        public int imageCount;
        public int wordCount;
        public int invalidCount;
        public final List<String> invalidSamples = new ArrayList<String>();
        public final List<String> notes = new ArrayList<String>();
        public String lxresPath;
        public long bytes;

        public boolean isEmpty() {
            return total <= 0;
        }
    }

    private BatchImportParser() {
    }

    // ------------------------------------------------------------------ 内部条目

    private static final class Item {
        final String code;
        String word;              // 来源提供的词；null = 来源没给
        byte[] image;             // 来源提供的图（已在内存里）；null = 来源没给
        String preserveImagePath; // 来源没给图时，现有自定义图的路径（流式写进包，不占内存）
        String raw;               // 原始编码串（用于报告）

        Item(String code, String raw) {
            this.code = code;
            this.raw = raw;
        }

        boolean useful() {
            return word != null || image != null;
        }
    }

    // ------------------------------------------------------------------ 正则

    private static final Pattern LX_WORD = Pattern.compile(
            "<div[^>]*class\\s*=\\s*[\"']?lx-word[\"']?[^>]*>(.*?)</div>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_SRC = Pattern.compile(
            "<img[^>]+?src\\s*=\\s*[\"']?([^\"'>\\s]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);

    private static final Set<String> IMAGE_EXT = new HashSet<String>();
    static {
        Collections.addAll(IMAGE_EXT, "jpg", "jpeg", "png", "webp", "gif", "bmp");
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 解析并生成临时 {@code .lxres}。**必须在后台线程调用**（会做磁盘 IO）。
     *
     * @param kind {@link #KIND_APKG} / {@link #KIND_CSV} / {@link #KIND_ZIP} / {@link #KIND_FOLDER}
     * @throws NewAnkiFormatException 检测到新版 Anki 包（调用方应给出「请勾选支持旧版 Anki」的提示）
     */
    public static Result parse(Context ctx, Uri uri, String kind, StepListener cb) throws Exception {
        Result r = new Result();
        Map<String, Item> items = new LinkedHashMap<String, Item>();

        if (KIND_APKG.equals(kind)) {
            r.sourceLabel = "Anki";
            parseApkg(ctx, uri, items, r, cb);
        } else if (KIND_CSV.equals(kind)) {
            r.sourceLabel = "CSV/TSV";
            String text = readText(ctx, uri);
            parseDelimitedText(text, items, r, false);
        } else if (KIND_ZIP.equals(kind)) {
            r.sourceLabel = "ZIP";
            parseZip(ctx, uri, items, r, cb);
        } else if (KIND_FOLDER.equals(kind)) {
            r.sourceLabel = "文件夹";
            parseFolder(ctx, uri, items, r, cb);
        } else {
            throw new IllegalArgumentException("未知来源: " + kind);
        }

        // 丢掉什么都没带的条目（例如 CSV 里只有编码的一行）
        List<Item> useful = new ArrayList<Item>();
        for (Item it : items.values()) {
            if (it.useful()) {
                useful.add(it);
            }
        }
        if (useful.isEmpty()) {
            r.total = 0;
            return r;
        }

        // 统计「来源实际提供了什么」。必须在补齐之前算，这样确认框里的数字
        // 才是来源的真实内容，而不是补齐后的 576。
        r.total = useful.size();
        r.imageCount = 0;
        r.wordCount = 0;
        for (Item it : useful) {
            if (it.image != null) {
                r.imageCount++;
            }
            if (it.word != null) {
                r.wordCount++;
            }
        }

        // ★★ 关键：来源没给的字段必须「保持现状」。
        //   现有 importCustomAssets 的语义是「包里没写自定义词 → 目标值 = 内置词」，
        //   于是只导图片（ZIP/文件夹）会把用户已有的自定义**词**清掉，
        //   只导词（CSV）会把用户已有的自定义**图**清掉 —— 都是静默的数据丢失。
        //   补法：把当前生效值回填进 manifest。
        //   · 词：回填当前生效词；与内置词相同时 hasCustomRemark=false，导入侧自然跳过。
        //   · 图：回填现有自定义图的**路径**（不读进内存），并直接用
        //     LxPicStorageUtils.buildFileDigest 的摘要 —— 与导入侧比对用的摘要完全一致，
        //     所以会被判定为「未变化」直接跳过，不会重编码、不损画质。
        int keepWord = 0;
        int keepImage = 0;
        for (Item it : useful) {
            if (it.word == null) {
                String w = currentWord(it.code);
                if (!TextUtils.isEmpty(w)) {
                    it.word = w;
                    keepWord++;
                }
            }
            if (it.image == null) {
                String path = currentImagePath(it.code);
                if (path != null) {
                    it.preserveImagePath = path;
                    keepImage++;
                }
            }
        }
        Log.i(TAG, "补齐保持现状：词 " + keepWord + " 条 / 图 " + keepImage + " 张");

        if (cb != null) {
            cb.onStep("正在打包 " + useful.size() + " 条…");
        }
        r.lxresPath = writeTempLxres(ctx, useful);
        r.bytes = new File(r.lxresPath).length();
        Log.i(TAG, "解析完成 source=" + r.sourceLabel + " total=" + r.total
                + " image=" + r.imageCount + " word=" + r.wordCount
                + " invalid=" + r.invalidCount + " bytes=" + r.bytes);
        return r;
    }

    /** 当前生效的联想词（自定义覆盖优先，其次内置词）。 */
    private static String currentWord(String code) {
        try {
            String builtin = LxCodeUtil.builtinWord(code);
            SPUtils sp = SPUtils.getInstance();
            if (sp != null) {
                String v = sp.getString(code + "_LX", builtin);
                if (!TextUtils.isEmpty(v)) {
                    return v;
                }
            }
            return builtin;
        } catch (Throwable t) {
            Log.w(TAG, "读当前词失败 " + code, t);
            return null;
        }
    }

    /** 当前自定义图的路径；没有或文件不可读时返回 null。 */
    private static String currentImagePath(String code) {
        try {
            SPUtils sp = SPUtils.getInstance();
            if (sp == null) {
                return null;
            }
            String path = sp.getString(code + "_LXPIC", "");
            if (TextUtils.isEmpty(path)) {
                return null;
            }
            File f = new File(path);
            return (f.isFile() && f.length() > 0) ? path : null;
        } catch (Throwable t) {
            Log.w(TAG, "读当前图路径失败 " + code, t);
            return null;
        }
    }

    // ------------------------------------------------------------------ Anki .apkg

    private static void parseApkg(Context ctx, Uri uri, Map<String, Item> items,
                                  Result r, StepListener cb) throws Exception {
        File src = new File(ctx.getCacheDir(), "anki_src_" + System.currentTimeMillis() + ".apkg");
        File db = new File(ctx.getCacheDir(), "anki_coll_" + System.currentTimeMillis() + ".anki2");
        ZipFile zip = null;
        SQLiteDatabase sq = null;
        try {
            if (cb != null) {
                cb.onStep("正在读取 Anki 包…");
            }
            copyToFile(ctx, uri, src);

            zip = new ZipFile(src);
            Set<String> names = new HashSet<String>();
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                names.add(en.nextElement().getName());
            }

            // ★ 格式判定：新版 Anki（2.1.50+）把真数据放在 collection.anki21b（zstd），
            //   collection.anki2 只是个「请更新至最新版本」的存根。
            if (names.contains("collection.anki21b")) {
                throw new NewAnkiFormatException("collection.anki21b");
            }
            if (!names.contains("collection.anki2")) {
                throw new IOException("不是有效的 Anki 包（缺少 collection.anki2）");
            }

            extractEntry(zip, "collection.anki2", db);
            if (!hasSqliteMagic(db)) {
                throw new IOException("Anki 包里的 collection.anki2 不是 SQLite 数据库");
            }

            sq = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

            // 存根检测：新版格式的 collection.anki2 里只有一条「请更新至…」的笔记
            if (isNewFormatStub(sq)) {
                throw new NewAnkiFormatException("collection.anki2 存根");
            }

            int ver = -1;
            Cursor c0 = null;
            try {
                c0 = sq.rawQuery("SELECT ver FROM col LIMIT 1", null);
                if (c0.moveToFirst()) {
                    ver = c0.getInt(0);
                }
            } finally {
                closeQuietly(c0);
            }
            if (ver != 11) {
                r.notes.add("Anki 库版本为 " + ver + "（期望 11），已尽力解析");
            }

            // media：{"0":"A.jpg", ...} → 反查表 文件名(大写) → zip 条目名
            Map<String, String> mediaByFile = new HashMap<String, String>();
            if (names.contains("media")) {
                String mediaText = readEntryText(zip, "media");
                if (mediaText != null) {
                    try {
                        JSONObject mo = new JSONObject(mediaText);
                        java.util.Iterator<String> kit = mo.keys();
                        while (kit.hasNext()) {
                            String entryName = kit.next();
                            String fileName = mo.optString(entryName, "");
                            if (!TextUtils.isEmpty(fileName)) {
                                mediaByFile.put(basename(fileName).toUpperCase(Locale.US), entryName);
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "media 不是 JSON（可能是新版 protobuf），按无媒体处理", t);
                    }
                }
            }

            if (cb != null) {
                cb.onStep("正在解析笔记…");
            }
            Cursor c = null;
            int notes = 0;
            try {
                c = sq.rawQuery("SELECT flds FROM notes", null);
                while (c.moveToNext()) {
                    notes++;
                    String flds = c.getString(0);
                    if (TextUtils.isEmpty(flds)) {
                        continue;
                    }
                    String[] fields = flds.split("\u001f", -1);
                    addAnkiNote(zip, mediaByFile, fields, items, r);
                }
            } finally {
                closeQuietly(c);
            }
            Log.i(TAG, "Anki 笔记 " + notes + " 条");
            if (notes == 0) {
                r.notes.add("Anki 包里没有任何笔记");
            }
        } finally {
            if (sq != null) {
                try {
                    sq.close();
                } catch (Throwable ignored) {
                }
            }
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
            deleteQuietly(src);
            deleteQuietly(db);
        }
    }

    /**
     * 一条 Anki 笔记 → 一个 Item。
     *
     * <p>启发式（<b>只看内容、不看字段名</b>，所以既支持我们自己的
     * {@code 问答题/正面/背面}，也支持普通 {@code Basic/Front/Back} 牌组）：
     * <ol>
     *   <li>编码：按字段顺序取第一个「剥标签后能归一化成功」的字段（要求原文长度 ≤ 6，避免
     *       把一整句中文误判成编码）</li>
     *   <li>词：在**非编码字段**里取剥标签后最长的那个</li>
     *   <li>图：在**所有字段**里找第一个 {@code <img src>}</li>
     * </ol>
     */
    private static void addAnkiNote(ZipFile zip, Map<String, String> mediaByFile,
                                    String[] fields, Map<String, Item> items, Result r) {
        String code = null;
        int codeIdx = -1;
        for (int i = 0; i < fields.length; i++) {
            String plain = stripHtml(fields[i]);
            if (plain.length() > 6) {
                continue;
            }
            String norm = LxCodeUtil.normalize(plain);
            if (norm != null) {
                code = norm;
                codeIdx = i;
                break;
            }
        }
        if (code == null) {
            // 兜底：编码可能不在文本里，而在图片文件名里
            for (String f : fields) {
                Matcher m = IMG_SRC.matcher(f == null ? "" : f);
                if (m.find()) {
                    String norm = LxCodeUtil.fromFileName(m.group(1));
                    if (norm != null) {
                        code = norm;
                        break;
                    }
                }
            }
        }
        if (code == null) {
            r.invalidCount++;
            if (r.invalidSamples.size() < 20) {
                r.invalidSamples.add(abbreviate(stripHtml(fields.length > 0 ? fields[0] : ""), 12));
            }
            return;
        }

        Item it = obtain(items, code, code);

        // 词：非编码字段里最长的
        String best = null;
        for (int i = 0; i < fields.length; i++) {
            if (i == codeIdx) {
                continue;
            }
            String w = extractWord(fields[i]);
            if (!TextUtils.isEmpty(w) && (best == null || w.length() > best.length())) {
                best = w;
            }
        }
        if (TextUtils.isEmpty(best)) {
            // 整条笔记只有一个字段时，编码字段自身可能就是词（例如 CSV 化的牌组）
            best = null;
        }
        if (!TextUtils.isEmpty(best)) {
            it.word = best;
        }

        // 图：任意字段里的第一个 <img src>
        if (it.image == null) {
            for (String f : fields) {
                Matcher m = IMG_SRC.matcher(f == null ? "" : f);
                if (m.find()) {
                    byte[] bytes = readMedia(zip, mediaByFile, m.group(1));
                    if (bytes != null) {
                        it.image = bytes;
                    }
                    break;
                }
            }
        }
    }

    private static byte[] readMedia(ZipFile zip, Map<String, String> mediaByFile, String src) {
        String file = basename(src);
        String entry = mediaByFile.get(file.toUpperCase(Locale.US));
        if (entry == null) {
            // 没有 media 映射（或映射里没有）时，退化成「条目名 == 文件名」
            entry = file;
        }
        try {
            ZipEntry ze = zip.getEntry(entry);
            if (ze == null || ze.isDirectory()) {
                return null;
            }
            return readEntryBytes(zip, entry);
        } catch (Throwable t) {
            Log.w(TAG, "读图失败 " + entry, t);
            return null;
        }
    }

    private static boolean isNewFormatStub(SQLiteDatabase sq) {
        Cursor c = null;
        try {
            c = sq.rawQuery("SELECT count(*) FROM notes", null);
            if (!c.moveToFirst() || c.getInt(0) != 1) {
                return false;
            }
            closeQuietly(c);
            c = sq.rawQuery("SELECT flds FROM notes LIMIT 1", null);
            if (c.moveToFirst()) {
                String f = c.getString(0);
                return f != null && f.indexOf("请更新至") >= 0;
            }
        } catch (Throwable ignored) {
            // 读不出来就当不是存根，让后面的正常流程报错
        } finally {
            closeQuietly(c);
        }
        return false;
    }

    // ------------------------------------------------------------------ CSV / TSV

    private static void parseDelimitedText(String text, Map<String, Item> items,
                                           Result r, boolean fromZip) {
        if (text == null) {
            return;
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        char delim = detectDelimiter(text);

        boolean firstDataRow = true;
        List<List<String>> rows = parseDelimited(text, delim);
        for (List<String> row : rows) {
            if (row.isEmpty()) {
                continue;
            }
            String first = row.get(0);
            if (first != null && first.startsWith("#")) {
                continue;   // Anki 纯文本导出的 #separator: / #html: / #columns: 等
            }
            if (row.size() < 2) {
                continue;
            }
            String rawCode = first == null ? "" : first.trim();
            if (rawCode.isEmpty()) {
                continue;
            }
            // ★ 表头行要跳过，不能算进「编码非法」。
            //   我们自己导出的 CSV 第一行就是「编码,联想词,图片文件名」，
            //   若不跳过会在确认框里多报一条「编码无法识别」，很误导。
            if (firstDataRow) {
                firstDataRow = false;
                if (isHeaderToken(rawCode)) {
                    Log.i(TAG, "跳过表头行: " + rawCode);
                    continue;
                }
            }
            String code = LxCodeUtil.normalize(rawCode);
            if (code == null) {
                r.invalidCount++;
                if (r.invalidSamples.size() < 20) {
                    r.invalidSamples.add(abbreviate(rawCode, 12));
                }
                continue;
            }
            Item it = obtain(items, code, rawCode);
            String word = extractWord(row.get(1));
            if (!TextUtils.isEmpty(word)) {
                it.word = word;
            }
            // 第 3 列只是「图片文件名」提示；CSV 本身不带图片字节。
            // 若同一批里还有 ZIP/文件夹来源，会在那边按 code 合并进来。
            if (it.image == null && row.size() >= 3 && !TextUtils.isEmpty(row.get(2))) {
                String name = row.get(2).trim();
                String fromName = LxCodeUtil.fromFileName(name);
                if (fromName != null && !fromName.equals(code) && r.notes.size() < 3) {
                    r.notes.add("CSV 第 3 列（图片文件名）与编码不一致，已忽略：" + name);
                }
            }
        }
    }

    /** 常见列名（小写比较）。 */
    private static final Set<String> HEADER_TOKENS = new HashSet<String>();

    static {
        Collections.addAll(HEADER_TOKENS, "code", "id", "no", "index", "word", "words",
                "front", "back", "question", "answer", "letter", "letters",
                "编码", "字母", "序号", "编号", "单词", "联想词", "正面", "背面", "问题", "答案");
    }

    /**
     * 第一行是不是表头？
     *
     * <p>判据刻意收得很紧，避免把「写错的编码」当成表头静默丢掉：
     * <ul>
     *   <li>命中常见列名清单 → 是表头</li>
     *   <li>否则：整个字段**一个 ASCII 字母都没有**（例如「编码」「序号」）→ 是表头</li>
     * </ul>
     * 所以 {@code AX1}、{@code UV} 这种「有字母但非法」的不会被误判，仍会如实报错。
     */
    private static boolean isHeaderToken(String field) {
        if (field == null) {
            return false;
        }
        String s = field.replace("\uFEFF", "").trim();
        if (s.isEmpty()) {
            return false;
        }
        if (HEADER_TOKENS.contains(s.toLowerCase(Locale.US))) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return false;   // 有 ASCII 字母 → 当作（可能写错的）编码，交给上层报错
            }
        }
        return true;
    }

    /** 从文本头几行判断分隔符。 */
    private static char detectDelimiter(String text) {
        int n = Math.min(text.length(), 2000);
        String head = text.substring(0, n);
        String lower = head.toLowerCase(Locale.US);
        if (lower.contains("#separator:tab")) {
            return '\t';
        }
        if (lower.contains("#separator:comma")) {
            return ',';
        }
        if (lower.contains("#separator:semicolon")) {
            return ';';
        }
        // 看第一个非注释行里哪种分隔符出现得多
        String[] lines = head.split("\r\n|\n|\r");
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            int tab = count(line, '\t');
            int comma = count(line, ',');
            int semi = count(line, ';');
            if (tab >= comma && tab >= semi && tab > 0) {
                return '\t';
            }
            if (semi > comma && semi > 0) {
                return ';';
            }
            return ',';
        }
        return '\t';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 支持引号包裹、{@code ""} 转义、字段内换行。 */
    private static List<List<String>> parseDelimited(String text, char delim) {
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i += 2;
                        continue;
                    }
                    inQ = false;
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inQ = true;
                i++;
                continue;
            }
            if (c == delim) {
                row.add(cur.toString());
                cur.setLength(0);
                i++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                row.add(cur.toString());
                cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).trim().isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<String>();
                continue;
            }
            cur.append(c);
            i++;
        }
        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ ZIP

    private static void parseZip(Context ctx, Uri uri, Map<String, Item> items,
                                 Result r, StepListener cb) throws Exception {
        InputStream raw = ctx.getContentResolver().openInputStream(uri);
        if (raw == null) {
            throw new IOException("无法读取所选文件");
        }
        ZipInputStream zin = null;
        try {
            zin = new ZipInputStream(new BufferedInputStream(raw));
            ZipEntry e;
            int seen = 0;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                String base = basename(name);
                if (base.startsWith(".")) {
                    continue;   // .DS_Store / ._xxx
                }
                String ext = extOf(base);
                if ("csv".equals(ext) || "tsv".equals(ext)) {
                    String text = new String(readAll(zin), "UTF-8");
                    parseDelimitedText(text, items, r, true);
                    continue;
                }
                if (!IMAGE_EXT.contains(ext)) {
                    continue;
                }
                String code = LxCodeUtil.fromFileName(base);
                if (code == null) {
                    continue;
                }
                Item it = obtain(items, code, base);
                if (it.image == null) {
                    it.image = readAll(zin);
                    seen++;
                }
                if (cb != null && (seen % 40) == 0) {
                    cb.onStep("已读取 " + seen + " 张图…");
                }
            }
        } finally {
            closeQuietly(zin);
            closeQuietly(raw);
        }
    }

    // ------------------------------------------------------------------ 文件夹（SAF）

    private static void parseFolder(Context ctx, Uri treeUri, Map<String, Item> items,
                                    Result r, StepListener cb) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId);
        ContentResolver cr = ctx.getContentResolver();

        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c == null) {
                throw new IOException("无法读取所选文件夹");
            }
            int seen = 0;
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null || name.startsWith(".")) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    continue;
                }
                String ext = extOf(name);
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                if ("csv".equals(ext) || "tsv".equals(ext)) {
                    String text = new String(readAll(cr.openInputStream(docUri)), "UTF-8");
                    parseDelimitedText(text, items, r, true);
                    continue;
                }
                if (!IMAGE_EXT.contains(ext)) {
                    continue;
                }
                String code = LxCodeUtil.fromFileName(name);
                if (code == null) {
                    continue;
                }
                Item it = obtain(items, code, name);
                if (it.image == null) {
                    it.image = readAll(cr.openInputStream(docUri));
                    seen++;
                    if (cb != null && (seen % 20) == 0) {
                        cb.onStep("已读取 " + seen + " 张图…");
                    }
                }
            }
        } finally {
            closeQuietly(c);
        }
    }

    // ------------------------------------------------------------------ 临时 .lxres

    private static String writeTempLxres(Context ctx, List<Item> useful) throws Exception {
        File out = new File(ctx.getCacheDir(), "batch_" + System.currentTimeMillis() + ".lxres");
        List<Item> sorted = new ArrayList<Item>(useful);
        Collections.sort(sorted, new java.util.Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.code.compareTo(b.code);
            }
        });

        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)));
            JSONArray arr = new JSONArray();
            for (Item it : sorted) {
                String entryName = "images/" + it.code + ".jpg";
                boolean hasImage = it.image != null || it.preserveImagePath != null;
                String hash = "";
                long size = 0;
                if (hasImage) {
                    if (it.image != null) {
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.write(it.image);
                        zos.closeEntry();
                        hash = sha256Hex(it.image);
                        size = it.image.length;
                    } else {
                        // 现有自定义图：**流式**拷进包（不进内存），摘要直接沿用 App 自己的
                        // buildFileDigest —— 与导入侧比对用的算法完全一致，
                        // 于是会被判成「未变化」跳过，既不重编码也不损画质。
                        hash = LxPicStorageUtils.buildFileDigest(it.preserveImagePath);
                        zos.putNextEntry(new ZipEntry(entryName));
                        size = copyFileInto(zos, it.preserveImagePath);
                        zos.closeEntry();
                        if (size <= 0) {
                            hasImage = false;
                            hash = "";
                        }
                    }
                }
                JSONObject o = new JSONObject();
                o.put("code", it.code);
                // 与内置词相同就不必写覆盖，减少无谓的 SPUtils 写入
                String builtin = LxCodeUtil.builtinWord(it.code);
                boolean changed = it.word != null && !it.word.equals(builtin);
                o.put("hasCustomRemark", changed);
                o.put("customRemark", it.word == null ? "" : it.word);
                o.put("hasCustomImage", hasImage);
                o.put("imageEntryName", hasImage ? entryName : "");
                o.put("imageHash", hasImage ? hash : "");
                o.put("imageSize", hasImage ? size : 0);
                arr.put(o);
            }

            JSONObject manifest = new JSONObject();
            manifest.put("packageType", "lx_custom_assets");
            manifest.put("version", 1);
            manifest.put("exportTime", System.currentTimeMillis());
            manifest.put("itemCount", arr.length());
            manifest.put("items", arr);

            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.toString().getBytes("UTF-8"));
            zos.closeEntry();
        } finally {
            closeQuietly(zos);
        }
        return out.getAbsolutePath();
    }

    // ------------------------------------------------------------------ 工具

    private static Item obtain(Map<String, Item> items, String code, String raw) {
        Item it = items.get(code);
        if (it == null) {
            it = new Item(code, raw);
            items.put(code, it);
        }
        return it;
    }

    /** 优先取 {@code <div class="lx-word">} 内文，否则剥掉全部标签。 */
    private static String extractWord(String field) {
        if (field == null) {
            return null;
        }
        Matcher m = LX_WORD.matcher(field);
        if (m.find()) {
            String inner = stripHtml(m.group(1));
            if (!TextUtils.isEmpty(inner)) {
                return inner;
            }
        }
        return stripHtml(field);
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        String s = html.replaceAll("(?i)<br\\s*/?>", " ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        s = s.replace('\u00a0', ' ');
        return s.trim().replaceAll("\\s{2,}", " ");
    }

    private static String basename(String path) {
        if (path == null) {
            return "";
        }
        String s = path;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        return s;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }

    private static String readText(Context ctx, Uri uri) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法读取所选文件");
        }
        try {
            byte[] data = readAll(in);
            return new String(data, "UTF-8");
        } finally {
            closeQuietly(in);
        }
    }

    private static void copyToFile(Context ctx, Uri uri, File dst) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法读取所选文件");
        }
        OutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(dst));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] readEntryBytes(ZipFile zip, String name) throws Exception {
        InputStream in = zip.getInputStream(zip.getEntry(name));
        try {
            return readAll(in);
        } finally {
            closeQuietly(in);
        }
    }

    /** 把文件流式写进 zip 条目，返回写入字节数（失败返回 0）。 */
    private static long copyFileInto(OutputStream out, String path) {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new java.io.FileInputStream(path));
            byte[] buf = new byte[32 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return total;
        } catch (Throwable t) {
            Log.w(TAG, "流式拷贝失败 " + path, t);
            return 0;
        } finally {
            closeQuietly(in);
        }
    }

    private static String readEntryText(ZipFile zip, String name) {
        try {
            return new String(readEntryBytes(zip, name), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void extractEntry(ZipFile zip, String name, File dst) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            throw new IOException("Anki 包缺少 " + name);
        }
        InputStream in = zip.getInputStream(e);
        OutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(dst));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }

    private static boolean hasSqliteMagic(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] head = new byte[16];
            if (raf.read(head) < 16) {
                return false;
            }
            return "SQLite format 3\u0000".equals(new String(head, "US-ASCII"));
        } catch (Throwable t) {
            return false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void closeQuietly(Cursor c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try {
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }
}

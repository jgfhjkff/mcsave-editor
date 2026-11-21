package com.nmsave.editor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单格方块编辑：直接改 LevelDB 里 SubChunkPrefix 记录的位打包索引与调色板。
 * 纯逻辑，无 Android 依赖，桌面可测。
 *
 * 子区块记录格式（Little Endian）：
 *   byte 版本（8 / 9）
 *   byte 层数
 *   [版本 9] byte 子区块 Y 索引（有符号）
 *   每层：byte 头（bit0 = 调色板类型 0=持久化 1=运行时，高 7 位 = 每索引位宽）
 *         4096 个索引，按 32 位字打包，每字 floor(32/位宽) 个
 *         位宽为 3/5/6 时每字高 2 位留空
 *         调色板数量（类型 0 是 int32）
 *         调色板：若干 root NBT（{name, states, val, version}）
 */
public class BlockEdit {

    public static final int DIM_OVERWORLD = 0, DIM_NETHER = 1, DIM_END = 2;

    public static class Result {
        public String message;
        public boolean created;
    }

    public static class Layer {
        public int paletteType;
        public int bits;
        public byte[] indices;
        public List<Nbt.Tag> palette = new ArrayList<>();
    }

    private static class SubChunk {
        int version;
        int yIndex;
        List<Layer> layers = new ArrayList<>();
        byte[] tail = new byte[0];
    }

    // ------------------------------------------------------------------ 键

    public static byte[] subChunkKey(int dim, int cx, int cz, int subY) {
        byte[] k = new byte[dim == DIM_OVERWORLD ? 10 : 14];
        putInt(k, 0, cx);
        putInt(k, 4, cz);
        int p = 8;
        if (dim != DIM_OVERWORLD) {
            putInt(k, 8, dim);
            p = 12;
        }
        k[p] = 0x2f;                 // SubChunkPrefix
        k[p + 1] = (byte) subY;
        return k;
    }

    public static BedrockDb.Entry findKey(List<BedrockDb.Entry> entries, byte[] key) {
        for (BedrockDb.Entry e : entries) {
            if (e.value == null || e.key.length != key.length) continue;
            boolean same = true;
            for (int i = 0; i < key.length; i++) if (e.key[i] != key[i]) { same = false; break; }
            if (same) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------ 状态解析

    /**
     * 解析 "pillar_axis=x; facing_direction=2" 这样的状态文本。
     * true/false 和 *_bit 结尾的键写成 byte，其它数字写 int，其余写字符串。
     */
    public static LinkedHashMap<String, String> parseStates(String text) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (text == null) return map;
        for (String part : text.split(";")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("状态要写成 key=value：" + t);
            map.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return map;
    }

    // ------------------------------------------------------------------ 修改方块

    /**
     * 把 entries 里 (bx,by,bz) 这一格的方块改成 name + states。
     * 找不到子区块时新建一个（该处原本是空气）。返回说明文本。
     */
    public static Result setBlock(List<BedrockDb.Entry> entries, int dim, int bx, int by, int bz,
                                  String name, Map<String, String> states) throws Exception {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("方块名不能为空");
        name = name.trim();
        if (name.indexOf(':') < 0) name = "minecraft:" + name;

        int cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16), subY = Math.floorDiv(by, 16);
        int lx = Math.floorMod(bx, 16), ly = Math.floorMod(by, 16), lz = Math.floorMod(bz, 16);
        int index = (lx << 8) | (lz << 4) | ly;   // YZX 顺序（Y 先变）

        byte[] key = subChunkKey(dim, cx, cz, subY);
        BedrockDb.Entry found = findKey(entries, key);

        Result r = new Result();
        SubChunk sc;
        if (found != null) {
            sc = parseSubChunk(found.value);
            r.created = false;
        } else {
            sc = newSubChunk(subY, donorVersion(entries));
            r.created = true;
        }

        Layer layer = sc.layers.isEmpty() ? null : sc.layers.get(0);
        if (layer == null) {
            layer = emptyLayer(donorVersion(entries));
            sc.layers.add(layer);
        }

        int palIndex = findPalette(layer, name, states);
        boolean added = false;
        if (palIndex < 0) {
            Nbt.Tag entry = newPaletteEntry(layer, name, states);
            if (layer.palette.size() <= maxIndex(layer.bits)) {
                layer.palette.add(entry);
                palIndex = layer.palette.size() - 1;
            } else {
                int newBits = nextBits(layer.bits);
                layer.indices = repack(layer.indices, layer.bits, newBits);
                layer.bits = newBits;
                layer.palette.add(entry);
                palIndex = layer.palette.size() - 1;
            }
            added = true;
        }

        setIndex(layer, index, palIndex);
        byte[] blob = build(sc);

        if (found != null) {
            found.value = blob;
            r.message = ("已写入 " + name + " 到 (" + bx + "," + by + "," + bz + ")"
                    + "，区块 " + cx + "," + cz + "，子区块 Y=" + subY
                    + "，槽位索引 " + index + (added ? "（新增调色板项 " + (layer.palette.size() - 1) + "）" : "（复用调色板项）")
                    + "，层 0 位宽 " + layer.bits);
        } else {
            entries.add(new BedrockDb.Entry(key, blob, 0));
            r.message = ("已新建成子区块：(" + bx + "," + by + "," + bz + ") → " + name
                    + "，区块 " + cx + "," + cz + "，子区块 Y=" + subY
                    + "（该处原本没有方块记录，按空气处理）");
        }
        return r;
    }

    // ------------------------------------------------------------------ 位打包

    private static int blocksPerWord(int bits) { return 32 / bits; }

    private static int wordsFor(int bits) { return (4096 + blocksPerWord(bits) - 1) / blocksPerWord(bits); }

    private static int mask(int bits) { return (1 << bits) - 1; }

    private static int maxIndex(int bits) { return mask(bits); }

    private static int nextBits(int bits) {
        int[] table = {1, 2, 3, 4, 5, 6, 8, 16};
        for (int b : table) if (b > bits) return b;
        throw new IllegalArgumentException("调色板已到 16 位上限，这一层放不下新方块了");
    }

    public static int getIndex(Layer layer, int i) {
        int bpw = blocksPerWord(layer.bits);
        int word = i / bpw;
        int shift = (i % bpw) * layer.bits;
        int w = getInt(layer.indices, word * 4);
        return (w >>> shift) & mask(layer.bits);
    }

    public static void setIndex(Layer layer, int i, int v) {
        int bpw = blocksPerWord(layer.bits);
        int word = i / bpw;
        int shift = (i % bpw) * layer.bits;
        int at = word * 4;
        int w = getInt(layer.indices, at);
        w = (w & ~(mask(layer.bits) << shift)) | ((v & mask(layer.bits)) << shift);
        putInt(layer.indices, at, w);
    }

    static byte[] repack(byte[] old, int oldBits, int newBits) {
        int[] values = new int[4096];
        int bpwOld = blocksPerWord(oldBits);
        for (int i = 0; i < 4096; i++) {
            int word = i / bpwOld, shift = (i % bpwOld) * oldBits;
            values[i] = (getInt(old, word * 4) >>> shift) & mask(oldBits);
        }
        byte[] nu = new byte[wordsFor(newBits) * 4];
        int bpwNew = blocksPerWord(newBits);
        for (int i = 0; i < 4096; i++) {
            int word = i / bpwNew, shift = (i % bpwNew) * newBits;
            int at = word * 4;
            int w = getInt(nu, at);
            w |= (values[i] & mask(newBits)) << shift;
            putInt(nu, at, w);
        }
        return nu;
    }

    // ------------------------------------------------------------------ 解析 / 重建

    /** 只取第一个层，测试与界面预览用。 */
    public static Layer firstLayer(byte[] blob) { return parseSubChunk(blob).layers.get(0); }

    static SubChunk parseSubChunk(byte[] v) {
        SubChunk sc = new SubChunk();
        if (v == null || v.length < 2) throw new IllegalStateException("子区块数据太短");
        sc.version = v[0] & 0xff;
        int layerCount = v[1] & 0xff;
        if (sc.version != 8 && sc.version != 9) {
            throw new IllegalStateException("不支持的子区块版本 " + sc.version + "（只支持 8 / 9）");
        }
        int pos = 2;
        if (sc.version == 9) {
            if (pos >= v.length) throw new IllegalStateException("子区块缺少 Y 索引");
            sc.yIndex = v[pos] & 0xff;
            pos++;
        }
        for (int l = 0; l < layerCount; l++) {
            Layer layer = new Layer();
            int header = v[pos++] & 0xff;
            layer.paletteType = header & 1;
            layer.bits = header >> 1;
            if (layer.bits != 1 && layer.bits != 2 && layer.bits != 3 && layer.bits != 4
                    && layer.bits != 5 && layer.bits != 6 && layer.bits != 8 && layer.bits != 16) {
                throw new IllegalStateException("奇怪的位宽 " + layer.bits);
            }
            int idxLen = wordsFor(layer.bits) * 4;
            if (pos + idxLen > v.length) throw new IllegalStateException("索引区越界");
            layer.indices = new byte[idxLen];
            System.arraycopy(v, pos, layer.indices, 0, idxLen);
            pos += idxLen;

            int count;
            if (layer.paletteType == 0) {
                if (pos + 4 > v.length) throw new IllegalStateException("调色板数量越界");
                count = getInt(v, pos);
                pos += 4;
            } else {
                throw new IllegalStateException("运行时调色板（类型 1）暂不支持");
            }
            if (count < 0 || count > 4096) throw new IllegalStateException("调色板数量异常 " + count);
            for (int i = 0; i < count; i++) {
                int[] end = new int[1];
                Nbt.Tag t = Nbt.parseAny(v, pos, end);
                if (t.type != Nbt.TAG_COMPOUND) throw new IllegalStateException("调色板项不是 compound");
                layer.palette.add(t);
                pos = end[0];
            }
            sc.layers.add(layer);
        }
        if (pos < v.length) {
            sc.tail = new byte[v.length - pos];
            System.arraycopy(v, pos, sc.tail, 0, sc.tail.length);
        }
        return sc;
    }

    static byte[] build(SubChunk sc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(sc.version);
        out.write(sc.layers.size());
        if (sc.version == 9) out.write(sc.yIndex);
        for (Layer layer : sc.layers) {
            out.write(((layer.bits << 1) | layer.paletteType) & 0xff);
            writeBytes(out, layer.indices);
            if (layer.paletteType != 0) throw new IllegalStateException("暂不支持写运行时调色板");
            writeInt(out, layer.palette.size());
            for (Nbt.Tag t : layer.palette) writeBytes(out, Nbt.writeRoot(t));
        }
        if (sc.tail.length > 0) writeBytes(out, sc.tail);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 调色板

    private static Layer emptyLayer(int version) {
        Layer l = new Layer();
        l.paletteType = 0;
        l.bits = 1;
        l.indices = new byte[wordsFor(1) * 4];   // 全 0 = 调色板[0] = 空气
        l.palette.add(airEntry(version));
        return l;
    }

    private static SubChunk newSubChunk(int subY, int version) {
        SubChunk sc = new SubChunk();
        sc.version = 9;
        sc.yIndex = subY;
        sc.layers.add(emptyLayer(version));
        return sc;
    }

    private static Nbt.Tag airEntry(int version) {
        Nbt.Compound c = new Nbt.Compound();
        c.put("name", new Nbt.Tag(Nbt.TAG_STRING, "name", "minecraft:air"));
        c.put("states", new Nbt.Tag(Nbt.TAG_COMPOUND, "states", new Nbt.Compound()));
        c.put("val", new Nbt.Tag(Nbt.TAG_SHORT, "val", (short) 0));
        c.put("version", new Nbt.Tag(Nbt.TAG_INT, "version", version));
        return new Nbt.Tag(Nbt.TAG_COMPOUND, "", c);
    }

    private static int findPalette(Layer layer, String name, Map<String, String> states) {
        for (int i = 0; i < layer.palette.size(); i++) {
            Nbt.Tag t = layer.palette.get(i);
            if (t.type != Nbt.TAG_COMPOUND) continue;
            Nbt.Compound c = (Nbt.Compound) t.value;
            Nbt.Tag n = c.get("name");
            if (n == null || !name.equals(n.value)) continue;
            if (statesMatch(c.get("states"), states)) return i;
        }
        return -1;
    }

    private static boolean statesMatch(Nbt.Tag statesTag, Map<String, String> want) {
        if (want.isEmpty()) return statesTag == null
                || (statesTag.type == Nbt.TAG_COMPOUND && ((Nbt.Compound) statesTag.value).isEmpty());
        if (statesTag == null || statesTag.type != Nbt.TAG_COMPOUND) return false;
        Nbt.Compound have = (Nbt.Compound) statesTag.value;
        if (have.size() != want.size()) return false;
        for (Map.Entry<String, String> en : want.entrySet()) {
            Nbt.Tag t = have.get(en.getKey());
            if (t == null) return false;
            String s = String.valueOf(t.value);
            if (!s.equals(en.getValue())) {
                // 数字类型可能写法不同（1 vs 1.0），做一次宽松比较
                try {
                    double a = Double.parseDouble(s), b = Double.parseDouble(en.getValue());
                    if (a != b) return false;
                } catch (Throwable ignore) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 新建调色板项：状态类型尽量沿用同名方块已有项的类型。 */
    private static Nbt.Tag newPaletteEntry(Layer layer, String name, Map<String, String> states) {
        int version = donorVersion(layer, name);
        Nbt.Compound c = new Nbt.Compound();
        c.put("name", new Nbt.Tag(Nbt.TAG_STRING, "name", name));
        Nbt.Compound s = new Nbt.Compound();
        for (Map.Entry<String, String> en : states.entrySet()) {
            s.put(en.getKey(), stateTag(layer, name, en.getKey(), en.getValue()));
        }
        c.put("states", new Nbt.Tag(Nbt.TAG_COMPOUND, "states", s));
        c.put("val", new Nbt.Tag(Nbt.TAG_SHORT, "val", (short) 0));
        c.put("version", new Nbt.Tag(Nbt.TAG_INT, "version", version));
        return new Nbt.Tag(Nbt.TAG_COMPOUND, "", c);
    }

    private static Nbt.Tag stateTag(Layer layer, String name, String key, String v) {
        // 1) 先找同名方块里同名状态的类型，照抄
        for (Nbt.Tag t : layer.palette) {
            if (t.type != Nbt.TAG_COMPOUND) continue;
            Nbt.Compound c = (Nbt.Compound) t.value;
            Nbt.Tag n = c.get("name");
            if (n == null || !name.equals(n.value)) continue;
            Nbt.Tag st = c.get("states");
            if (st == null || st.type != Nbt.TAG_COMPOUND) continue;
            Nbt.Tag hit = ((Nbt.Compound) st.value).get(key);
            if (hit != null) return coerce(hit.type, key, v);
        }
        // 2) 默认规则
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) || key.endsWith("_bit")
                || key.endsWith("_toggle")) {
            return new Nbt.Tag(Nbt.TAG_BYTE, key,
                    (byte) ("true".equalsIgnoreCase(v) || "1".equals(v) ? 1 : 0));
        }
        try {
            return new Nbt.Tag(Nbt.TAG_INT, key, Integer.parseInt(v));
        } catch (Throwable ignore) {
        }
        return new Nbt.Tag(Nbt.TAG_STRING, key, v);
    }

    private static Nbt.Tag coerce(int type, String key, String v) {
        switch (type) {
            case Nbt.TAG_BYTE: return new Nbt.Tag(type, key, (byte) (("true".equalsIgnoreCase(v) || "1".equals(v)) ? 1 : 0));
            case Nbt.TAG_SHORT: return new Nbt.Tag(type, key, (short) Integer.parseInt(v.trim()));
            case Nbt.TAG_INT: return new Nbt.Tag(type, key, Integer.parseInt(v.trim()));
            case Nbt.TAG_LONG: return new Nbt.Tag(type, key, Long.parseLong(v.trim()));
            default: return new Nbt.Tag(Nbt.TAG_STRING, key, v);
        }
    }

    /** 世界版本号：从已有调色板项里取一个（模型版本不同会影响方块解析）。 */
    private static int donorVersion(List<BedrockDb.Entry> entries) {
        for (BedrockDb.Entry e : entries) {
            if (e.value == null || e.key.length < 9 || e.key[e.key.length - 2] != 0x2f) continue;
            try {
                SubChunk sc = parseSubChunk(e.value);
                int v = versionOf(sc);
                if (v != 0) return v;
            } catch (Throwable ignore) {
            }
        }
        return 0;
    }

    private static int donorVersion(Layer layer, String name) {
        for (Nbt.Tag t : layer.palette) {
            if (t.type != Nbt.TAG_COMPOUND) continue;
            Nbt.Tag v = ((Nbt.Compound) t.value).get("version");
            if (v != null && v.value instanceof Number) {
                int iv = ((Number) v.value).intValue();
                if (iv != 0) return iv;
            }
        }
        return 0;
    }

    private static int versionOf(SubChunk sc) {
        for (Layer l : sc.layers)
            for (Nbt.Tag t : l.palette) {
                if (t.type != Nbt.TAG_COMPOUND) continue;
                Nbt.Tag v = ((Nbt.Compound) t.value).get("version");
                if (v != null && v.value instanceof Number) return ((Number) v.value).intValue();
            }
        return 0;
    }

    // ------------------------------------------------------------------ 字节工具

    static int getInt(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        for (byte x : b) out.write(x & 0xff);
    }

    public static String stateText(Map<String, String> states) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> en : states.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(en.getKey()).append('=').append(en.getValue());
        }
        return sb.toString();
    }

    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}

package com.nmsave.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家数据（~local_player）的字节级精准改写。
 *
 * 网易的玩家 NBT 末尾带有非标准块（scriptData），整体重写会损坏存档；
 * 这里只在目标字段所在字节区间做拼接，其余字节原样保留。
 */
public class PlayerEdit {

    private static final int TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4,
            TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8,
            TAG_LIST = 9, TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    /** 字段在 value 中的 [payloadStart, payloadEnd)。 */
    public static class Span {
        public int type;
        public int start;
        public int end;
    }

    /** 扫描根级字段名 -> 区间（容错：遇到不可解析的字段就停，已找到的仍有效）。 */
    public static java.util.Map<String, Span> scanFields(byte[] d) {
        java.util.Map<String, Span> map = new java.util.LinkedHashMap<>();
        if (d.length < 3) return map;
        int pos = 0;
        int rootType = d[pos++] & 0xFF;
        if (rootType != TAG_COMPOUND) return map;
        int nameLen = (d[pos] & 0xFF) | ((d[pos + 1] & 0xFF) << 8);
        pos += 2 + nameLen;

        while (pos < d.length) {
            int type = d[pos] & 0xFF;
            if (type == 0 || type > 12) break;
            int p = pos + 1;
            if (p + 2 > d.length) break;
            int nlen = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
            p += 2;
            if (p + nlen > d.length) break;
            String name = new String(d, p, nlen, java.nio.charset.StandardCharsets.UTF_8);
            p += nlen;
            int start = p;
            int end;
            try {
                end = skip(d, p, type);
            } catch (Throwable t) {
                break; // 尾部非标准块，忽略
            }
            if (!map.containsKey(name)) {
                Span s = new Span();
                s.type = type;
                s.start = start;
                s.end = end;
                map.put(name, s);
            }
            pos = end;
        }
        return map;
    }

    /** 按名字取根级字段区间（取第一个匹配）。 */
    public static Span find(byte[] d, String key) {
        return scanFields(d).get(key);
    }

    /** 返回 [x, y, z]；找不到返回 null。 */
    public static double[] readPos(byte[] d) {
        try {
            Span s = find(d, "Pos");
            if (s == null || s.type != TAG_LIST) return null;
            int elemType = d[s.start] & 0xFF;
            int n = i32(d, s.start + 1);
            if (n < 3) return null;
            double[] out = new double[3];
            if (elemType == TAG_DOUBLE) {
                for (int i = 0; i < 3; i++) out[i] = Double.longBitsToDouble(i64(d, s.start + 5 + i * 8));
            } else if (elemType == TAG_FLOAT) {
                for (int i = 0; i < 3; i++) out[i] = Float.intBitsToFloat(i32(d, s.start + 5 + i * 4));
            } else {
                return null;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 原地改写坐标（长度不变）。返回是否成功。 */
    public static boolean setPos(byte[] d, double x, double y, double z) {
        try {
            Span s = find(d, "Pos");
            if (s == null || s.type != TAG_LIST) return false;
            int elemType = d[s.start] & 0xFF;
            int n = i32(d, s.start + 1);
            if (n < 3) return false;
            int base = s.start + 5;
            if (elemType == TAG_DOUBLE) {
                putDouble(d, base, x);
                putDouble(d, base + 8, y);
                putDouble(d, base + 16, z);
                return true;
            }
            if (elemType == TAG_FLOAT) {
                putFloat(d, base, (float) x);
                putFloat(d, base + 4, (float) y);
                putFloat(d, base + 8, (float) z);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读手持物品名（Mainhand）。 */
    public static String readMainhand(byte[] d) {
        try {
            Span s = find(d, "Mainhand");
            if (s == null || s.type != TAG_LIST) return null;
            int elemType = d[s.start] & 0xFF;
            int n = i32(d, s.start + 1);
            if (elemType != TAG_COMPOUND || n < 1) return null;
            int[] endPos = {0};
            Nbt.Compound c = Nbt.parseCompoundBody(d, s.start + 5, endPos);
            Nbt.Tag name = c.get("Name");
            Nbt.Tag count = c.get("Count");
            if (name == null) return null;
            String id = String.valueOf(name.value);
            String cnt = count != null ? String.valueOf(((Number) count.value).intValue()) : "1";
            return id + " × " + cnt;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 改写手持物品：以原物品为模板，只改 Name / Count / Damage，再把这一小段拼回原数据。
     * @return 改写后的新 value；失败返回 null
     */
    public static byte[] setMainhand(byte[] d, String itemId, int count, int aux) {
        try {
            Span s = find(d, "Mainhand");
            if (s == null || s.type != TAG_LIST) return null;
            int elemType = d[s.start] & 0xFF;
            int n = i32(d, s.start + 1);

            Nbt.Compound c;
            if (elemType == TAG_COMPOUND && n >= 1) {
                int[] endPos = {0};
                c = Nbt.parseCompoundBody(d, s.start + 5, endPos);
            } else {
                c = new Nbt.Compound(); // 手上没东西：构造最小物品结构
                c.put("Count", new Nbt.Tag(TAG_BYTE, "Count", (byte) count));
                c.put("Damage", new Nbt.Tag(TAG_SHORT, "Damage", (short) aux));
                c.put("wasPickedUp", new Nbt.Tag(TAG_BYTE, "wasPickedUp", (byte) 0));
            }

            String id = itemId.contains(":") ? itemId : "minecraft:" + itemId;
            Nbt.Tag nameTag = c.get("Name");
            if (nameTag != null) {
                nameTag.type = TAG_STRING;
                nameTag.value = id;
            } else {
                c.put("Name", new Nbt.Tag(TAG_STRING, "Name", id));
            }
            setNum(c, "Count", count);
            setNum(c, "Damage", aux);
            if (c.get("wasPickedUp") == null) {
                c.put("wasPickedUp", new Nbt.Tag(TAG_BYTE, "wasPickedUp", (byte) 1));
            }

            byte[] body = Nbt.writeCompoundBody(c);
            byte[] newPayload = new byte[5 + body.length];
            newPayload[0] = (byte) TAG_COMPOUND;
            newPayload[1] = 1; // count = 1
            System.arraycopy(body, 0, newPayload, 5, body.length);

            return splice(d, s.start, s.end, newPayload);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取根级某个字段的完整 Tag（含类型）；不存在返回 null。 */
    public static Nbt.Tag readSection(byte[] d, String key) {
        Span s = find(d, key);
        if (s == null) return null;
        int[] pos = {0};
        Object v = Nbt.readValue(d, s.start, s.type, pos);
        return new Nbt.Tag(s.type, key, v);
    }

    /** 用新的 Tag 替换根级字段（不存在则插在根 compound 末尾）；失败返回 null。 */
    public static byte[] setSection(byte[] d, String key, Nbt.Tag tag) {
        try {
            byte[] nameBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] payload = Nbt.valueBytes(tag.type, tag.value);
            byte[] field = new byte[1 + 2 + nameBytes.length + payload.length];
            int p = 0;
            field[p++] = (byte) tag.type;
            field[p++] = (byte) (nameBytes.length & 0xFF);
            field[p++] = (byte) ((nameBytes.length >> 8) & 0xFF);
            System.arraycopy(nameBytes, 0, field, p, nameBytes.length);
            p += nameBytes.length;
            System.arraycopy(payload, 0, field, p, payload.length);

            Span s = find(d, key);
            if (s != null) {
                int fieldStart = s.start - (1 + 2 + nameBytes.length);
                return splice(d, fieldStart, s.end, field);
            }
            int end = endOfRoot(d);
            if (end < 0) return null;
            return splice(d, end, end, field);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 用 JSON 覆盖玩家的某个背包字段（Inventory / Armor / EnderChestInventory…）。 */
    public static byte[] setInventorySection(byte[] d, String section, String json) {
        Nbt.Tag tmpl = readSection(d, section);
        Nbt.Tag list = NbtJson.fromJson(json == null ? "" : json, tmpl); // 解析失败直接抛给上层显示
        byte[] out = setSection(d, section, list);
        if (out == null) throw new IllegalStateException("写入 " + section + " 失败（字段结构异常）");
        return out;
    }

    /** 根 compound 的 TAG_END 位置；解析失败返回 -1。 */
    private static int endOfRoot(byte[] d) {
        try {
            if (d.length < 3 || (d[0] & 0xFF) != TAG_COMPOUND) return -1;
            int nameLen = (d[1] & 0xFF) | ((d[2] & 0xFF) << 8);
            int pos = 3 + nameLen;
            while (pos < d.length) {
                int type = d[pos] & 0xFF;
                if (type == 0) return pos;
                if (type > 12) return -1;
                int nlen = (d[pos + 1] & 0xFF) | ((d[pos + 2] & 0xFF) << 8);
                pos = skip(d, pos + 3 + nlen, type);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void setNum(Nbt.Compound c, String key, int v) {
        Nbt.Tag t = c.get(key);
        if (t == null) {
            c.put(key, new Nbt.Tag(TAG_SHORT, key, (short) v));
            return;
        }
        switch (t.type) {
            case TAG_BYTE: t.value = (byte) v; break;
            case TAG_SHORT: t.value = (short) v; break;
            case TAG_INT: t.value = v; break;
            case TAG_LONG: t.value = (long) v; break;
            default: t.value = (short) v; t.type = TAG_SHORT;
        }
    }

    private static byte[] splice(byte[] d, int from, int to, byte[] repl) {
        byte[] out = new byte[d.length - (to - from) + repl.length];
        System.arraycopy(d, 0, out, 0, from);
        System.arraycopy(repl, 0, out, from, repl.length);
        System.arraycopy(d, to, out, from + repl.length, d.length - to);
        return out;
    }

    // ------------------------------------------------------------------ 底层
    private static int skip(byte[] d, int pos, int type) {
        switch (type) {
            case TAG_BYTE: return pos + 1;
            case TAG_SHORT: return pos + 2;
            case TAG_INT: case TAG_FLOAT: return pos + 4;
            case TAG_LONG: case TAG_DOUBLE: return pos + 8;
            case TAG_BYTE_ARRAY: {
                int n = i32(d, pos);
                if (n < 0) throw new IllegalStateException("负长度");
                return pos + 4 + n;
            }
            case TAG_STRING: {
                int n = (d[pos] & 0xFF) | ((d[pos + 1] & 0xFF) << 8);
                return pos + 2 + n;
            }
            case TAG_LIST: {
                if (pos + 5 > d.length) throw new IllegalStateException("越界");
                int et = d[pos] & 0xFF;
                int n = i32(d, pos + 1);
                if (n < 0 || n > 200000) throw new IllegalStateException("列表长度异常");
                int p = pos + 5;
                for (int i = 0; i < n; i++) p = skip(d, p, et);
                return p;
            }
            case TAG_COMPOUND: {
                int p = pos;
                while (true) {
                    if (p + 1 > d.length) throw new IllegalStateException("越界");
                    int t = d[p] & 0xFF;
                    if (t == 0) return p + 1;
                    if (p + 3 > d.length) throw new IllegalStateException("越界");
                    int nlen = (d[p + 1] & 0xFF) | ((d[p + 2] & 0xFF) << 8);
                    p += 3 + nlen;
                    p = skip(d, p, t);
                }
            }
            case TAG_INT_ARRAY: {
                int n = i32(d, pos);
                return pos + 4 + 4 * n;
            }
            case TAG_LONG_ARRAY: {
                int n = i32(d, pos);
                return pos + 4 + 8 * n;
            }
            default:
                throw new IllegalStateException("未知类型 " + type);
        }
    }

    private static int i32(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
    }

    private static long i64(byte[] d, int p) {
        long lo = i32(d, p) & 0xFFFFFFFFL;
        long hi = i32(d, p + 4) & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    private static void putFloat(byte[] d, int p, float v) {
        int bits = Float.floatToIntBits(v);
        for (int i = 0; i < 4; i++) d[p + i] = (byte) ((bits >> (8 * i)) & 0xFF);
    }

    private static void putDouble(byte[] d, int p, double v) {
        long bits = Double.doubleToLongBits(v);
        for (int i = 0; i < 8; i++) d[p + i] = (byte) ((bits >> (8 * i)) & 0xFF);
    }
}

package com.nmsave.editor;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 NBT 读写（基岩版 = 小端序 little-endian）。
 * 只覆盖存档编辑需要的类型：byte/short/int/long/float/double/string/list/compound/int[]/long[]/byte[]。
 */
public class Nbt {

    public static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4,
            TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8, TAG_LIST = 9,
            TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    public static class Tag {
        public int type;
        public String name = "";
        public Object value;   // Byte/Short/Integer/Long/Float/Double/String/byte[]/int[]/long[]/ListTag/Compound

        public Tag() {}

        public Tag(int type, String name, Object value) {
            this.type = type;
            this.name = name;
            this.value = value;
        }

        public Compound asCompound() {
            return value instanceof Compound ? (Compound) value : null;
        }

        public String asString() {
            return value instanceof String ? (String) value : null;
        }

        public double asDouble() {
            if (value instanceof Number) return ((Number) value).doubleValue();
            return 0;
        }

        public boolean asBool() {
            return value instanceof Number && ((Number) value).byteValue() != 0;
        }
    }

    public static class Compound extends LinkedHashMap<String, Tag> {
        public Tag getTag(String key) { return get(key); }
    }

    public static class ListTag {
        public int elemType;
        public List<Tag> items = new ArrayList<>();
    }

    // ---------------------------------------------------------------- 解析

    /** 解析以 root compound 开头的 NBT，offset 指向 tagType 字节。 */
    public static Tag parse(byte[] data, int offset) {
        Reader r = new Reader(data, offset);
        int type = r.u8();
        if (type != TAG_COMPOUND) throw new IllegalStateException("root tag 不是 compound: " + type);
        r.skipString(); // root name
        Tag root = new Tag(TAG_COMPOUND, "", readCompoundBody(r));
        return root;
    }

    /** 解析任意 root tag（类型 + 名字 + payload），endOut[0] 返回结束位置。 */
    public static Tag parseAny(byte[] data, int offset, int[] endOut) {
        Reader r = new Reader(data, offset);
        int type = r.u8();
        r.skipString();
        Object v = readPayload(r, type);
        if (endOut != null) endOut[0] = r.pos;
        return new Tag(type, "", v);
    }

    /** 从 data[offset] 开始读一个 compound 体（不含类型/名字），返回读到的位置放在 posOut[0]。 */
    public static Compound parseCompoundBody(byte[] data, int offset, int[] posOut) {
        Reader r = new Reader(data, offset);
        Compound c = readCompoundBody(r);
        posOut[0] = r.pos;
        return c;
    }

    /** 序列化一个 compound 体（尾部带 TAG_END）。 */
    public static byte[] writeCompoundBody(Compound c) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePayload(out, TAG_COMPOUND, c);
        return out.toByteArray();
    }

    private static Compound readCompoundBody(Reader r) {
        Compound c = new Compound();
        while (true) {
            int type = r.u8();
            if (type == TAG_END) break;
            String name = r.str();
            c.put(name, new Tag(type, name, readPayload(r, type)));
        }
        return c;
    }

    private static Object readPayload(Reader r, int type) {
        switch (type) {
            case TAG_BYTE: return (byte) r.u8();
            case TAG_SHORT: { int v = r.u16(); return (short) (v >= 0x8000 ? v - 0x10000 : v); }
            case TAG_INT: return r.i32();
            case TAG_LONG: return r.i64();
            case TAG_FLOAT: return Float.intBitsToFloat(r.i32());
            case TAG_DOUBLE: return Double.longBitsToDouble(r.i64());
            case TAG_BYTE_ARRAY: {
                int n = r.i32();
                r.checkLen(n, 1);
                byte[] b = new byte[n];
                System.arraycopy(r.data, r.pos, b, 0, n);
                r.pos += n;
                return b;
            }
            case TAG_STRING: return r.str();
            case TAG_LIST: {
                ListTag lt = new ListTag();
                lt.elemType = r.u8();
                int n = r.i32();
                // 元素至少 1 字节，长度不可能超过剩余空间；防畸形数据一次性堆爆内存
                if (n < 0 || n > r.remaining()) throw new IllegalStateException("list 长度异常 " + n);
                for (int i = 0; i < n; i++) lt.items.add(new Tag(lt.elemType, "", readPayload(r, lt.elemType)));
                return lt;
            }
            case TAG_COMPOUND: return readCompoundBody(r);
            case TAG_INT_ARRAY: {
                int n = r.i32();
                r.checkLen(n, 4);
                int[] v = new int[n];
                for (int i = 0; i < n; i++) v[i] = r.i32();
                return v;
            }
            case TAG_LONG_ARRAY: {
                int n = r.i32();
                r.checkLen(n, 8);
                long[] v = new long[n];
                for (int i = 0; i < n; i++) v[i] = r.i64();
                return v;
            }
            default:
                throw new IllegalStateException("未知 NBT 类型 " + type + " @" + r.pos);
        }
    }

    private static class Reader {
        final byte[] data;
        int pos;

        Reader(byte[] d, int p) { data = d; pos = p; }

        int u8() { return data[pos++] & 0xFF; }

        int remaining() { return Math.max(0, data.length - pos); }

        /** 数组长度合法性：非负且不超过剩余字节，否则抛错而不是硬分配。 */
        void checkLen(int n, int itemSize) {
            if (n < 0 || (long) n * itemSize > remaining()) {
                throw new IllegalStateException("NBT 数组长度异常 " + n);
            }
        }

        int u16() { int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8); pos += 2; return v; }

        int i32() { int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8) | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24); pos += 4; return v; }

        long i64() {
            long lo = i32() & 0xFFFFFFFFL;
            long hi = i32() & 0xFFFFFFFFL;
            return lo | (hi << 32);
        }

        String str() {
            int n = u16();
            String s = new String(data, pos, n, java.nio.charset.StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        void skipString() {
            int n = u16();   // 注意：不能写成 pos += u16()，否则会先读旧 pos 再赋值
            pos += n;
        }
    }

    // ---------------------------------------------------------------- 序列化

    public static byte[] writeRoot(Tag root) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(root.type);
        writeString(out, root.name == null ? "" : root.name);
        writePayload(out, root.type, root.value);
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(b.length & 0xFF);
        out.write((b.length >> 8) & 0xFF);
        for (byte x : b) out.write(x);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        writeInt(out, (int) (v & 0xFFFFFFFFL));
        writeInt(out, (int) ((v >> 32) & 0xFFFFFFFFL));
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(ByteArrayOutputStream out, int type, Object value) {
        switch (type) {
            case TAG_BYTE: out.write(((Number) value).byteValue() & 0xFF); break;
            case TAG_SHORT: {
                int v = ((Number) value).shortValue() & 0xFFFF;
                out.write(v & 0xFF); out.write((v >> 8) & 0xFF); break;
            }
            case TAG_INT: writeInt(out, ((Number) value).intValue()); break;
            case TAG_LONG: writeLong(out, ((Number) value).longValue()); break;
            case TAG_FLOAT: writeInt(out, Float.floatToIntBits(((Number) value).floatValue())); break;
            case TAG_DOUBLE: writeLong(out, Double.doubleToLongBits(((Number) value).doubleValue())); break;
            case TAG_BYTE_ARRAY: {
                byte[] b = (byte[]) value;
                writeInt(out, b.length);
                for (byte x : b) out.write(x);
                break;
            }
            case TAG_STRING: writeString(out, (String) value); break;
            case TAG_LIST: {
                ListTag lt = (ListTag) value;
                out.write(lt.elemType);
                writeInt(out, lt.items.size());
                for (Tag t : lt.items) writePayload(out, lt.elemType, t.value);
                break;
            }
            case TAG_COMPOUND: {
                Compound c = (Compound) value;
                for (Map.Entry<String, Tag> e : c.entrySet()) {
                    Tag t = e.getValue();
                    out.write(t.type);
                    writeString(out, e.getKey());
                    writePayload(out, t.type, t.value);
                }
                out.write(TAG_END);
                break;
            }
            case TAG_INT_ARRAY: {
                int[] v = (int[]) value;
                writeInt(out, v.length);
                for (int x : v) writeInt(out, x);
                break;
            }
            case TAG_LONG_ARRAY: {
                long[] v = (long[]) value;
                writeInt(out, v.length);
                for (long x : v) writeLong(out, x);
                break;
            }
            default: throw new IllegalStateException("无法序列化类型 " + type);
        }
    }

    /** 读一个 payload（不含类型/名字），posOut 返回读到的位置。 */
    public static Object readValue(byte[] data, int offset, int type, int[] posOut) {
        Reader r = new Reader(data, offset);
        Object v = readPayload(r, type);
        if (posOut != null) posOut[0] = r.pos;
        return v;
    }

    /** 把 payload（不含类型/名字）序列化成字节。 */
    public static byte[] valueBytes(int type, Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePayload(out, type, value);
        return out.toByteArray();
    }

    /** 在 compound 里按路径删除某个键（只删一层）。 */
    public static boolean remove(Compound c, String key) {
        if (c == null) return false;
        return c.remove(key) != null;
    }
}

package com.nmsave.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NBT <-> JSON。
 *
 * 输出（给人看/改）：直接是普通 JSON，数字就是数字、字符串就是字符串。
 *   例：[{"Name":"minecraft:stone","Count":64,"Damage":0,"Slot":0}]
 *
 * 输入：普通 JSON 即可；类型按「模板 + 常见字段名」推断，也可以用显式包装写死类型：
 *   {"__type__":"byte","__value__":64}
 *   {"__type__":"list","__elem__":"compound","__value":[{...}]}
 *   支持 byte/short/int/long/float/double/string/list/compound/byte_array/int_array/long_array
 *
 * 推断规则（没有模板时）：
 *   Count / Slot / wasPickedUp / WasPickedUp -> byte
 *   Damage -> short
 *   其它整数 -> int，小数 -> double
 *   true/false -> byte(1/0)
 */
public class NbtJson {

    private static final int T_BYTE = 1, T_SHORT = 2, T_INT = 3, T_LONG = 4, T_FLOAT = 5,
            T_DOUBLE = 6, T_BYTE_ARRAY = 7, T_STRING = 8, T_LIST = 9, T_COMPOUND = 10,
            T_INT_ARRAY = 11, T_LONG_ARRAY = 12;

    // ---------------------------------------------------------------- 输出

    public static String toJson(Nbt.Tag tag) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, tag, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Nbt.Tag tag, int depth) {
        if (tag == null) { sb.append("null"); return; }
        switch (tag.type) {
            case T_BYTE:
            case T_SHORT:
            case T_INT:
            case T_LONG:
            case T_FLOAT:
            case T_DOUBLE:
                sb.append(numText(tag));
                break;
            case T_STRING:
                quote(sb, String.valueOf(tag.value));
                break;
            case T_COMPOUND: {
                Nbt.Compound c = (Nbt.Compound) tag.value;
                if (c == null || c.isEmpty()) { sb.append("{}"); break; }
                sb.append("{\n");
                int n = 0;
                for (Map.Entry<String, Nbt.Tag> e : c.entrySet()) {
                    indent(sb, depth + 1);
                    quote(sb, e.getKey());
                    sb.append(": ");
                    writeValue(sb, e.getValue(), depth + 1);
                    if (++n < c.size()) sb.append(',');
                    sb.append('\n');
                }
                indent(sb, depth);
                sb.append('}');
                break;
            }
            case T_LIST: {
                Nbt.ListTag lt = (Nbt.ListTag) tag.value;
                if (lt == null || lt.items.isEmpty()) { sb.append("[]"); break; }
                sb.append("[\n");
                for (int i = 0; i < lt.items.size(); i++) {
                    indent(sb, depth + 1);
                    writeValue(sb, lt.items.get(i), depth + 1);
                    if (i < lt.items.size() - 1) sb.append(',');
                    sb.append('\n');
                }
                indent(sb, depth);
                sb.append(']');
                break;
            }
            case T_BYTE_ARRAY: {
                byte[] b = (byte[]) tag.value;
                sb.append('[');
                for (int i = 0; i < b.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(b[i]);
                }
                sb.append(']');
                break;
            }
            case T_INT_ARRAY: {
                int[] v = (int[]) tag.value;
                sb.append('[');
                for (int i = 0; i < v.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(v[i]);
                }
                sb.append(']');
                break;
            }
            case T_LONG_ARRAY: {
                long[] v = (long[]) tag.value;
                sb.append('[');
                for (int i = 0; i < v.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(v[i]);
                }
                sb.append(']');
                break;
            }
            default:
                throw new IllegalStateException("无法输出的 NBT 类型 " + tag.type);
        }
    }

    /** 严格模式：每个值都带 __type__ / __value__，类型完全明确，适合当"合法 NBT 模板"。 */
    public static String toJsonTyped(Nbt.Tag tag) {
        StringBuilder sb = new StringBuilder();
        writeTyped(sb, tag, 0);
        return sb.toString();
    }

    private static String token(int type) {
        switch (type) {
            case 0: return "end";
            case T_BYTE_ARRAY: return "byte_array";
            case T_INT_ARRAY: return "int_array";
            case T_LONG_ARRAY: return "long_array";
            case T_COMPOUND: return "compound";
            case T_LIST: return "list";
            case T_STRING: return "string";
            case T_BYTE: return "byte";
            case T_SHORT: return "short";
            case T_INT: return "int";
            case T_LONG: return "long";
            case T_FLOAT: return "float";
            case T_DOUBLE: return "double";
            default: return "unknown";
        }
    }

    private static void writeTyped(StringBuilder sb, Nbt.Tag tag, int depth) {
        sb.append("{\"__type__\": \"").append(token(tag.type)).append('"');
        if (tag.type == T_LIST) {
            sb.append(", \"__elem__\": \"").append(token(((Nbt.ListTag) tag.value).elemType)).append('"');
        }
        sb.append(", \"__value__\": ");
        switch (tag.type) {
            case T_BYTE:
            case T_SHORT:
            case T_INT:
            case T_LONG:
            case T_FLOAT:
            case T_DOUBLE:
                sb.append(numText(tag));
                break;
            case T_STRING:
                quote(sb, String.valueOf(tag.value));
                break;
            case T_COMPOUND: {
                Nbt.Compound c = (Nbt.Compound) tag.value;
                sb.append('{');
                int n = 0;
                for (Map.Entry<String, Nbt.Tag> e : c.entrySet()) {
                    if (n++ > 0) sb.append(',');
                    sb.append("\n");
                    indent(sb, depth + 1);
                    quote(sb, e.getKey());
                    sb.append(": ");
                    writeTyped(sb, e.getValue(), depth + 1);
                }
                if (!c.isEmpty()) { sb.append("\n"); indent(sb, depth); }
                sb.append('}');
                break;
            }
            case T_LIST: {
                Nbt.ListTag lt = (Nbt.ListTag) tag.value;
                sb.append('[');
                for (int i = 0; i < lt.items.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append("\n");
                    indent(sb, depth + 1);
                    writeTyped(sb, lt.items.get(i), depth + 1);
                }
                if (!lt.items.isEmpty()) { sb.append("\n"); indent(sb, depth); }
                sb.append(']');
                break;
            }
            case T_BYTE_ARRAY: {
                byte[] b = (byte[]) tag.value;
                sb.append('[');
                for (int i = 0; i < b.length; i++) { if (i > 0) sb.append(','); sb.append(b[i]); }
                sb.append(']');
                break;
            }
            case T_INT_ARRAY: {
                int[] v = (int[]) tag.value;
                sb.append('[');
                for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
                sb.append(']');
                break;
            }
            case T_LONG_ARRAY: {
                long[] v = (long[]) tag.value;
                sb.append('[');
                for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
                sb.append(']');
                break;
            }
            default: throw new IllegalStateException("无法输出类型 " + tag.type);
        }
        sb.append('}');
    }

    private static String numText(Nbt.Tag t) {
        if (t.type == T_FLOAT) {
            float f = ((Number) t.value).floatValue();
            if (f == Math.round(f) && Math.abs(f) < 1e7f) return String.valueOf((long) f);
            return String.valueOf(f);
        }
        if (t.type == T_DOUBLE) {
            double d = ((Number) t.value).doubleValue();
            if (d == Math.floor(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(((Number) t.value).longValue());
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- 输入

    /**
     * 把 JSON 文本转成 NBT。
     * @param template 现有同位置 NBT（用来定类型），可为 null
     * @throws IllegalArgumentException 结构不合法时抛出，消息可直接展示给用户
     */
    public static Nbt.Tag fromJson(String text, Nbt.Tag template) {
        Object v;
        try {
            v = Json.parse(text);
        } catch (Throwable t) {
            throw new IllegalArgumentException("JSON 解析失败：" + t.getMessage());
        }
        Nbt.Tag tag = build(v, template, "");
        if (tag.type != T_LIST) {
            throw new IllegalArgumentException("最外层必须是数组（一个列表），现在是 " + typeName(tag.type));
        }
        return tag;
    }

    /** 任意顶层（不要求是数组）：给格子里的 tag/compound 用。 */
    public static Nbt.Tag fromJsonAny(String text, Nbt.Tag template) {
        Object v;
        try {
            v = Json.parse(text);
        } catch (Throwable t) {
            throw new IllegalArgumentException("JSON 解析失败：" + t.getMessage());
        }
        return build(v, template, "");
    }

    private static Nbt.Tag build(Object o, Nbt.Tag tmpl, String key) {
        if (o == null) throw new IllegalArgumentException("不支持 null 值（键 " + key + "）");

        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.containsKey("__type__")) return buildTyped(m, key);
            Nbt.Compound tmplC = (tmpl != null && tmpl.type == T_COMPOUND) ? (Nbt.Compound) tmpl.value : null;
            Nbt.Compound c = new Nbt.Compound();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.startsWith("__")) throw new IllegalArgumentException("不认识的包装字段 " + k);
                Nbt.Tag childTmpl = tmplC == null ? null : tmplC.get(k);
                Nbt.Tag child = build(e.getValue(), childTmpl, k);
                child.name = k;
                c.put(k, child);
            }
            return new Nbt.Tag(T_COMPOUND, key, c);
        }

        if (o instanceof List) {
            List<?> src = (List<?>) o;
            if (tmpl != null && (tmpl.type == T_BYTE_ARRAY || tmpl.type == T_INT_ARRAY || tmpl.type == T_LONG_ARRAY)) {
                return buildArray(src, tmpl.type, key);
            }
            Nbt.ListTag tmplLt = (tmpl != null && tmpl.type == T_LIST) ? (Nbt.ListTag) tmpl.value : null;
            Nbt.Tag merged = tmplLt == null ? null : mergeItems(tmplLt);
            Nbt.ListTag lt = new Nbt.ListTag();
            lt.elemType = tmplLt == null ? 0 : tmplLt.elemType; // 空列表也保留原 elemType(可能是 0)
            for (Object item : src) {
                Nbt.Tag elemTmpl = pickItemTemplate(item, tmplLt, merged);
                Nbt.Tag t = build(item, elemTmpl, "");
                if (lt.elemType == 0) lt.elemType = t.type;
                if (t.type != lt.elemType) {
                    throw new IllegalArgumentException("同一个列表里的元素类型不一致：" + typeName(lt.elemType)
                            + " 与 " + typeName(t.type));
                }
                lt.items.add(t);
            }
            return new Nbt.Tag(T_LIST, key, lt);
        }

        if (o instanceof String) return new Nbt.Tag(T_STRING, key, (String) o);
        if (o instanceof Boolean) return new Nbt.Tag(T_BYTE, key, (byte) (((Boolean) o) ? 1 : 0));
        if (o instanceof Number) {
            int type = (tmpl != null && isNumberType(tmpl.type)) ? tmpl.type : guessNumberType(key);
            return new Nbt.Tag(type, key, convertNumber((Number) o, type));
        }
        throw new IllegalArgumentException("不支持的值类型：" + o.getClass().getSimpleName());
    }

    /** 数组类 NBT（byte[] / int[] / long[]）从 JSON 数字数组构造。 */
    private static Nbt.Tag buildArray(List<?> src, int type, String key) {
        if (type == T_BYTE_ARRAY) {
            byte[] b = new byte[src.size()];
            for (int i = 0; i < src.size(); i++) {
                if (!(src.get(i) instanceof Number)) throw new IllegalArgumentException("byte[] 的元素必须是数字");
                long x = ((Number) src.get(i)).longValue();
                if (x > 127 && x <= 255) x -= 256;
                b[i] = (byte) x;
            }
            return new Nbt.Tag(T_BYTE_ARRAY, key, b);
        }
        if (type == T_INT_ARRAY) {
            int[] v = new int[src.size()];
            for (int i = 0; i < src.size(); i++) {
                if (!(src.get(i) instanceof Number)) throw new IllegalArgumentException("int[] 的元素必须是数字");
                v[i] = ((Number) src.get(i)).intValue();
            }
            return new Nbt.Tag(T_INT_ARRAY, key, v);
        }
        long[] v = new long[src.size()];
        for (int i = 0; i < src.size(); i++) {
            if (!(src.get(i) instanceof Number)) throw new IllegalArgumentException("long[] 的元素必须是数字");
            v[i] = ((Number) src.get(i)).longValue();
        }
        return new Nbt.Tag(T_LONG_ARRAY, key, v);
    }

    /**
     * 给每个元素挑一个"最像"的原模板：
     * 1) 有 Slot 且原列表里同 Slot 的项 → 用它（背包槽位唯一，最可靠）
     * 2) 否则用整表合并出来的模板（键取并集）
     * 3) 再不行用第一项
     */
    private static Nbt.Tag pickItemTemplate(Object item, Nbt.ListTag tmplLt, Nbt.Tag merged) {
        if (tmplLt == null) return null;
        if (item instanceof Map) {
            Object slot = ((Map<?, ?>) item).get("Slot");
            if (slot instanceof Number) {
                int s = ((Number) slot).intValue();
                for (Nbt.Tag it : tmplLt.items) {
                    if (it.type != T_COMPOUND) continue;
                    Nbt.Tag st = ((Nbt.Compound) it.value).get("Slot");
                    if (st != null && st.value instanceof Number && ((Number) st.value).intValue() == s) return it;
                }
            }
        }
        if (merged != null) return merged;
        return tmplLt.items.isEmpty() ? null : tmplLt.items.get(0);
    }

    /** 把列表里所有 compound 项合并成一份模板（键取并集，同键取第一个）。 */
    private static Nbt.Tag mergeItems(Nbt.ListTag lt) {
        Nbt.Compound m = null;
        for (Nbt.Tag it : lt.items) {
            if (it.type != T_COMPOUND) continue;
            if (m == null) m = new Nbt.Compound();
            mergeCompound(m, (Nbt.Compound) it.value);
        }
        return m == null ? null : new Nbt.Tag(T_COMPOUND, "", m);
    }

    private static void mergeCompound(Nbt.Compound dst, Nbt.Compound src) {
        for (Map.Entry<String, Nbt.Tag> e : src.entrySet()) {
            Nbt.Tag exist = dst.get(e.getKey());
            if (exist == null) {
                dst.put(e.getKey(), copyTag(e.getValue()));
            } else {
                mergeTag(exist, e.getValue());
            }
        }
    }

    private static void mergeTag(Nbt.Tag dst, Nbt.Tag src) {
        if (dst.type == src.type) {
            if (dst.type == T_COMPOUND && dst.value instanceof Nbt.Compound && src.value instanceof Nbt.Compound) {
                mergeCompound((Nbt.Compound) dst.value, (Nbt.Compound) src.value);
            } else if (dst.type == T_LIST && dst.value instanceof Nbt.ListTag && src.value instanceof Nbt.ListTag) {
                Nbt.ListTag dl = (Nbt.ListTag) dst.value, sl = (Nbt.ListTag) src.value;
                if (dl.elemType == 0 && sl.elemType != 0) { dl.elemType = sl.elemType; dl.items = sl.items; }
            }
            return;
        }
        // 类型冲突：列表优先保留有实体元素的那个，其它保留先出现的
        if (dst.type == T_LIST && dst.value instanceof Nbt.ListTag && ((Nbt.ListTag) dst.value).elemType == 0
                && src.type == T_LIST) {
            dst.type = src.type;
            dst.value = src.value;
        }
    }

    private static Nbt.Tag copyTag(Nbt.Tag t) {
        Nbt.Tag c = new Nbt.Tag(t.type, t.name, t.value);
        return c;
    }

    /** 显式包装：{"__type__":"byte","__value__":64}。 */
    private static Nbt.Tag buildTyped(Map<?, ?> m, String key) {
        String t = String.valueOf(m.get("__type__")).toLowerCase();
        if (!m.containsKey("__value__")) throw new IllegalArgumentException("__type__ 缺少 __value__");
        Object v = m.get("__value__");

        if (t.equals("byte")) return new Nbt.Tag(T_BYTE, key, (Byte) toNum(v, T_BYTE));
        if (t.equals("short")) return new Nbt.Tag(T_SHORT, key, (Short) toNum(v, T_SHORT));
        if (t.equals("int")) return new Nbt.Tag(T_INT, key, (Integer) toNum(v, T_INT));
        if (t.equals("long")) return new Nbt.Tag(T_LONG, key, (Long) toNum(v, T_LONG));
        if (t.equals("float")) return new Nbt.Tag(T_FLOAT, key, (Float) toNum(v, T_FLOAT));
        if (t.equals("double")) return new Nbt.Tag(T_DOUBLE, key, (Double) toNum(v, T_DOUBLE));
        if (t.equals("string")) return new Nbt.Tag(T_STRING, key, String.valueOf(v));

        if (t.equals("byte_array")) {
            if (!(v instanceof List)) throw new IllegalArgumentException("byte_array 的值必须是数组");
            List<?> l = (List<?>) v;
            byte[] b = new byte[l.size()];
            for (int i = 0; i < l.size(); i++) b[i] = ((Number) l.get(i)).byteValue();
            return new Nbt.Tag(T_BYTE_ARRAY, key, b);
        }
        if (t.equals("int_array")) {
            if (!(v instanceof List)) throw new IllegalArgumentException("int_array 的值必须是数组");
            List<?> l = (List<?>) v;
            int[] a = new int[l.size()];
            for (int i = 0; i < l.size(); i++) a[i] = ((Number) l.get(i)).intValue();
            return new Nbt.Tag(T_INT_ARRAY, key, a);
        }
        if (t.equals("long_array")) {
            if (!(v instanceof List)) throw new IllegalArgumentException("long_array 的值必须是数组");
            List<?> l = (List<?>) v;
            long[] a = new long[l.size()];
            for (int i = 0; i < l.size(); i++) a[i] = ((Number) l.get(i)).longValue();
            return new Nbt.Tag(T_LONG_ARRAY, key, a);
        }
        if (t.equals("compound")) {
            if (!(v instanceof Map)) throw new IllegalArgumentException("compound 的值必须是对象");
            Nbt.Tag tag = build(v, null, key);
            tag.type = T_COMPOUND;
            return tag;
        }
        if (t.equals("list")) {
            if (!(v instanceof List)) throw new IllegalArgumentException("list 的值必须是数组");
            Nbt.Tag tag = build(v, null, key);
            Object et = m.get("__elem__");
            if (et != null) {
                Nbt.ListTag lt = (Nbt.ListTag) tag.value;
                lt.elemType = typeCode(String.valueOf(et));
            }
            return tag;
        }
        throw new IllegalArgumentException("不认识的 __type__：" + t);
    }

    private static Number toNum(Object v, int type) {
        if (!(v instanceof Number)) throw new IllegalArgumentException("该类型需要数字");
        Number n = (Number) v;
        switch (type) {
            case T_BYTE: {
                long x = n.longValue();
                if (x < -128 || x > 127) {
                    if (x >= 0 && x <= 255) return (byte) (x - 256);
                    throw new IllegalArgumentException("byte 超出范围：" + x);
                }
                return (byte) x;
            }
            case T_SHORT: {
                long x = n.longValue();
                if (x < -32768 || x > 32767) throw new IllegalArgumentException("short 超出范围：" + x);
                return (short) x;
            }
            case T_INT: return (int) n.longValue();
            case T_LONG: return n.longValue();
            case T_FLOAT: return n.floatValue();
            default: return n.doubleValue();
        }
    }

    private static Number convertNumber(Number n, int type) {
        switch (type) {
            case T_BYTE: {
                long x = n.longValue();
                if (x > 127 && x <= 255) x -= 256;   // 允许按无符号写法填 200
                if (x < -128 || x > 127) throw new IllegalArgumentException("byte 超出范围：" + n);
                return (byte) x;
            }
            case T_SHORT: {
                long x = n.longValue();
                if (x > 32767 && x <= 65535) x -= 65536;
                if (x < -32768 || x > 32767) throw new IllegalArgumentException("short 超出范围：" + n);
                return (short) x;
            }
            case T_INT: return n.intValue();
            case T_LONG: return n.longValue();
            case T_FLOAT: return n.floatValue();
            case T_DOUBLE: return n.doubleValue();
            default: return n.intValue();
        }
    }

    private static int guessNumberType(String key) {
        if (key == null) return T_INT;
        String k = key.toLowerCase();
        if (k.equals("count") || k.equals("slot") || k.equals("waspickedup") || k.equals("unbreakable")) return T_BYTE;
        if (k.equals("damage")) return T_SHORT;
        // 附魔项 {id, lvl} 在基岩版里都是 short
        if (k.equals("lvl") || k.equals("id")) return T_SHORT;
        return T_INT;
    }

    private static boolean isNumberType(int t) {
        return t >= T_BYTE && t <= T_DOUBLE;
    }

    private static String typeName(int t) {
        switch (t) {
            case T_BYTE: return "byte";
            case T_SHORT: return "short";
            case T_INT: return "int";
            case T_LONG: return "long";
            case T_FLOAT: return "float";
            case T_DOUBLE: return "double";
            case T_BYTE_ARRAY: return "byte[]";
            case T_STRING: return "string";
            case T_LIST: return "list";
            case T_COMPOUND: return "compound";
            case T_INT_ARRAY: return "int[]";
            case T_LONG_ARRAY: return "long[]";
            default: return "未知类型 " + t;
        }
    }

    private static int typeCode(String s) {
        String t = s.toLowerCase();
        if (t.equals("byte")) return T_BYTE;
        if (t.equals("short")) return T_SHORT;
        if (t.equals("int")) return T_INT;
        if (t.equals("long")) return T_LONG;
        if (t.equals("float")) return T_FLOAT;
        if (t.equals("double")) return T_DOUBLE;
        if (t.equals("string")) return T_STRING;
        if (t.equals("compound")) return T_COMPOUND;
        if (t.equals("list")) return T_LIST;
        if (t.equals("byte_array")) return T_BYTE_ARRAY;
        if (t.equals("int_array")) return T_INT_ARRAY;
        if (t.equals("long_array")) return T_LONG_ARRAY;
        if (t.equals("end")) return 0;
        throw new IllegalArgumentException("不认识的 __elem__：" + s);
    }
}

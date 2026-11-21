package com.nmsave.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析（只用标准库，桌面端自测也能编）。
 * 对象 -> LinkedHashMap，数组 -> ArrayList，数字 -> Long/Double，字符串 -> String，布尔 -> Boolean，null -> null。
 */
public class Json {

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new IllegalStateException("JSON 末尾有多余内容，位置 " + p.i);
        return v;
    }

    private static class P {
        final String s;
        int i;

        P(String s) { this.s = s == null ? "" : s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        char peek() {
            if (i >= s.length()) throw new IllegalStateException("JSON 意外结束");
            return s.charAt(i);
        }

        Object value() {
            char c = peek();
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f' || c == 'n') return lit();
            return num();
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new IllegalStateException("对象键必须是字符串，位置 " + i);
                String k = str();
                ws();
                if (peek() != ':') throw new IllegalStateException("缺少冒号，位置 " + i);
                i++;
                ws();
                Object v = value();
                m.put(k, v);
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new IllegalStateException("对象里缺少 , 或 }，位置 " + i);
            }
        }

        List<Object> arr() {
            List<Object> a = new ArrayList<>();
            i++; // [
            ws();
            if (peek() == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return a; }
                throw new IllegalStateException("数组里缺少 , 或 ]，位置 " + i);
            }
        }

        String str() {
            StringBuilder sb = new StringBuilder();
            i++; // "
            while (true) {
                if (i >= s.length()) throw new IllegalStateException("字符串没有闭合");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new IllegalStateException("转义字符不完整");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        if (i + 4 > s.length()) throw new IllegalStateException("\\u 转义不完整");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    }
                    default: throw new IllegalStateException("不支持的转义 \\" + e);
                }
            }
        }

        Object lit() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new IllegalStateException("无法识别的字符 " + peek() + "，位置 " + i);
        }

        Object num() {
            int st = i;
            if (peek() == '-' || peek() == '+') i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') i++;
                else break;
            }
            String t = s.substring(st, i);
            if (t.isEmpty()) throw new IllegalStateException("不是合法数字，位置 " + st);
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
                return Double.valueOf(t);
            }
            try {
                return Long.valueOf(t);
            } catch (NumberFormatException nf) {
                return Double.valueOf(t);
            }
        }
    }
}

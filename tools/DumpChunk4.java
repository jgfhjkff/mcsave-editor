import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class DumpChunk4 {
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static String txt(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) { int c = x & 0xff; s.append(c >= 32 && c < 127 ? (char) c : '.'); } return s.toString(); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        Map<String, Integer> prefixes = new TreeMap<>();
        int printable = 0;
        for (BedrockDb.Entry e : all) {
            if (e.value == null) continue;
            String k = new String(e.key, java.nio.charset.StandardCharsets.UTF_8);
            if (k.matches("[\\x20-\\x7e]+")) { printable++; continue; }
            String t = txt(e.key);
            String head = t.length() > 10 ? t.substring(0, 10) : t;
            prefixes.put(head, (prefixes.containsKey(head) ? prefixes.get(head) : 0) + 1);
        }
        System.out.println("可打印键=" + printable);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(prefixes.entrySet());
        list.sort((x, y) -> y.getValue() - x.getValue());
        for (int i = 0; i < Math.min(20, list.size()); i++)
            System.out.println("前缀[" + list.get(i).getKey() + "] 数量=" + list.get(i).getValue());
        System.out.println("--- 可打印键示例 ---");
        int shown = 0;
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, java.nio.charset.StandardCharsets.UTF_8);
            if (e.value != null && k.matches("[\\x20-\\x7e]+") && shown++ < 30)
                System.out.println("键[" + k + "] 值长=" + e.value.length);
        }
    }
}

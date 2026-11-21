import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class DumpChunk2 {
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        byte[] key = Editor.dbKeyOf(db);
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        Map<Integer, Integer> lens = new TreeMap<>();
        int shown = 0;
        for (BedrockDb.Entry e : all) {
            if (e.value == null) continue;
            String k = new String(e.key, java.nio.charset.StandardCharsets.UTF_8);
            boolean printable = k.matches("[\\x20-\\x7e]+");
            if (printable) continue;
            lens.put(e.key.length, (lens.containsKey(e.key.length) ? lens.get(e.key.length) : 0) + 1);
            if (shown++ < 12) {
                byte[] head = Arrays.copyOf(e.value, Math.min(40, e.value.length));
                System.out.println("键长度=" + e.key.length + " hex=" + hex(e.key)
                        + " 值长=" + e.value.length + " 值头=" + hex(head));
            }
        }
        System.out.println("非ASCII键长度分布=" + lens);
    }
}

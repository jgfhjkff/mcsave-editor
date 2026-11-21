import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class DumpChunk {
    static boolean isIntList(String s, int want) {
        String[] p = s.split(",");
        if (p.length != want) return false;
        for (String x : p) {
            try { Integer.parseInt(x); } catch (Exception e) { if (!x.trim().isEmpty()) return false; }
        }
        return true;
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        File db = new File(dir, "db");
        byte[] key = Editor.dbKeyOf(db);
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        System.out.println("entries=" + all.size());
        int shown = 0, subs = 0;
        for (BedrockDb.Entry e : all) {
            if (e.value == null) continue;
            String k = new String(e.key, java.nio.charset.StandardCharsets.UTF_8);
            boolean printable = k.matches("[\\x20-\\x7e]+");
            if (!printable) {
                if (shown++ < 8) System.out.println("非ASCII键 len=" + e.key.length + " 首字节=" + e.key[0]);
                continue;
            }
            if (isIntList(k, 2)) {
                if (shown++ < 20) System.out.println("chunk键 " + k + " 值长 " + e.value.length);
            } else if (isIntList(k, 3)) {
                subs++;
                if (subs <= 6) {
                    System.out.println("subchunk键 " + k + " 值长 " + e.value.length
                            + " 头=" + e.value[0] + "," + e.value[1]
                            + (e.value.length > 2 ? ("," + e.value[2]) : ""));
                }
            }
        }
        System.out.println("subchunk总数=" + subs);
    }
}

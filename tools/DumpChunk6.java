import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class DumpChunk6 {
    static String txt(byte[] b, int max) { StringBuilder s = new StringBuilder(); for (int i = 0; i < b.length && i < max; i++) { int c = b[i] & 0xff; s.append(c >= 32 && c < 127 ? (char) c : '.'); } return s.toString(); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        Map<String, Integer> roots = new HashMap<>();
        int ok = 0;
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.value.length < 40) continue;
            int[] skip = new int[]{0, 4};
            for (int s : skip) {
                try {
                    Nbt.Tag t = Nbt.parse(e.value, s);
                    if (t.type != Nbt.TAG_COMPOUND) continue;
                    Nbt.Compound c = (Nbt.Compound) t.value;
                    ok++;
                    String r = c.keySet().toString();
                    if (!roots.containsKey(r)) {
                        roots.put(r, 0);
                        if (roots.size() <= 25) System.out.println("键[" + txt(e.key, 24) + "] 跳" + s
                                + " 根键(" + c.size() + ")=" + (r.length() > 100 ? r.substring(0, 100) : r)
                                + " 值长=" + e.value.length);
                    }
                    break;
                } catch (Throwable ignored) { }
            }
        }
        System.out.println("可解析=" + ok + " 不同根结构=" + roots.size());
    }
}

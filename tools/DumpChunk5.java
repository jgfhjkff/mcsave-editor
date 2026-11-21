import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class DumpChunk5 {
    static String txt(byte[] b, int max) { StringBuilder s = new StringBuilder(); for (int i = 0; i < b.length && i < max; i++) { int c = b[i] & 0xff; s.append(c >= 32 && c < 127 ? (char) c : '.'); } return s.toString(); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        int ok0 = 0, ok4 = 0;
        Map<String, Integer> roots = new TreeMap<>();
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.value.length < 20) continue;
            for (int skip : new int[]{0, 4}) {
                try {
                    int[] pos = new int[1];
                    Nbt.Tag t = (Nbt.Tag) Nbt.readValue(e.value, skip, Nbt.TAG_COMPOUND, pos);
                    if (t.type == Nbt.TAG_COMPOUND && pos[0] > e.value.length * 0.7) {
                        Nbt.Compound c = (Nbt.Compound) t.value;
                        if (skip == 0) ok0++; else ok4++;
                        String k = txt(e.key, 24);
                        String sig = txt(e.value, 4);
                        String r = c.keySet().toString();
                        if (roots.size() < 40) System.out.println("skip=" + skip + " 键[" + k + "] 值头[" + sig
                            + "] 根键(" + c.size() + ")=" + (r.length() > 90 ? r.substring(0, 90) : r));
                        roots.put(r.length() > 60 ? r.substring(0, 60) : r, 1);
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        System.out.println("可解析复合=" + ok0 + " 跳4后可解析=" + ok4);
    }
}

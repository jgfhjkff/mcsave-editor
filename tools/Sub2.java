import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class Sub2 {
    static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8) | ((b[o+2] & 0xff) << 16) | ((b[o+3] & 0xff) << 24); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.key.length != 10) continue;
            int x = le32(e.key, 0), z = le32(e.key, 4), y = e.key[9];
            if (x != -1 || z != -1 || y != 0) continue;
            byte[] v = e.value;
            int layers = v[1] & 0xff;
            int pos = 3;
            for (int l = 0; l < layers; l++) {
                int header = v[pos] & 0xff; pos++;
                int pt = header & 1, bits = header >> 1;
                int bpw = 32 / bits;
                int words = (4096 + bpw - 1) / bpw;
                int idxStart = pos;
                pos += words * 4;
                int count = le32(v, pos); pos += 4;
                System.out.println("层" + l + " 类型=" + pt + " 位宽=" + bits + " 索引=" + (words * 4) + " 词数=" + words
                        + " 每词=" + bpw + " 调色板=" + count + " 尾余=" + (v.length - pos));
                for (int k = 0; k < count; k++) {
                    int[] end = new int[1];
                    Nbt.Tag t = Nbt.parseAny(v, pos, end);
                    Nbt.Compound c = (Nbt.Compound) t.value;
                    if (k < 8 || k > count - 3) System.out.println("  [" + k + "] name=" + str(c, "name")
                            + " val=" + num(c, "val") + " version=" + num(c, "version")
                            + " states=" + map(c.get("states")));
                    pos = end[0];
                }
                System.out.println("  调色板后偏移=" + pos + " 总长=" + v.length);
                // 索引取样：前几个方块
                int bpw2 = bpw, bits2 = bits, mask = (1 << bits2) - 1;
                for (int i = 0; i < 4; i++) {
                    int word = i / bpw2;
                    int v32 = le32(v, idxStart + word * 4);
                    int val = (v32 >>> ((i % bpw2) * bits2)) & mask;
                    System.out.println("  索引" + i + " -> 调色板[" + val + "]");
                }
            }
        }
    }

    static String str(Nbt.Compound c, String k) { Nbt.Tag t = c.get(k); return t == null ? "null" : String.valueOf(t.value); }
    static String num(Nbt.Compound c, String k) { Nbt.Tag t = c.get(k); return t == null ? "null" : (t.value + "(t=" + t.type + ")"); }
    static String map(Nbt.Tag t) {
        if (t == null || t.type != Nbt.TAG_COMPOUND) return "null";
        StringBuilder sb = new StringBuilder("{");
        Nbt.Compound c = (Nbt.Compound) t.value;
        for (Map.Entry<String, Nbt.Tag> en : c.entrySet()) sb.append(en.getKey()).append("=").append(en.getValue().value).append("(t=").append(en.getValue().type).append(") ");
        return sb.append("}").toString();
    }
}

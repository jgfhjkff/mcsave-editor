import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class Sub {
    static String hex(byte[] b, int o, int n) { StringBuilder s = new StringBuilder(); for (int i = o; i < b.length && i < o + n; i++) s.append(String.format("%02x", b[i])); return s.toString(); }
    static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8) | ((b[o+2] & 0xff) << 16) | ((b[o+3] & 0xff) << 24); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.key.length != 10) continue;
            int x = le32(e.key, 0), z = le32(e.key, 4), y = e.key[9];
            if (x != -1 || z != -1 || y != 0) continue;
            byte[] v = e.value;
            System.out.println("总长=" + v.length + " 版本=" + (v[0] & 0xff) + " 层数=" + (v[1] & 0xff) + " 值内Y=" + v[2]);
            int[] pos = new int[]{3};
            int layers = v[1] & 0xff;
            for (int l = 0; l < layers && l < 4; l++) {
                int hp = pos[0];
                int header = v[hp] & 0xff; pos[0]++;
                int pt = header & 1, bits = header >> 1;
                int bpw = 32 / bits;
                int words = (4096 + bpw - 1) / bpw;
                int idxBytes = words * 4;
                int idxStart = pos[0];
                pos[0] += idxBytes;
                int count;
                if (pt == 0) { count = le32(v, pos[0]); pos[0] += 4; }
                else { System.out.println("varint palette"); break; }
                System.out.println("层" + l + " 头=0x" + Integer.toHexString(header) + " 类型=" + pt + " 位宽=" + bits
                        + " 索引字节=" + idxBytes + " 调色板数=" + count + " 调色板起始偏移=" + pos[0]);
                int palStart = pos[0];
                for (int k = 0; k < Math.min(count, 6); k++) {
                    try {
                        System.out.print("  条目" + k + " 起始=" + pos[0] + " ");
                        Nbt.Tag t = Nbt.parse(v, pos[0]);
                        System.out.println("type=" + t.type + " name=[" + t.name + "] " + (t.type == 10 ? t.value.toString() : ""));
                        // 试图跳过该条目
                        int consumed = skipTag(v, pos[0]);
                        pos[0] += consumed;
                    } catch (Throwable ex) {
                        System.out.println("解析失败 " + ex);
                        break;
                    }
                }
                System.out.println("  调色板结束偏移=" + pos[0] + " 预计数据结束=" + v.length);
                // 残留
            }
        }
    }

    static int skipTag(byte[] v, int off) {
        // 只处理 compound：用 parse 后无法得长度，改用手工扫描不现实，这里通过 readValue 计算
        int[] p = new int[]{off + 1}; // 跳过类型字节? 不行，还是用手工
        return 0;
    }
}

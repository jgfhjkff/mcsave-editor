import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class Keys10 {
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8) | ((b[o+2] & 0xff) << 16) | ((b[o+3] & 0xff) << 24); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        int n10 = 0;
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.key.length != 10) continue;
            int x = le32(e.key, 0), z = le32(e.key, 4);
            int yByte = e.key[8] & 0xff, tag = e.key[9] & 0xff;
            int subY = (e.value.length > 2 && (e.value[0] & 0xff) == 9) ? (e.value[2] & 0xff) : -1;
            if (n10++ < 25) System.out.println("键=" + hex(e.key) + " x=" + x + " z=" + z
                    + " k8=" + yByte + " tag=0x" + Integer.toHexString(tag) + " 值长=" + e.value.length
                    + " v=" + (e.value[0] & 0xff) + " 值内Y=" + subY);
        }
        System.out.println("10字节键总数=" + n10);
        // 8字节前缀集合范围
        Set<Integer> xs = new TreeSet<>(), zs = new TreeSet<>();
        for (BedrockDb.Entry e : all) if (e.value != null && e.key.length == 10) {
            xs.add(le32(e.key, 0)); zs.add(le32(e.key, 4));
        }
        System.out.println("x 取值=" + (xs.size() > 12 ? new ArrayList<>(xs).subList(0, 6) + "..." + new ArrayList<>(xs).subList(xs.size()-6, xs.size()) : xs));
        System.out.println("z 取值=" + (zs.size() > 12 ? new ArrayList<>(zs).subList(0, 6) + "..." + new ArrayList<>(zs).subList(zs.size()-6, zs.size()) : zs));
    }
}

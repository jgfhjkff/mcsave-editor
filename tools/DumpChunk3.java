import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class DumpChunk3 {
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        Map<Integer, Integer> firstByte = new TreeMap<>();
        int n19 = 0;
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.value.length == 0) continue;
            if (e.key.length != 19) continue;
            n19++;
            int fb = e.value[0] & 0xff;
            firstByte.put(fb, (firstByte.containsKey(fb) ? firstByte.get(fb) : 0) + 1);
            if (n19 <= 10) {
                byte[] head = Arrays.copyOf(e.value, Math.min(48, e.value.length));
                System.out.println("k19 hex=" + hex(e.key) + " 值长=" + e.value.length + " 头=" + hex(head));
            }
        }
        System.out.println("19字节键数量=" + n19 + " 值首字节分布=" + firstByte);
    }
}

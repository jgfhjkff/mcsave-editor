import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class DumpChunk7 {
    static String hex(byte[] b, int from, int n) { StringBuilder s = new StringBuilder(); for (int i = from; i < b.length && i < from + n; i++) s.append(String.format("%02x", b[i])); return s.toString(); }
    static String txt(byte[] b, int max) { StringBuilder s = new StringBuilder(); for (int i = 0; i < b.length && i < max; i++) { int c = b[i] & 0xff; s.append(c >= 32 && c < 127 ? (char) c : '.'); } return s.toString(); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        // 9 字节键：8 字节前缀 + 1 字节类型
        Map<Integer, int[]> stats = new TreeMap<>(); // 类型 -> {数量, 平均长度}
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.key.length != 9) continue;
            int tag = e.key[8] & 0xff;
            int[] s = stats.get(tag);
            if (s == null) { s = new int[]{0, 0}; stats.put(tag, s); }
            s[0]++; s[1] += e.value.length;
        }
        for (Map.Entry<Integer, int[]> en : stats.entrySet()) {
            System.out.println("尾字节 0x" + Integer.toHexString(en.getKey()) + "('" + (char) en.getKey().intValue()
                    + "') 数量=" + en.getValue()[0] + " 平均值长=" + (en.getValue()[1] / en.getValue()[0]));
        }
        System.out.println("--- 同一前缀下的不同记录 ---");
        // 找出现次数最多的前缀
        Map<String, Integer> pre = new HashMap<>();
        for (BedrockDb.Entry e : all) if (e.value != null && e.key.length == 9) {
            String p = hex(e.key, 0, 8); pre.put(p, pre.containsKey(p) ? pre.get(p) + 1 : 1);
        }
        String best = null; int bestN = 0;
        for (Map.Entry<String, Integer> en : pre.entrySet()) if (en.getValue() > bestN) { bestN = en.getValue(); best = en.getKey(); }
        System.out.println("前缀 " + best + " 记录数=" + bestN);
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.key.length != 9) continue;
            if (hex(e.key, 0, 8).equals(best)) {
                System.out.println("  尾=0x" + Integer.toHexString(e.key[8] & 0xff) + " 值长=" + e.value.length
                        + " 头=" + hex(e.value, 0, 24) + " 尾=" + hex(e.value, Math.max(0, e.value.length - 8), 8));
            }
        }
        System.out.println("--- 其它键值 ---");
        for (BedrockDb.Entry e : all) {
            if (e.value == null) continue;
            String k = new String(e.key, java.nio.charset.StandardCharsets.UTF_8);
            if (k.equals("neteaseData") || k.equals("SST_WORD") || k.equals("SST_NAME") || k.equals("SST_SALOG") || k.equals("20")) {
                System.out.println("键[" + k + "] 值长=" + e.value.length + " 头=" + hex(e.value, 0, 60));
            }
        }
    }
}

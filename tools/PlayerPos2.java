import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class PlayerPos2 {
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8) | ((b[o+2] & 0xff) << 16) | ((b[o+3] & 0xff) << 24); }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        File db = new File(dir, "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        byte[] pv = null;
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, java.nio.charset.StandardCharsets.UTF_8);
            if (k.equals("~local_player")) pv = e.value;
        }
        double[] pos = PlayerEdit.readPos(pv);
        System.out.println("Pos=" + pos[0] + "," + pos[1] + "," + pos[2]
                + " chunk=(" + ((int) Math.floor(pos[0] / 16)) + "," + ((int) Math.floor(pos[2] / 16)) + ")");
        int cx = (int) Math.floor(pos[0] / 16), cz = (int) Math.floor(pos[2] / 16);
        int found = 0;
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.key.length != 10) continue;
            if (le32(e.key, 0) == cx && le32(e.key, 4) == cz) {
                found++;
                System.out.println("命中 x=" + cx + " z=" + cz + " Y=" + (e.key[9] & 0xff) + " key=" + hex(e.key) + " 长=" + e.value.length);
            }
        }
        System.out.println("该区块子区块数=" + found);
        // 列出所有不同的 8 字节前缀与其子区块数，确认前缀的确表示坐标/或哈希
        Map<String, Integer> uniq = new TreeMap<>();
        for (BedrockDb.Entry e : all) if (e.value != null && e.key.length == 10) {
            String p = hex(Arrays.copyOf(e.key, 8));
            uniq.put(p, uniq.containsKey(p) ? uniq.get(p) + 1 : 1);
        }
        System.out.println("不同区块前缀数=" + uniq.size());
        int i = 0;
        for (Map.Entry<String, Integer> en : uniq.entrySet()) {
            if (i++ >= 10) break;
            byte[] b = new byte[8];
            for (int j = 0; j < 8; j++) b[j] = (byte) Integer.parseInt(en.getKey().substring(j * 2, j * 2 + 2), 16);
            System.out.println("前缀 " + en.getKey() + " x=" + le32(b, 0) + " z=" + le32(b, 4) + " 子区块=" + en.getValue());
        }
    }
}

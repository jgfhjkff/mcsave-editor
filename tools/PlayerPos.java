import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class PlayerPos {
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o+1] & 0xff) << 8) | ((b[o+2] & 0xff) << 16) | ((b[o+3] & 0xff) << 24); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, java.nio.charset.StandardCharsets.UTF_8);
            if (!k.equals("~local_player") || e.value == null) continue;
            Nbt.Tag t = Nbt.parse(e.value, 0);
            Nbt.Compound c = (Nbt.Compound) t.value;
            System.out.println("Pos=" + c.get("Pos") + " 根键数=" + c.size());
            Nbt.ListTag lt = (Nbt.ListTag) c.get("Pos").value;
            double px = ((Number) lt.items.get(0).value).doubleValue();
            double pz = ((Number) lt.items.get(2).value).doubleValue();
            System.out.println("x=" + px + " z=" + pz + " chunk=(" + ((int) Math.floor(px / 16)) + "," + ((int) Math.floor(pz / 16)) + ")");
            int cx = (int) Math.floor(px / 16), cz = (int) Math.floor(pz / 16);
            int found = 0;
            for (BedrockDb.Entry e2 : all) {
                if (e2.value == null || e2.key.length != 10) continue;
                if (le32(e2.key, 0) == cx && le32(e2.key, 4) == cz) {
                    found++;
                    System.out.println("命中子区块 x=" + cx + " z=" + cz + " Y=" + (e2.key[9] & 0xff)
                            + " key=" + hex(e2.key) + " 值长=" + e2.value.length);
                }
            }
            System.out.println("该区块子区块数=" + found);
        }
    }
}

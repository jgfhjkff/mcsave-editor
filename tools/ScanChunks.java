import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class ScanChunks {
    static String txt(byte[] b, int max) { StringBuilder s = new StringBuilder(); for (int i = 0; i < b.length && i < max; i++) { int c = b[i] & 0xff; s.append(c >= 32 && c < 127 ? (char) c : '.'); } return s.toString(); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);
        int sub = 0, chunkNbt = 0, paletteOnly = 0;
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.value.length < 100) continue;
            int v0 = e.value[0] & 0xff;
            if ((v0 == 8 || v0 == 9) && e.value.length >= 1000) {
                if (sub++ < 6) System.out.println("疑似子区块 键[" + txt(e.key, 20) + "] 长=" + e.value.length
                        + " v=" + v0 + " 层=" + (e.value[1] & 0xff));
            }
            try {
                Nbt.Tag t = Nbt.parse(e.value, 0);
                if (t.type == Nbt.TAG_COMPOUND) {
                    Nbt.Compound c = (Nbt.Compound) t.value;
                    if (c.containsKey("Version") || c.containsKey("FinalizedState")) {
                        if (chunkNbt++ < 5) System.out.println("区块NBT 键[" + txt(e.key, 20) + "] 长=" + e.value.length + " 根=" + c.keySet());
                    }
                    if (c.containsKey("states") && c.containsKey("name")) paletteOnly++;
                }
            } catch (Throwable ignored) { }
            if (e.value.length > 4) {
                try {
                    Nbt.Tag t2 = Nbt.parse(e.value, 4);
                    if (t2.type == Nbt.TAG_COMPOUND) {
                        Nbt.Compound c2 = (Nbt.Compound) t2.value;
                        if (c2.containsKey("states") && c2.containsKey("name")) paletteOnly++;
                    }
                } catch (Throwable ignored) { }
            }
        }
        System.out.println("子区块候选=" + sub + " 区块NBT=" + chunkNbt + " 调色板条目=" + paletteOnly);
    }
}

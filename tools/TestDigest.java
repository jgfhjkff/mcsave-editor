import com.nmsave.editor.*;
import java.io.File;
import java.util.List;

public class TestDigest {
    static void rm(File f) { File[] k = f.listFiles(); if (k != null) for (File x : k) rm(x); f.delete(); }
    static int digests(File db) throws Exception {
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), new java.util.ArrayList<String>());
        int n = 0;
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (k.startsWith("digp")) n++;
        }
        return n;
    }
    public static void main(String[] a) throws Exception {
        File world = new File(a[0]);
        System.out.println("原始 digp 数 = " + digests(new File(world, "db")));

        // 1) 只改天气（不清理实体）：索引必须原样保留
        Editor.Options o1 = new Editor.Options();
        o1.purgeEntities = false; o1.weather = 2;
        File out1 = new File("/tmp/dg1"); rm(out1); out1.mkdirs();
        Editor.processDir(world, out1, o1, new File("/tmp/dgw1"), null);
        System.out.println("不改实体 → digp = " + digests(new File(out1, "db"))
                + "（应与原始相同）");

        // 2) 清理实体 + 清索引
        Editor.Options o2 = new Editor.Options();
        o2.purgeEntities = true; o2.clearDigests = true;
        File out2 = new File("/tmp/dg2"); rm(out2); out2.mkdirs();
        Editor.processDir(world, out2, o2, new File("/tmp/dgw2"), null);
        System.out.println("清理实体+清索引 → digp = " + digests(new File(out2, "db")) + "（应为 0）");

        // 3) 清理实体但保留索引
        Editor.Options o3 = new Editor.Options();
        o3.purgeEntities = true; o3.clearDigests = false;
        File out3 = new File("/tmp/dg3"); rm(out3); out3.mkdirs();
        Editor.processDir(world, out3, o3, new File("/tmp/dgw3"), null);
        System.out.println("清理实体但保留索引 → digp = " + digests(new File(out3, "db")) + "（应>0）");
    }
}

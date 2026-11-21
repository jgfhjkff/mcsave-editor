import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

/** 端到端：只改背包，跑完整 processDir（解密→重建库→加密），再读回校验。 */
public class TestInvWrite {
    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File out = new File("/tmp/inv_out");
        File work = new File("/tmp/inv_work");
        Editor.Options opt = new Editor.Options();
        opt.purgeEntities = false;
        opt.clearDigests = false;
        opt.invSection = "Inventory";
        opt.invJson = "[{\"Name\":\"minecraft:stone\",\"Count\":64,\"Damage\":0,\"Slot\":0},"
                + "{\"Name\":\"minecraft:diamond_sword\",\"Count\":1,\"Damage\":0,\"Slot\":1}]";
        Editor.Stats st = Editor.processDir(src, out, opt, work, new Editor.Progress() {
            public void onProgress(int pct, String msg) { System.out.println(pct + "% " + msg); }
        });
        System.out.println("keptKey=" + st.keptCount + " entityCount=" + st.entityCount);
        String json = Editor.readPlayerSectionJson(out, "Inventory");
        Object o = Json.parse(json);
        List<?> items = (List<?>) o;
        System.out.println("写回后项数 = " + items.size() + "（应为 2）");
        int slot0 = ((Number) ((Map<?, ?>) items.get(0)).get("Slot")).intValue();
        String name0 = String.valueOf(((Map<?, ?>) items.get(0)).get("Name"));
        System.out.println("第 0 项 = " + name0 + " slot=" + slot0);

        // 其他字段还在吗
        byte[] cur = BedrockDb.readFile(new File(out, "db/CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, manifestName(new File(out, "db"))) : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(new File(out, "db"), key, null);
        byte[] val = null;
        for (BedrockDb.Entry e : all) if ("~local_player".equals(new String(e.key, "ISO-8859-1"))) val = e.value;
        System.out.println("库内键总数 = " + all.size());
        System.out.println("Pos 仍在 = " + (PlayerEdit.readPos(val) != null)
                + "，Inventory 之后字段可解析 = " + (PlayerEdit.find(val, "Pos") != null)
                + "，value 字节 = " + (val == null ? -1 : val.length));
        all.clear();

        // 第二次：把读到的 JSON 原样写回，结果应等长（说明读→写闭环无损）
        Editor.Options opt2 = new Editor.Options();
        opt2.purgeEntities = false; opt2.clearDigests = false;
        opt2.invSection = "Inventory"; opt2.invJson = json;
        Editor.processDir(out, new File("/tmp/inv_out2"), opt2, new File("/tmp/inv_work2"), null);
        String json2 = Editor.readPlayerSectionJson(new File("/tmp/inv_out2"), "Inventory");
        System.out.println("二次写回 JSON 一致 = " + json2.equals(json));
    }

    static String manifestName(File db) {
        for (File f : db.listFiles()) if (f.getName().startsWith("MANIFEST")) return f.getName();
        return null;
    }
}

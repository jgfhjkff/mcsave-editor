import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

/** 背包 JSON 读写回归：真存档 round-trip + 改写 + 结构校验。 */
public class TestInvJson {
    public static void main(String[] a) throws Exception {
        File world = new File(a[0]);
        File dbDir = new File(world, "db");
        byte[] key = null;
        File cur = new File(dbDir, "CURRENT");
        if (cur.isFile()) {
            byte[] c = BedrockDb.readFile(cur);
            if (BedrockDb.isEncrypted(c)) {
                String mf = null;
                for (File f : dbDir.listFiles()) if (f.getName().startsWith("MANIFEST")) mf = f.getName();
                key = BedrockDb.deriveKey(c, mf);
            }
        }
        List<BedrockDb.Entry> all = BedrockDb.readAll(dbDir, key, null);
        byte[] val = null;
        for (BedrockDb.Entry e : all) {
            if ("~local_player".equals(new String(e.key, "ISO-8859-1"))) { val = e.value; break; }
        }
        all.clear();
        if (val == null) { System.out.println("FAIL 没有 ~local_player"); return; }
        System.out.println("player value 字节 = " + val.length);
        double[] pos0 = PlayerEdit.readPos(val);

        Nbt.Tag inv = PlayerEdit.readSection(val, "Inventory");
        System.out.println("Inventory 存在 = " + (inv != null)
                + (inv == null ? "" : "，项数 = " + ((Nbt.ListTag) inv.value).items.size()));

        if (inv != null) {
            String json = NbtJson.toJson(inv);
            System.out.println("导出 JSON 字符数 = " + json.length());
            byte[] rt = PlayerEdit.setInventorySection(val, "Inventory", json);
            System.out.println("round-trip 字节 " + val.length + " -> " + rt.length + "（应相等）");
            String json2 = NbtJson.toJson(PlayerEdit.readSection(rt, "Inventory"));
            System.out.println("round-trip JSON 一致 = " + json.equals(json2));
            double[] pos1 = PlayerEdit.readPos(rt);
            System.out.println("Pos 未受影响 = " + (pos0 != null && pos1 != null
                    && Math.abs(pos0[0] - pos1[0]) < 1e-9));
        }

        // 手工 JSON 写入（模板取自现有 Inventory，决定 byte/short 类型）
        String hand = "[{\"Name\":\"minecraft:stone\",\"Count\":64,\"Damage\":0,\"Slot\":0},"
                + "{\"Name\":\"minecraft:diamond_sword\",\"Count\":1,\"Damage\":0,\"Slot\":1,"
                + "\"tag\":{\"Damage\":0,\"display\":{\"Name\":\"测试剑\"}}}]";
        byte[] nv = PlayerEdit.setInventorySection(val, "Inventory", hand);
        Nbt.Tag read = PlayerEdit.readSection(nv, "Inventory");
        Nbt.ListTag lt = (Nbt.ListTag) read.value;
        System.out.println("写入后项数 = " + lt.items.size() + "（应为 2）");
        for (Nbt.Tag item : lt.items) {
            Nbt.Compound c = (Nbt.Compound) item.value;
            Nbt.Tag name = c.get("Name"), cnt = c.get("Count"), dmg = c.get("Damage");
            System.out.println("  项 " + name.value + " countType=" + cnt.type + " count=" + cnt.value
                    + " damageType=" + dmg.type + " damage=" + dmg.value);
        }
        System.out.println("写入后 Pos 正常 = " + (PlayerEdit.readPos(nv) != null));
        System.out.println("写入后整体仍可解析（Inventory 后字段）= " + PlayerEdit.find(nv, "Pos") != null);

        // 非法 JSON 应抛错
        try {
            PlayerEdit.setInventorySection(val, "Inventory", "[{\"Count\":");
            System.out.println("FAIL 非法 JSON 没报错");
        } catch (Throwable e) {
            System.out.println("非法 JSON 报错 = " + e.getMessage());
        }
    }
}

import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

/** 单格改写回归：只改槽 0，其余格原样，Tag 直写（不经 JSON）。 */
public class TestSlotWrite {
    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        Nbt.Tag before = Editor.readPlayerSectionTag(src, "Inventory");
        Nbt.ListTag lt0 = (Nbt.ListTag) before.value;
        Map<Integer, String> oldJson = new LinkedHashMap<>();
        Map<Integer, Nbt.Tag> bySlot = new LinkedHashMap<>();
        for (Nbt.Tag it : lt0.items) {
            Nbt.Compound c = (Nbt.Compound) it.value;
            int s = ((Number) c.get("Slot").value).intValue();
            bySlot.put(s, it);
            oldJson.put(s, NbtJson.toJson(it));
        }
        System.out.println("原有格子 = " + bySlot.size());

        // 改槽 0：Name/Cout/Damage
        Nbt.Tag zero = bySlot.get(0);
        Nbt.Compound zc = (Nbt.Compound) zero.value;
        zc.put("Name", new Nbt.Tag(Nbt.TAG_STRING, "Name", "minecraft:diamond"));
        zc.put("Count", new Nbt.Tag(Nbt.TAG_BYTE, "Count", (byte) 5));
        Nbt.ListTag lt = new Nbt.ListTag();
        lt.elemType = Nbt.TAG_COMPOUND;
        for (int i = 0; i < 36; i++) {
            Nbt.Tag t = bySlot.get(i);
            if (t == null) continue;
            lt.items.add(t);
        }
        Editor.Options opt = new Editor.Options();
        opt.purgeEntities = false;
        opt.clearDigests = false;
        opt.invSection = "Inventory";
        opt.invTag = new Nbt.Tag(Nbt.TAG_LIST, "Inventory", lt);
        File out = new File("/tmp/slot_out");
        Editor.processDir(src, out, opt, new File("/tmp/slot_work"), null);

        String json = Editor.readPlayerSectionJson(out, "Inventory");
        Object o = Json.parse(json);
        List<?> items = (List<?>) o;
        System.out.println("写回后格子 = " + items.size() + "（应与原来一致）");

        Nbt.Tag after = Editor.readPlayerSectionTag(out, "Inventory");
        int diff = 0, same = 0;
        for (Nbt.Tag it : ((Nbt.ListTag) after.value).items) {
            Nbt.Compound c = (Nbt.Compound) it.value;
            int s = ((Number) c.get("Slot").value).intValue();
            String nowJson = NbtJson.toJson(it);
            String oldJsonStr = oldJson.get(s);
            if (s == 0) {
                System.out.println("槽 0 现在是：" + c.get("Name").value + " × " + c.get("Count").value
                        + "（类型 count=" + c.get("Count").type + "）");
            } else if (nowJson.equals(oldJsonStr)) same++;
            else { diff++; System.out.println("槽 " + s + " 意外变化"); }
        }
        System.out.println("其它格保持不变的 = " + same + "，意外变化 = " + diff);

        byte[] cur = BedrockDb.readFile(new File(out, "db/CURRENT"));
        String mf = null;
        for (File f : new File(out, "db").listFiles()) if (f.getName().startsWith("MANIFEST")) mf = f.getName();
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, mf) : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(new File(out, "db"), key, null);
        byte[] val = null;
        for (BedrockDb.Entry e : all) if ("~local_player".equals(new String(e.key, "ISO-8859-1"))) val = e.value;
        System.out.println("键总数 = " + all.size() + "，Pos 仍在 = " + (PlayerEdit.readPos(val) != null));
        all.clear();
    }
}

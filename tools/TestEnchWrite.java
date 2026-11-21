import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

/** 附魔写入回归：在 slot 1 物品的 tag.ench 里加两条附魔，走完整流程再读回。 */
public class TestEnchWrite {
    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        Nbt.Tag inv = Editor.readPlayerSectionTag(src, "Inventory");
        Nbt.ListTag lt = (Nbt.ListTag) inv.value;

        Nbt.ListTag out = new Nbt.ListTag();
        out.elemType = Nbt.TAG_COMPOUND;
        for (Nbt.Tag item : lt.items) {
            Nbt.Compound c = (Nbt.Compound) item.value;
            int slot = ((Number) c.get("Slot").value).intValue();
            if (slot == 1) {
                Nbt.Tag tag = c.get("tag");
                if (tag == null || tag.type != Nbt.TAG_COMPOUND) {
                    tag = new Nbt.Tag(Nbt.TAG_COMPOUND, "tag", new Nbt.Compound());
                    c.put("tag", tag);
                }
                Nbt.Compound tc = (Nbt.Compound) tag.value;
                Nbt.ListTag ench = new Nbt.ListTag();
                ench.elemType = Nbt.TAG_COMPOUND;
                for (int[] e : new int[][]{{9, 5}, {17, 3}}) {
                    Nbt.Compound ec = new Nbt.Compound();
                    ec.put("id", new Nbt.Tag(Nbt.TAG_SHORT, "id", (short) e[0]));
                    ec.put("lvl", new Nbt.Tag(Nbt.TAG_SHORT, "lvl", (short) e[1]));
                    ench.items.add(new Nbt.Tag(Nbt.TAG_COMPOUND, "", ec));
                }
                tc.put("ench", new Nbt.Tag(Nbt.TAG_LIST, "ench", ench));
                System.out.println("改的是 " + c.get("Name").value + "（槽 1）");
            }
            out.items.add(item);
        }

        Editor.Options opt = new Editor.Options();
        opt.purgeEntities = false;
        opt.clearDigests = false;
        opt.invSection = "Inventory";
        opt.invTag = new Nbt.Tag(Nbt.TAG_LIST, "Inventory", out);
        File dst = new File("/tmp/ench_out");
        Editor.processDir(src, dst, opt, new File("/tmp/ench_work"), null);

        Nbt.Tag after = Editor.readPlayerSectionTag(dst, "Inventory");
        for (Nbt.Tag item : ((Nbt.ListTag) after.value).items) {
            Nbt.Compound c = (Nbt.Compound) item.value;
            if (((Number) c.get("Slot").value).intValue() != 1) continue;
            Nbt.Compound tc = (Nbt.Compound) c.get("tag").value;
            Nbt.ListTag ench = (Nbt.ListTag) tc.get("ench").value;
            System.out.println("写回后 ench 项数 = " + ench.items.size() + "，elemType = " + ench.elemType);
            for (Nbt.Tag e : ench.items) {
                Nbt.Compound ec = (Nbt.Compound) e.value;
                System.out.println("  id=" + ec.get("id").value + "(type " + ec.get("id").type + ")"
                        + " lvl=" + ec.get("lvl").value + "(type " + ec.get("lvl").type + ")");
            }
        }
        // 整个存档键数 + Pos
        byte[] cur = BedrockDb.readFile(new File(dst, "db/CURRENT"));
        String mf = null;
        for (File f : new File(dst, "db").listFiles()) if (f.getName().startsWith("MANIFEST")) mf = f.getName();
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, mf) : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(new File(dst, "db"), key, null);
        byte[] val = null;
        for (BedrockDb.Entry e : all) if ("~local_player".equals(new String(e.key, "ISO-8859-1"))) val = e.value;
        System.out.println("键总数 = " + all.size() + "，Pos 正常 = " + (PlayerEdit.readPos(val) != null));
        all.clear();
    }
}

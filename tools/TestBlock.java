import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class TestBlock {
    static int bad = 0;
    static void check(boolean ok, String msg) { System.out.println((ok ? "OK  " : "BAD ") + msg); if (!ok) bad++; }

    static BedrockDb.Entry find(List<BedrockDb.Entry> all, byte[] key) {
        for (BedrockDb.Entry e : all) {
            if (e.value == null || e.key.length != key.length) continue;
            boolean same = true;
            for (int i = 0; i < key.length; i++) if (e.key[i] != key[i]) { same = false; break; }
            if (same) return e;
        }
        return null;
    }

    static BlockEdit.Layer layerOf(byte[] blob) { return BlockEdit.firstLayer(blob); }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0], "db");
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, Editor.dbKeyOf(db), null);

        byte[] key = BlockEdit.subChunkKey(0, -1, -1, 0);
        BedrockDb.Entry e = find(all, key);
        check(e != null, "找到玩家所在区块的子区块");
        byte[] before = e.value.clone();
        int[] beforeIdx = new int[4096];
        {
            BlockEdit.Layer l = layerOf(before);
            for (int i = 0; i < 4096; i++) beforeIdx[i] = BlockEdit.getIndex(l, i);
        }
        // 找一个空气格（调色板索引 0）
        int target = -1;
        for (int i = 0; i < 4096; i++) if (beforeIdx[i] == 0) { target = i; break; }
        check(target >= 0, "找到一格空气 idx=" + target);
        int lx = target >> 8, lz = (target >> 4) & 15, ly = target & 15;
        int bx = -1 * 16 + lx, bz = -1 * 16 + lz, by = 0 * 16 + ly;
        BlockEdit.Result r = BlockEdit.setBlock(all, 0, bx, by, bz, "minecraft:stone", new LinkedHashMap<String, String>());
        System.out.println("结果：" + r.message);

        byte[] after = e.value;
        BlockEdit.Layer la = layerOf(after);
        int changed = 0;
        for (int i = 0; i < 4096; i++) if (BlockEdit.getIndex(la, i) != beforeIdx[i]) changed++;
        check(changed == 1, "只有目标格变化，变化数=" + changed);
        int pal = BlockEdit.getIndex(la, target);
        check(pal > 0, "目标格指向新调色板项 " + pal);
        Nbt.Tag t = la.palette.get(pal);
        Nbt.Compound c = (Nbt.Compound) t.value;
        check("minecraft:stone".equals(String.valueOf(c.get("name").value)), "调色板项是 stone：" + c.get("name").value);
        check(la.bits >= 5, "位宽 " + la.bits);

        // 新建子区块（y=200）
        BlockEdit.Result r2 = BlockEdit.setBlock(all, 0, 10, 200, 10, "minecraft:diamond_block", new LinkedHashMap<String, String>());
        System.out.println("结果：" + r2.message);
        byte[] k2 = BlockEdit.subChunkKey(0, Math.floorDiv(10, 16), Math.floorDiv(10, 16), Math.floorDiv(200, 16));
        BedrockDb.Entry e2 = find(all, k2);
        check(e2 != null && e2.value != null, "新子区块已写入");
        BlockEdit.Layer l2 = layerOf(e2.value);
        int idx2 = ((Math.floorMod(10, 16)) << 8) | ((Math.floorMod(10, 16)) << 4) | Math.floorMod(200, 16);
        check(BlockEdit.getIndex(l2, idx2) > 0, "新子区块目标格已设置");

        // 位宽升级：同一个新子区块里再放两种不同方块（1 位只能放 2 项）
        BlockEdit.setBlock(all, 0, 11, 200, 10, "minecraft:gold_block", new LinkedHashMap<String, String>());
        BlockEdit.setBlock(all, 0, 12, 200, 10, "minecraft:emerald_block", new LinkedHashMap<String, String>());
        int idx3 = ((Math.floorMod(11, 16)) << 8) | ((Math.floorMod(10, 16)) << 4) | Math.floorMod(200, 16);
        int idx4 = ((Math.floorMod(12, 16)) << 8) | ((Math.floorMod(10, 16)) << 4) | Math.floorMod(200, 16);
        BlockEdit.Layer l3 = layerOf(e2.value);
        int i3 = BlockEdit.getIndex(l3, idx3), i4 = BlockEdit.getIndex(l3, idx4);
        String n3 = String.valueOf(((Nbt.Compound) l3.palette.get(i3).value).get("name").value);
        String n4 = String.valueOf(((Nbt.Compound) l3.palette.get(i4).value).get("name").value);
        check("minecraft:gold_block".equals(n3), "升级后 gold 正确：" + n3);
        check("minecraft:emerald_block".equals(n4), "升级后 emerald 正确：" + n4);
        check(l3.bits >= 2, "位宽已升级到 " + l3.bits);

        // 状态值写入
        BlockEdit.setBlock(all, 0, 12, 200, 11, "minecraft:oak_log",
                new LinkedHashMap<String, String>() {{ put("pillar_axis", "x"); }});
        int idx5 = ((Math.floorMod(12, 16)) << 8) | ((Math.floorMod(11, 16)) << 4) | Math.floorMod(200, 16);
        BlockEdit.Layer l4 = layerOf(e2.value);
        int i5 = BlockEdit.getIndex(l4, idx5);
        Nbt.Compound st = (Nbt.Compound) ((Nbt.Compound) l4.palette.get(i5).value).get("states").value;
        check("x".equals(String.valueOf(st.get("pillar_axis").value))
                && st.get("pillar_axis").type == Nbt.TAG_STRING, "带状态写入 pillar_axis=x");

        System.out.println(bad == 0 ? "ALL PASS" : "FAIL " + bad);
        if (bad > 0) System.exit(1);
    }
}

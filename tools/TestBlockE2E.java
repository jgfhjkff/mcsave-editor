import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class TestBlockE2E {
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

    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File out = new File(a[1]);
        File work = new File(a[2]);

        List<BedrockDb.Entry> before = BedrockDb.readAll(new File(src, "db"), Editor.dbKeyOf(new File(src, "db")), null);
        int nBefore = 0;
        for (BedrockDb.Entry e : before) if (e.value != null) nBefore++;
        byte[] pv = null;
        BlockEdit.Layer l0 = BlockEdit.firstLayer(find(before, BlockEdit.subChunkKey(0, -1, -1, 0)).value);
        int[] idx0 = new int[4096];
        for (int i = 0; i < 4096; i++) idx0[i] = BlockEdit.getIndex(l0, i);

        Editor.Options opt = new Editor.Options();
        opt.purgeEntities = false;
        opt.clearDigests = false;
        opt.blockEdit = true;
        opt.blockDim = 0;
        opt.blockX = -16; opt.blockY = 0; opt.blockZ = -16;
        opt.blockName = "minecraft:stone";
        opt.blockStates = "";
        Editor.Stats st = Editor.processDir(src, out, opt, work, null);
        System.out.println("写入说明：" + st.blockMessage);

        File odb = new File(out, "db");
        List<BedrockDb.Entry> after = BedrockDb.readAll(odb, Editor.dbKeyOf(odb), null);
        int nAfter = 0;
        for (BedrockDb.Entry e : after) if (e.value != null) nAfter++;
        check(nAfter >= nBefore, "键数量没有减少 " + nBefore + " → " + nAfter);

        BedrockDb.Entry e = find(after, BlockEdit.subChunkKey(0, -1, -1, 0));
        check(e != null, "输出库里仍有该子区块");
        BlockEdit.Layer l1 = BlockEdit.firstLayer(e.value);
        // 目标格 = 局部 (0,0,0) → index 0
        int val = BlockEdit.getIndex(l1, 0);
        String name = String.valueOf(((Nbt.Compound) l1.palette.get(val).value).get("name").value);
        check("minecraft:stone".equals(name), "目标格已是 stone：调色板[" + val + "]=" + name);
        int diff = 0;
        for (int i = 1; i < 4096; i++) if (BlockEdit.getIndex(l1, i) != idx0[i]) diff++;
        check(diff == 0, "其它 4095 格未变，差异=" + diff);

        // 玩家数据仍可读
        boolean playerOk = false;
        for (BedrockDb.Entry pe : after) {
            String k = new String(pe.key, java.nio.charset.StandardCharsets.UTF_8);
            if (k.equals("~local_player") && pe.value != null) {
                double[] pos = PlayerEdit.readPos(pe.value);
                playerOk = Math.abs(pos[0] - (-6.3)) < 0.01;
            }
        }
        check(playerOk, "玩家坐标仍可解析");

        // 第二轮：新建子区块 + 模组方块 + 状态
        Editor.Options o2 = new Editor.Options();
        o2.purgeEntities = false;
        o2.clearDigests = false;
        o2.blockEdit = true;
        o2.blockX = 10; o2.blockY = 200; o2.blockZ = 10;
        o2.blockName = "starlight:starlight_switch";
        o2.blockStates = "open_bit=1";
        Editor.Stats st2 = Editor.processDir(out, new File(a[3]), o2, new File(a[4]), null);
        System.out.println("第二轮说明：" + st2.blockMessage);

        File odb2 = new File(a[3], "db");
        List<BedrockDb.Entry> after2 = BedrockDb.readAll(odb2, Editor.dbKeyOf(odb2), null);
        byte[] k2 = BlockEdit.subChunkKey(0, 0, 0, 12);
        BedrockDb.Entry e2 = find(after2, k2);
        check(e2 != null, "新子区块键存在");
        BlockEdit.Layer l2 = BlockEdit.firstLayer(e2.value);
        int idx = ((Math.floorMod(10, 16)) << 8) | ((Math.floorMod(10, 16)) << 4) | Math.floorMod(200, 16);
        int v2 = BlockEdit.getIndex(l2, idx);
        Nbt.Compound pc = (Nbt.Compound) l2.palette.get(v2).value;
        String nm = String.valueOf(pc.get("name").value);
        Nbt.Compound sts = (Nbt.Compound) pc.get("states").value;
        check("starlight:starlight_switch".equals(nm), "模组方块写入 " + nm);
        check(sts.get("open_bit") != null && sts.get("open_bit").type == Nbt.TAG_BYTE
                && ((Number) sts.get("open_bit").value).intValue() == 1, "open_bit=1 以 byte 写入");

        System.out.println(bad == 0 ? "ALL PASS" : "FAIL " + bad);
        if (bad > 0) System.exit(1);
    }
}

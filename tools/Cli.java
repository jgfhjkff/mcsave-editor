import com.nmsave.editor.*;

import java.io.File;

/**
 * 桌面端自测入口（不打包进 APK）。
 * 用法：java com.nmsave.editor.Cli <输入zip> <输出zip> [选项]
 * 选项：entities|digests|player|repack
 */
public class Cli {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: Cli <in.zip> <out.zip> [entities,digests,player,repack]");
            return;
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        Editor.Options opt = new Editor.Options();
        if (args.length >= 3) {
            opt.purgeEntities = false;
            opt.clearDigests = false;
            for (String a : args[2].split(",")) {
                if (a.equals("entities")) opt.purgeEntities = true;
                if (a.equals("digests")) opt.clearDigests = true;
                if (a.equals("player")) opt.clearPlayer = true;
                if (a.equals("repack")) opt.repackOnly = true;
                if (a.startsWith("kinds=")) {
                    opt.purgeEntities = true;
                    for (String k : a.substring(6).split("[+]")) opt.entityKinds.add(k);
                }
                if (a.startsWith("gt=")) opt.gameType = Integer.parseInt(a.substring(3));
                if (a.startsWith("diff=")) opt.difficulty = Integer.parseInt(a.substring(5));
                if (a.startsWith("cheat=")) opt.cheats = a.endsWith("1");
                if (a.startsWith("pvp=")) opt.pvp = a.endsWith("1");
                if (a.startsWith("keepinv=")) opt.keepInventory = a.endsWith("1");
                if (a.startsWith("day=")) opt.daylight = a.endsWith("1");
                if (a.startsWith("time=")) opt.time = Long.parseLong(a.substring(5));
                if (a.startsWith("id=")) for (String k : a.substring(3).split("[+]")) opt.entityIds.add(k);
                if (a.startsWith("pos=")) {
                    String[] q = a.substring(4).split("/");
                    opt.posX = Double.parseDouble(q[0]);
                    opt.posY = Double.parseDouble(q[1]);
                    opt.posZ = Double.parseDouble(q[2]);
                }
                if (a.startsWith("item=")) {
                    String[] q = a.substring(5).split("/");
                    opt.heldItem = q[0];
                    if (q.length > 1) opt.heldCount = Integer.parseInt(q[1]);
                    if (q.length > 2) opt.heldAux = Integer.parseInt(q[2]);
                }
            }
        }
        Editor.debug = true;
        File work = new File("build/cli-work");
        Editor.Progress cb = new Editor.Progress() {
            public void onProgress(int pct, String msg) {
                System.out.println("[" + pct + "%] " + msg);
            }
        };
        Editor.Stats st = Editor.process(in, out, opt, work, cb);
        System.out.println("世界名: " + st.worldName);
        System.out.println("世界目录: " + st.worldDir);
        System.out.println("加密: " + st.encrypted);
        System.out.println("实体: " + st.entityCount + " (玩家 " + st.playerEntities + ")");
        System.out.println("digp/旧实体索引: " + st.digestCount);
        System.out.println("保留键: " + st.keptCount + "，实际删除: " + st.purged);
        System.out.println("类别分布: " + st.byKind);
        System.out.println("原玩家坐标: " + (st.playerPos == null ? "无" : st.playerPos[0] + "," + st.playerPos[1] + "," + st.playerPos[2]));
        System.out.println("原手持物品: " + (st.mainhand == null ? "无" : st.mainhand));
        for (java.util.Map.Entry<String, Integer> e : st.byType.entrySet()) {
            System.out.println("  " + e.getKey() + " x" + e.getValue());
        }
        System.out.println("输出: " + out.getAbsolutePath() + " (" + out.length() + " 字节)");
    }
}

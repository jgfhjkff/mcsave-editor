import com.nmsave.editor.*;
import java.io.*;
import java.util.*;

/** 生成"合法 NBT 示例包"（严格版 + 简洁版），真实存档里的样例直接取自玩家背包。 */
public class GenExamples {
    static final int B = Nbt.TAG_BYTE, S = Nbt.TAG_SHORT, I = Nbt.TAG_INT, L = Nbt.TAG_LONG, ST = Nbt.TAG_STRING,
            LS = Nbt.TAG_LIST, CP = Nbt.TAG_COMPOUND, BA = Nbt.TAG_BYTE_ARRAY;

    public static void main(String[] a) throws Exception {
        List<String[]> names = new ArrayList<>();
        List<Nbt.Tag> items = new ArrayList<>();

        // 真实存档里各取一件（顺带证明这些结构真的存在于你的档里）
        Nbt.Tag inv = Editor.readPlayerSectionTag(new File(a[0]), "Inventory");
        boolean gotBlock = false, gotCmd = false, gotFw = false;
        for (Nbt.Tag it : ((Nbt.ListTag) inv.value).items) {
            Nbt.Compound c = (Nbt.Compound) it.value;
            String n = String.valueOf(c.get("Name").value);
            if (!gotBlock && c.get("Block") != null) {
                gotBlock = true;
                add(names, items, "带方块状态的方块物品", "真实存档 · Block{name,states,val,version}", it);
            } else if (!gotCmd && n.equals("minecraft:repeating_command_block")) {
                gotCmd = true;
                add(names, items, "命令方块", "真实存档 · Block 状态 + Command 参数", it);
            } else if (!gotFw && n.equals("minecraft:firework_rocket")) {
                gotFw = true;
                add(names, items, "烟花火箭", "真实存档 · Fireworks 结构（含 byte[] 颜色）", it);
            }
        }

        // 合成：附魔钻石剑
        Nbt.Tag sword = item("minecraft:diamond_sword", 1, 0, ench(new int[][]{{9, 5}, {17, 3}, {26, 1}}));
        add(names, items, "附魔钻石剑", "锋利 V / 耐久 III / 经验修补 I", sword);

        // 合成：附魔书
        Nbt.Tag book = item("minecraft:enchanted_book", 1, 0, ench(new int[][]{{0, 4}, {3, 2}}));
        add(names, items, "附魔书", "保护 IV / 爆炸保护 II（基岩版附魔书也用 ench）", book);

        // 合成：改名 + Lore + 铁砧惩罚
        Nbt.Tag named = item("minecraft:diamond_pickaxe", 1, 0, null);
        Nbt.Compound tp = (Nbt.Compound) named.value;
        Nbt.Compound tc = new Nbt.Compound();
        Nbt.Compound disp = new Nbt.Compound();
        disp.put("Name", new Nbt.Tag(ST, "Name", "§b测试镐"));
        Nbt.ListTag lore = new Nbt.ListTag(); lore.elemType = ST;
        lore.items.add(new Nbt.Tag(ST, "", "第一行说明"));
        lore.items.add(new Nbt.Tag(ST, "", "第二行 §e彩色"));
        disp.put("Lore", new Nbt.Tag(LS, "Lore", lore));
        tc.put("display", new Nbt.Tag(CP, "display", disp));
        tc.put("RepairCost", new Nbt.Tag(I, "RepairCost", 5));
        cput(tp, "tag", new Nbt.Tag(CP, "tag", tc));
        add(names, items, "改名 + 两行 Lore", "tag.display（真实存档里确认存在 display/Lore）", named);

        // 合成：弩 / 三叉戟 / 不可破坏
        add(names, items, "附魔弩", "多重射击 III / 穿透 IV / 快速装填 III",
                item("minecraft:crossbow", 1, 0, ench(new int[][]{{32, 3}, {33, 4}, {34, 3}})));
        add(names, items, "附魔三叉戟", "穿刺 V / 激流 III / 忠诚 I / 引雷 I",
                item("minecraft:trident", 1, 0, ench(new int[][]{{28, 5}, {29, 3}, {30, 1}, {31, 1}})));

        Nbt.Tag unbreak = item("minecraft:diamond_pickaxe", 1, 0, null);
        Nbt.Compound uc = (Nbt.Compound) unbreak.value;
        Nbt.Compound ut = new Nbt.Compound();
        ut.put("Unbreakable", new Nbt.Tag(B, "Unbreakable", (byte) 1));
        cput(uc, "tag", new Nbt.Tag(CP, "tag", ut));
        add(names, items, "不可破坏的镐", "tag.Unbreakable=1（常见标签，未在本机实机验证）", unbreak);

        add(names, items, "石头 ×64", "最简结构；注意 WasPickedUp 大写（真实存档就这么写）",
                item("minecraft:stone", 64, 0, null));

        String dir = a[1];
        write(dir + "/背包NBT示例_严格版.json", true, names, items);
        write(dir + "/背包NBT示例_简洁版.json", false, names, items);
        // 自检：严格版文本 → Tag → 严格版文本，应逐字符一致（保证"合法且可无损回写"）
        int bad = 0;
        for (int i = 0; i < items.size(); i++) {
            String typed = NbtJson.toJsonTyped(items.get(i));
            Nbt.Tag back = NbtJson.fromJsonAny(typed, null);
            String again = NbtJson.toJsonTyped(back);
            if (!typed.equals(again)) { bad++; System.out.println("✗ 严格版往返不一致：" + names.get(i)[0]); }
        }
        System.out.println("已写出 " + items.size() + " 个示例，严格版往返不一致 = " + bad);
    }

    static void add(List<String[]> n, List<Nbt.Tag> it, String name, String desc, Nbt.Tag item) {
        n.add(new String[]{name, desc});
        it.add(item);
    }

    static Nbt.Tag item(String id, int count, int dmg, Nbt.Tag tag) {
        Nbt.Compound c = new Nbt.Compound();
        c.put("Name", new Nbt.Tag(ST, "Name", id));
        c.put("Count", new Nbt.Tag(B, "Count", (byte) count));
        c.put("Damage", new Nbt.Tag(S, "Damage", (short) dmg));
        c.put("Slot", new Nbt.Tag(B, "Slot", (byte) 0));
        c.put("WasPickedUp", new Nbt.Tag(B, "WasPickedUp", (byte) 1));
        if (tag != null) c.put("tag", tag);
        return new Nbt.Tag(CP, "", c);
    }

    static Nbt.Tag ench(int[][] ids) {
        Nbt.Compound tc = new Nbt.Compound();
        Nbt.ListTag lt = new Nbt.ListTag(); lt.elemType = CP;
        for (int[] e : ids) {
            Nbt.Compound ec = new Nbt.Compound();
            ec.put("id", new Nbt.Tag(S, "id", (short) e[0]));
            ec.put("lvl", new Nbt.Tag(S, "lvl", (short) e[1]));
            lt.items.add(new Nbt.Tag(CP, "", ec));
        }
        tc.put("ench", new Nbt.Tag(LS, "ench", lt));
        return new Nbt.Tag(CP, "tag", tc);
    }

    static void cput(Nbt.Compound c, String k, Nbt.Tag t) { c.put(k, t); }

    static void write(String path, boolean typed, List<String[]> names, List<Nbt.Tag> items) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append("  {\n    \"名称\": ");
            sb.append('"').append(esc(names.get(i)[0])).append("\",\n    \"说明\": ");
            sb.append('"').append(esc(names.get(i)[1])).append("\",\n    \"物品\": ");
            String json = typed ? NbtJson.toJsonTyped(items.get(i)) : NbtJson.toJson(items.get(i));
            // 缩进一下，便于阅读
            String[] lines = json.split("\n");
            for (int l = 0; l < lines.length; l++) {
                if (l == 0) sb.append(lines[l]);
                else sb.append("\n    ").append(lines[l]);
            }
            sb.append(i < items.size() - 1 ? "\n  },\n" : "\n  }\n");
        }
        sb.append("]\n");
        Writer w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
        try { w.write(sb.toString()); } finally { w.close(); }
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}

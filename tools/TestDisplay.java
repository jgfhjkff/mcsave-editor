import com.nmsave.editor.*;

public class TestDisplay {
    static int bad = 0;

    static void eq(String a, String b, String msg) {
        if (a == null ? b == null : a.equals(b)) System.out.println("OK  " + msg);
        else { bad++; System.out.println("BAD " + msg + "\n    got=" + a + "\n    exp=" + b); }
    }

    public static void main(String[] a) throws Exception {
        String base = "{\"Name\":\"minecraft:diamond_sword\",\"Count\":1,\"Damage\":0,"
                + "\"Slot\":1,\"WasPickedUp\":1}";
        Nbt.Tag item = NbtJson.fromJsonAny(base, null);
        Nbt.Tag it = item;

        // 初始没有 display
        eq(ItemDisplay.displayNameOf(it), "", "空物品无显示名");
        eq(ItemDisplay.hasKeepOnDeath(it) ? "1" : "0", "0", "空物品无 keep_on_death");

        // 写名字 + 介绍 + 掉落
        String out = ItemDisplay.patchDisplay(base, null, "弑神之刃", "第一条\n\n第二条", true);
        System.out.println("OUT " + out);
        Nbt.Tag t2 = NbtJson.fromJsonAny(out, null);
        eq(ItemDisplay.displayNameOf(t2), "弑神之刃", "写名字");
        eq(ItemDisplay.displayLoreOf(t2), "第一条\n第二条", "写介绍（空行被丢）");
        eq(ItemDisplay.hasKeepOnDeath(t2) ? "1" : "0", "1", "写入死亡不掉落");
        // 原有字段仍在
        Nbt.Compound c = (Nbt.Compound) t2.value;
        eq(String.valueOf(c.get("Name").value), "minecraft:diamond_sword", "原 Name 未被改");
        eq(String.valueOf(c.get("Count").value), "1", "原 Count 在");

        // 清空名字与介绍，保留 keep_on_death
        String out2 = ItemDisplay.patchDisplay(out, null, "", "", true);
        Nbt.Tag t3 = NbtJson.fromJsonAny(out2, null);
        eq(ItemDisplay.displayNameOf(t3), "", "清空名字");
        eq(ItemDisplay.displayLoreOf(t3), "", "清空介绍");
        eq(ItemDisplay.hasKeepOnDeath(t3) ? "1" : "0", "1", "掉落开关不受影响");

        // 关掉落
        String out3 = ItemDisplay.patchDisplay(out, null, "弑神之刃", "第一条", false);
        Nbt.Tag t4 = NbtJson.fromJsonAny(out3, null);
        eq(ItemDisplay.hasKeepOnDeath(t4) ? "1" : "0", "0", "关闭死亡不掉落");

        // display 原有其它字段（如 color）要保留
        String withColor = "{\"Name\":\"minecraft:leather_helmet\",\"Count\":1,"
                + "\"tag\":{\"display\":{\"Name\":\"旧名\",\"color\":12345}}}";
        String out4 = ItemDisplay.patchDisplay(withColor, null, "新名", "", true);
        Nbt.Tag t5 = NbtJson.fromJsonAny(out4, null);
        Nbt.Compound d = (Nbt.Compound) ((Nbt.Compound) ((Nbt.Compound) t5.value).get("tag").value)
                .get("display").value;
        eq(String.valueOf(d.get("Name").value), "新名", "改名字");
        eq(String.valueOf(d.get("color").value), "12345", "display 其它字段保留");

        // userData.display 原位置跟随
        String ud = "{\"Name\":\"minecraft:apple\",\"Count\":1,"
                + "\"tag\":{\"userData\":{\"display\":{\"Name\":\"旧名\"}}}}";
        String out5 = ItemDisplay.patchDisplay(ud, null, "新名", "描述", true);
        System.out.println("OUT5 " + out5);
        Nbt.Tag t6 = NbtJson.fromJsonAny(out5, null);
        Nbt.Compound tc = (Nbt.Compound) ((Nbt.Compound) t6.value).get("tag").value;
        Nbt.Compound u = (Nbt.Compound) ((Nbt.TAG_COMPOUND == ((Nbt.Tag) tc.get("userData")).type)
                ? ((Nbt.Tag) tc.get("userData")).value : null);
        eq(u != null ? "1" : "0", "1", "userData 还在");
        eq(ItemDisplay.displayNameOf(t6), "新名", "userData.display 写回原位");
        eq(tc.get("display") != null ? "出现 tag.display 重复" : "0", "0", "没有另造 tag.display");

        System.out.println(bad == 0 ? "ALL PASS" : ("FAIL " + bad));
        if (bad > 0) System.exit(1);
    }
}

import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

/** 单格：整段物品 NBT 文本 -> Tag 的往返一致性。 */
public class TestSlotJson {
    public static void main(String[] a) throws Exception {
        Nbt.Tag inv = Editor.readPlayerSectionTag(new File(a[0]), "Inventory");
        Nbt.ListTag lt = (Nbt.ListTag) inv.value;
        int bad = 0;
        for (Nbt.Tag item : lt.items) {
            String json = NbtJson.toJson(item);
            Nbt.Tag back = NbtJson.fromJsonAny(json, item);
            if (!json.equals(NbtJson.toJson(back))) bad++;
            byte[] b1 = Nbt.valueBytes(Nbt.TAG_COMPOUND, item.value);
            byte[] b2 = Nbt.valueBytes(Nbt.TAG_COMPOUND, back.value);
            if (!java.util.Arrays.equals(b1, b2)) bad++;
        }
        System.out.println("逐格往返不一致数 = " + bad + "（应为 0），共 " + lt.items.size() + " 格");

        String Q = "\"";
        String def = "{" + Q + "Name" + Q + ": " + Q + "stone" + Q + ", "
                + Q + "Count" + Q + ": 1, " + Q + "Damage" + Q + ": 0, "
                + Q + "Slot" + Q + ": 7, " + Q + "wasPickedUp" + Q + ": 1}";
        Nbt.Tag t = NbtJson.fromJsonAny(def, null);
        Nbt.Compound c = (Nbt.Compound) t.value;
        System.out.println("新建项 Name=" + c.get("Name").type + " Count=" + c.get("Count").type
                + " Damage=" + c.get("Damage").type + " Slot=" + c.get("Slot").type);

        String wrap = "{" + Q + "id" + Q + ":{" + Q + "__type__" + Q + ":" + Q + "short" + Q
                + "," + Q + "__value__" + Q + ":9}}";
        Nbt.Compound wc = (Nbt.Compound) NbtJson.fromJsonAny(wrap, null).value;
        System.out.println("包装后 id 类型=" + wc.get("id").type + " 值=" + wc.get("id").value);

        Nbt.Compound no = (Nbt.Compound) NbtJson.fromJsonAny("{" + Q + "Count" + Q + ":3}", null).value;
        System.out.println("缺 Name 时解析出的键 = " + no.keySet());
    }
}

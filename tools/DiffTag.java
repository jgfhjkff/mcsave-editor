import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

/** 找出 JSON round-trip 里类型/值不一致的路径。 */
public class DiffTag {
    static int shown = 0;
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
        for (BedrockDb.Entry e : all) if ("~local_player".equals(new String(e.key, "ISO-8859-1"))) { val = e.value; break; }
        all.clear();
        Nbt.Tag orig = PlayerEdit.readSection(val, "Inventory");
        String json = NbtJson.toJson(orig);
        byte[] nv = PlayerEdit.setInventorySection(val, "Inventory", json);
        Nbt.Tag back = PlayerEdit.readSection(nv, "Inventory");
        diff(orig, back, "Inventory");
        System.out.println("总差异条数（打印上限 40）: " + shown);
    }

    static void diff(Nbt.Tag x, Nbt.Tag y, String path) {
        if (x == null || y == null) { print(path, "null 一侧", x, y); return; }
        if (x.type != y.type) { print(path, "类型", x, y); return; }
        switch (x.type) {
            case 10: {
                Nbt.Compound cx = (Nbt.Compound) x.value, cy = (Nbt.Compound) y.value;
                for (String k : cx.keySet()) {
                    if (!cy.containsKey(k)) print(path + "." + k, "键丢失", cx.get(k), null);
                    else diff(cx.get(k), cy.get(k), path + "." + k);
                }
                for (String k : cy.keySet()) if (!cx.containsKey(k)) print(path + "." + k, "多出键", null, cy.get(k));
                break;
            }
            case 9: {
                Nbt.ListTag lx = (Nbt.ListTag) x.value, ly = (Nbt.ListTag) y.value;
                if (lx.elemType != ly.elemType) print(path, "list elemType", x, y);
                if (lx.items.size() != ly.items.size()) { print(path, "项数", x, y); return; }
                for (int i = 0; i < lx.items.size(); i++) diff(lx.items.get(i), ly.items.get(i), path + "[" + i + "]");
                break;
            }
            default: {
                if (!sameScalar(x.value, y.value)) print(path, "值", x, y);
            }
        }
    }

    static void print(String path, String kind, Nbt.Tag x, Nbt.Tag y) {
        if (shown++ >= 40) return;
        System.out.println(kind + " @" + path + "  " + (x == null ? "-" : x.type + ":" + brief(x)) 
                + " -> " + (y == null ? "-" : y.type + ":" + brief(y)));
    }

    static boolean sameScalar(Object a, Object b) {
        if (a instanceof byte[] && b instanceof byte[]) return java.util.Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof int[] && b instanceof int[]) return java.util.Arrays.equals((int[]) a, (int[]) b);
        if (a instanceof long[] && b instanceof long[]) return java.util.Arrays.equals((long[]) a, (long[]) b);
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }

    static String brief(Nbt.Tag t) {
        if (t.value instanceof Nbt.Compound) return "{...}";
        if (t.value instanceof Nbt.ListTag) return "[" + ((Nbt.ListTag) t.value).items.size() + "]";
        if (t.value instanceof byte[]) return "byte[" + ((byte[]) t.value).length + "]";
        if (t.value instanceof int[]) return "int[" + ((int[]) t.value).length + "]";
        String s = String.valueOf(t.value);
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }
}

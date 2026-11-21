import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class TestPlayer {
    static void dump(Nbt.Tag t, String indent) {
        if (t.value instanceof Nbt.Compound) {
            for (Map.Entry<String, Nbt.Tag> e : ((Nbt.Compound) t.value).entrySet()) {
                Nbt.Tag v = e.getValue();
                String extra = "";
                if (v.type == Nbt.TAG_LIST) {
                    Nbt.ListTag lt = (Nbt.ListTag) v.value;
                    extra = " elemType=" + lt.elemType + " n=" + lt.items.size();
                } else if (v.value instanceof String) extra = " = " + v.value;
                else if (v.value instanceof Number) extra = " = " + v.value;
                System.out.println(indent + e.getKey() + " [t" + v.type + "]" + extra);
            }
        }
    }
    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, "ISO-8859-1");
            if (!k.startsWith("~local_player") && !k.equals("~local_player")) continue;
            System.out.println("KEY=" + k + " len=" + e.value.length);
            Nbt.Tag root = Nbt.parse(e.value, 0);
            dump(root, "  ");
            Nbt.Compound c = root.asCompound();
            Nbt.Tag inv = c.get("Inventory");
            if (inv != null && inv.value instanceof Nbt.ListTag) {
                Nbt.ListTag lt = (Nbt.ListTag) inv.value;
                System.out.println("Inventory n=" + lt.items.size());
                for (int i = 0; i < Math.min(3, lt.items.size()); i++) {
                    Nbt.Compound it = lt.items.get(i).asCompound();
                    System.out.print("  item[" + i + "]:");
                    for (Map.Entry<String, Nbt.Tag> en : it.entrySet())
                        System.out.print(" " + en.getKey() + "[t" + en.getValue().type + "]=" + en.getValue().value);
                    System.out.println();
                }
            }
            break;
        }
    }
}

import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class TestKind {
    public static void main(String[] a) throws Exception {
        for (String s : new String[]{"minecraft:boat", "minecraft:cow", "minecraft:parrot", "minecraft:item", "minecraft:xp_orb"})
            System.out.println(s + " -> " + Editor.kindOf(s));
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        Map<String, String> seen = new TreeMap<>();
        for (BedrockDb.Entry e : all) {
            if (!new String(e.key, "ISO-8859-1").startsWith("actorprefix") || e.value == null) continue;
            Nbt.Tag r = Nbt.parse(e.value, 0);
            Nbt.Tag idt = r.asCompound().get("identifier");
            String id = idt == null ? "null" : String.valueOf(idt.value);
            if (!seen.containsKey(id)) seen.put(id, Editor.kindOf(id));
        }
        System.out.println("id -> kind: " + seen);
    }
}

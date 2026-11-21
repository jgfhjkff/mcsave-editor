import com.nmsave.editor.*;
import java.io.File;
import java.util.List;
import java.util.Map;

public class TestNbt2 {
    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        int n = 0;
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, "ISO-8859-1");
            if (!k.startsWith("actorprefix") || e.value == null) continue;
            Nbt.Tag root = Nbt.parse(e.value, 0);
            Nbt.Compound c = root.asCompound();
            System.out.print("keys(" + c.size() + "):");
            for (Map.Entry<String, Nbt.Tag> en : c.entrySet()) {
                System.out.print(" " + en.getKey() + "[t" + en.getValue().type + "]");
            }
            System.out.println();
            if (++n >= 2) break;
        }
    }
}

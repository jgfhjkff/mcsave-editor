import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class TestPlayer3 {
    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000002") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        for (BedrockDb.Entry e : all) {
            if (!"~local_player".equals(new String(e.key, "ISO-8859-1"))) continue;
            System.out.println("value len=" + e.value.length);
            double[] p = PlayerEdit.readPos(e.value);
            System.out.println("pos=" + (p == null ? "null" : p[0] + "," + p[1] + "," + p[2]));
            System.out.println("mainhand=" + PlayerEdit.readMainhand(e.value));
            Map<String, PlayerEdit.Span> fields = PlayerEdit.scanFields(e.value);
            System.out.println("扫描到字段数=" + fields.size() + " 含Pos=" + fields.containsKey("Pos") + " 含Mainhand=" + fields.containsKey("Mainhand"));
            break;
        }
    }
}

import com.nmsave.editor.*;
import java.io.File;

public class DumpKeys {
    public static void main(String[] a) throws Exception {
        File f = new File(a[0]);
        byte[] d = BedrockDb.readFile(f);
        Nbt.Tag root = Nbt.parse(d, 8);
        Nbt.Compound c = root.asCompound();
        System.out.println("keys=" + c.size());
        for (String k : c.keySet()) {
            Nbt.Tag t = c.get(k);
            String v = "";
            if (t.value instanceof Number || t.value instanceof String || t.value instanceof Boolean) v = " = " + t.value;
            else if (t.value instanceof Nbt.ListTag) v = " list(" + ((Nbt.ListTag)t.value).items.size() + ")";
            else if (t.value instanceof Nbt.Compound) v = " compound(" + ((Nbt.Compound)t.value).size() + ")";
            System.out.println(k + "  type=" + t.type + v);
        }
    }
}

import com.nmsave.editor.*;
import java.io.File;

public class DumpInvJson {
    public static void main(String[] a) throws Exception {
        Nbt.Tag inv = Editor.readPlayerSectionTag(new File(a[0]), "Inventory");
        System.out.println(NbtJson.toJson(inv));
    }
}

import com.nmsave.editor.*;

import java.io.File;

/** 标准 LevelDB（国际版）读写自测。 */
public class TestIntl {
    public static void main(String[] a) throws Exception {
        File world = new File(a[0]);
        File out = new File(a[1]);
        File work = new File(a[2]);

        Editor.Stats s1 = Editor.scanDir(world, work, null);
        System.out.println("读取标准库: keys=" + s1.keptCount + " entities=" + s1.entityCount
                + " digp=" + s1.digestCount + " encrypted=" + s1.encrypted + " name=" + s1.worldName);

        Editor.Options o = new Editor.Options();
        o.repackOnly = true;          // 不删任何数据，只重建库
        Editor.Stats s2 = Editor.processDir(world, out, o, new File(work, "p"), null);
        System.out.println("重建标准库: keys=" + s2.keptCount);

        Editor.Stats s3 = Editor.scanDir(out, new File(work, "r"), null);
        System.out.println("回读: keys=" + s3.keptCount + " encrypted=" + s3.encrypted
                + " level.dat=" + new File(out, "level.dat").isFile());
        System.out.println("OK");
    }
}

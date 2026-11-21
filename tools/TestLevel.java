import com.nmsave.editor.*;
import java.io.File;

public class TestLevel {
    public static void main(String[] a) throws Exception {
        LevelDat.Info i = LevelDat.read(new File(a[0]));
        System.out.println("name=" + i.levelName + " ver=" + i.version);
        System.out.println("gameType=" + i.gameType + " difficulty=" + i.difficulty + " cheats=" + i.cheats
                + " pvp=" + i.pvp + " keepInv=" + i.keepInventory + " day=" + i.daylight);
        System.out.println("time=" + i.time + " seed=" + i.seed + " spawn=" + i.spawnX + "," + i.spawnY + "," + i.spawnZ + " player=" + i.hasPlayer + " keys=" + i.keys);
    }
}

package com.nmsave.editor;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 存档目录的发现、备份与回写。 */
public class WorldStore {

    public static class World {
        public File dir;
        public String id = "";        // 目录名（网易是世界 ID，形如 EdKLTbzEXh8=）
        public String name = "未命名世界";
        public String version = "";
        public long sizeBytes;
        public long modified;
        public boolean encrypted;     // db 是否为网易加密格式
        public boolean hasPlayer;     // level.dat 里带 Player
        public boolean readable = true;
        public String rootPath = "";  // 所属 minecraftWorlds 目录
        public String edition = "unknown"; // netease / intl
    }

    public static class ScanResult {
        public final List<World> worlds = new ArrayList<>();
        public final List<String> tried = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public boolean permissionDenied;
    }

    public static String baseDir() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    /** 常见存档根目录（网易版在前，国际版兜底）。 */
    static List<File> candidateRoots() {
        String b = baseDir();
        List<File> roots = new ArrayList<>();
        roots.add(new File(b + "/Android/data/com.netease.x19/files/minecraftWorlds"));
        roots.add(new File(b + "/Android/data/com.netease.x19/files/games/com.netease.x19/minecraftWorlds"));
        roots.add(new File(b + "/games/com.netease.x19/minecraftWorlds"));
        roots.add(new File(b + "/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds"));
        roots.add(new File(b + "/games/com.mojang/minecraftWorlds"));
        roots.add(new File(b + "/minecraftWorlds"));
        roots.add(new File(b + "/Android/data/com.netease.mc.mcbox/files/minecraftWorlds"));
        roots.add(new File(b + "/games/com.netease.mcbox/minecraftWorlds"));
        return roots;
    }

    static String editionOf(String path) {
        String p = path.toLowerCase();
        if (p.contains("com.mojang") || p.contains("/games/com.mojang")) return "intl";
        if (p.contains("netease") || p.contains("mcbox") || p.contains("x19")) return "netease";
        return "unknown";
    }

    public static ScanResult scan() {
        return scan(true);
    }

    /** includeIntl=false 时不扫描国际基岩版（com.mojang）目录。 */
    public static ScanResult scan(boolean includeIntl) {
        ScanResult r = new ScanResult();
        List<File> roots = new ArrayList<>(candidateRoots());

        // 兜底：在 Android/data 与 games 下按名字找 minecraftWorlds（深度有限）
        List<File> found = new ArrayList<>();
        findByName(new File(baseDir() + "/Android/data"), "minecraftWorlds", 0, 3, found, r.errors);
        findByName(new File(baseDir() + "/games"), "minecraftWorlds", 0, 3, found, r.errors);
        for (File f : found) if (!roots.contains(f)) roots.add(f);

        for (File root : roots) {
            String ed = editionOf(root.getAbsolutePath());
            if ("intl".equals(ed) && !includeIntl) {
                r.tried.add(root.getAbsolutePath() + "（国际版，已在设置里关闭）");
                continue;
            }
            r.tried.add(root.getAbsolutePath() + (root.isDirectory() ? "" : "（不存在）"));
            if (!root.isDirectory()) continue;
            File[] kids = root.listFiles();
            if (kids == null) {
                r.permissionDenied = true;
                r.errors.add("无法列出 " + root.getAbsolutePath());
                continue;
            }
            for (File k : kids) {
                if (!k.isDirectory()) continue;
                World w = probe(k);
                if (w != null) {
                    w.rootPath = root.getAbsolutePath();
                    w.edition = ed;
                    if ("intl".equals(ed) && !includeIntl) continue;
                    r.worlds.add(w);
                }
            }
        }
        return r;
    }

    private static void findByName(File dir, String target, int depth, int maxDepth,
                                   List<File> out, List<String> errors) {
        if (depth > maxDepth) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (!k.isDirectory()) continue;
            if (k.getName().equals(target)) {
                if (!out.contains(k)) out.add(k);
            } else if (depth < maxDepth) {
                findByName(k, target, depth + 1, maxDepth, out, errors);
            }
        }
    }

    /** 判断一个目录是否是一个存档；不是则返回 null。 */
    public static World probe(File dir) {
        File levelDat = new File(dir, "level.dat");
        if (!levelDat.isFile()) return null;
        World w = new World();
        w.dir = dir;
        w.id = dir.getName();
        w.sizeBytes = sizeOf(dir);
        w.modified = levelDat.lastModified();
        File cur = new File(dir, "db/CURRENT");
        if (cur.isFile()) {
            try {
                w.encrypted = BedrockDb.isEncrypted(BedrockDb.readFile(cur));
            } catch (Throwable t) {
                w.encrypted = false;
            }
        }
        String datName = "";
        try {
            LevelDat.Info info = LevelDat.read(levelDat);
            if (info != null) {
                datName = info.levelName == null ? "" : info.levelName.trim();
                w.version = info.version;
                w.hasPlayer = info.hasPlayer;
            }
        } catch (Throwable t) {
            w.readable = false;
        }

        // 游戏里改过名的世界：当前名字写在 levelname.txt，level.dat 的 LevelName
        // 常常还是首次创建时的旧名，所以先取 levelname.txt。
        String txtName = "";
        File nameTxt = new File(dir, "levelname.txt");
        if (nameTxt.isFile()) {
            try {
                byte[] raw = BedrockDb.readFile(nameTxt);
                txtName = new String(raw, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("\uFEFF", "").trim();
            } catch (Throwable ignored) {
            }
        }
        if (!txtName.isEmpty()) w.name = txtName;
        else if (!datName.isEmpty()) w.name = datName;
        else w.name = w.id;
        return w;
    }

    public static long sizeOf(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        long n = 0;
        for (File k : kids) {
            n += k.isDirectory() ? sizeOf(k) : k.length();
        }
        return n;
    }

    public static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    public static String timeText(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    // ------------------------------------------------------------------ 备份列表

    public static class BackupEntry {
        public File dir;
        public String name = "";
        public String type = "部分备份";   // 整目录备份 / 部分备份
        public String worldId = "";
        public long size;
        public long time;
    }

    /** 列出 /sdcard/NM存档备份 下的所有备份文件夹。 */
    public static List<BackupEntry> listBackups() {
        List<BackupEntry> out = new ArrayList<>();
        File root = backupRoot();
        File[] kids = root.listFiles();
        if (kids == null) return out;
        for (File k : kids) {
            if (!k.isDirectory()) continue;
            BackupEntry b = new BackupEntry();
            b.dir = k;
            b.name = k.getName();
            b.size = sizeOf(k);
            b.time = k.lastModified();
            File directDb = new File(k, "db");
            if (directDb.isDirectory()) {
                b.type = "部分备份（db + level.dat）";
                File note = new File(k, "备份说明.txt");
                if (note.isFile()) {
                    try {
                        String t = new String(BedrockDb.readFile(note), java.nio.charset.StandardCharsets.UTF_8);
                        for (String line : t.split("\n")) {
                            if (line.startsWith("目录：")) {
                                String path = line.substring(3).trim();
                                int slash = path.lastIndexOf('/');
                                if (slash > 0) b.worldId = path.substring(slash + 1);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                File[] inner = k.listFiles();
                if (inner != null) {
                    for (File i : inner) {
                        if (i.isDirectory() && new File(i, "db").isDirectory()) {
                            b.type = "整目录备份";
                            b.worldId = i.getName();
                            break;
                        }
                    }
                }
            }
            if (b.worldId.isEmpty()) b.worldId = "(未知)";
            out.add(b);
        }
        // 新的排前面
        java.util.Collections.sort(out, new java.util.Comparator<BackupEntry>() {
            public int compare(BackupEntry a, BackupEntry b) {
                return Long.compare(b.time, a.time);
            }
        });
        return out;
    }

    public static String readNote(File dir) {
        File note = new File(dir, "备份说明.txt");
        if (!note.isFile()) return "（没有备份说明文件）";
        try {
            return new String(BedrockDb.readFile(note), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "（读取说明失败：" + t + "）";
        }
    }

    public static void deleteBackup(File dir) throws Exception {
        if (!dir.isDirectory()) throw new Exception("备份目录不存在");
        Editor.deleteRecursivePublic(dir);
        if (dir.exists()) throw new Exception("删除失败（可能没有写权限）");
    }

    // ------------------------------------------------------------------ 备份与回写

    static File backupRoot() {
        return new File(baseDir() + "/NM存档备份");
    }

    /** 整目录备份（不经过任何修改），备份到 /sdcard/NM存档备份/<世界名>_<时间>/<世界ID>/ 。 */
    public static File fullBackup(World w) throws Exception {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String safe = w.name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.length() > 24) safe = safe.substring(0, 24);
        File dst = new File(backupRoot(), safe + "_" + ts);
        File inner = new File(dst, w.id);
        inner.mkdirs();
        Editor.copyDir(w.dir, inner, null);
        writeNote(dst, w, "整目录备份（原样，未做任何修改）",
                "还原：把本目录下的 " + w.id + " 整个文件夹复制回 "
                        + (w.rootPath.isEmpty() ? "minecraftWorlds 目录" : w.rootPath) + " 即可。");
        return dst;
    }

    private static void writeNote(File dir, World w, String kind, String how) throws Exception {
        String info = "世界：" + w.name + "\n目录：" + w.dir.getAbsolutePath()
                + "\n类型：" + kind
                + "\n时间：" + timeText(System.currentTimeMillis())
                + "\n体积：" + sizeText(w.sizeBytes)
                + "\n\n" + how + "\n";
        FileOutputStream fo = new FileOutputStream(new File(dir, "备份说明.txt"));
        try {
            fo.write(info.getBytes("UTF-8"));
        } finally {
            fo.close();
        }
    }

    /** 备份到 /sdcard/NM存档备份/<世界名>_<时间>/ ，返回备份目录。 */
    public static File backup(World w) throws Exception {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String safe = w.name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.length() > 24) safe = safe.substring(0, 24);
        File dst = new File(backupRoot(), safe + "_" + ts);
        dst.mkdirs();

        File db = new File(w.dir, "db");
        if (db.isDirectory()) Editor.copyDir(db, new File(dst, "db"), null);
        File levelDat = new File(w.dir, "level.dat");
        if (levelDat.isFile()) Editor.copyFile(levelDat, new File(dst, "level.dat"));
        File icon = new File(w.dir, "world_icon.jpeg");
        if (icon.isFile()) Editor.copyFile(icon, new File(dst, "world_icon.jpeg"));

        String info = "世界：" + w.name + "\n目录：" + w.dir.getAbsolutePath()
                + "\n备份时间：" + timeText(System.currentTimeMillis())
                + "\n\n还原方法：把本目录里的 db 与 level.dat 复制回存档目录，覆盖同名内容。\n";
        FileOutputStream fo = new FileOutputStream(new File(dst, "备份说明.txt"));
        try {
            fo.write(info.getBytes("UTF-8"));
        } finally {
            fo.close();
        }
        return dst;
    }

    /** 把处理结果写回原存档目录（先备份，再替换 db 与 level.dat，失败自动回滚）。 */
    public static File writeBack(World w, File processed) throws Exception {
        File newDb = new File(processed, "db");
        if (!newDb.isDirectory()) throw new Exception("处理结果里没有 db 目录");
        File backupDir = backup(w);
        File newLevel = new File(processed, "level.dat");

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        // 暂存在同一目录（同一文件系统，renameTo 才可靠），成功后再删除
        File stashDb = new File(w.dir, ".db_old_" + ts);
        File stashLevel = new File(w.dir, ".level_old_" + ts);
        File oldDb = new File(w.dir, "db");
        File oldLevel = new File(w.dir, "level.dat");
        boolean dbMoved = false, levelMoved = false;
        try {
            if (oldDb.isDirectory()) {
                if (!oldDb.renameTo(stashDb)) throw new Exception("无法移走旧的 db 目录（可能没有写权限）");
                dbMoved = true;
            }
            if (newLevel.isFile() && oldLevel.isFile()) {
                if (oldLevel.renameTo(stashLevel)) levelMoved = true;
            }
            Editor.copyDir(newDb, oldDb, null);
            if (newLevel.isFile()) Editor.copyFile(newLevel, oldLevel);
        } catch (Exception e) {
            // 回滚
            try {
                Editor.deleteRecursivePublic(oldDb);
                if (dbMoved) stashDb.renameTo(oldDb);
                if (levelMoved) {
                    if (oldLevel.isFile()) Editor.deleteRecursivePublic(oldLevel);
                    stashLevel.renameTo(oldLevel);
                }
            } catch (Throwable ignored) {
            }
            throw new Exception("写入失败，已回滚原存档：" + e.getMessage()
                    + "（备份在 " + backupDir.getAbsolutePath() + "）");
        }
        Editor.deleteRecursivePublic(stashDb);
        Editor.deleteRecursivePublic(stashLevel);
        return backupDir;
    }
}

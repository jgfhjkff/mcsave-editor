package com.nmsave.editor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 网易存档处理流程：解包 -> 解密 -> 读库 -> 过滤 -> 重建库 -> 加密 -> 重新打包。 */
public class Editor {

    /** 调试：打印每个表/日志文件的条数（仅桌面端自测用）。 */
    public static boolean debug = false;

    public interface Progress {
        void onProgress(int pct, String msg);
    }

    public static class Options {
        public boolean purgeEntities = true;   // 是否执行实体清理
        /** 要删除的实体类别；空集合 = 全部删除。可含 boat/mob/item/xp/other。 */
        public final java.util.Set<String> entityKinds = new java.util.LinkedHashSet<>();
        public boolean clearDigests = true;    // 清空区块实体索引（digp / 旧 Entity 记录）
        public boolean clearPlayer = false;    // 清空旧玩家数据（~local_player + level.dat Player）
        public boolean repackOnly = false;     // 只重打包，不改任何数据（诊断用）

        /** 精确/包含匹配的实体 ID（如 cow、minecraft:parrot）；非空时优先于类别勾选。 */
        public final java.util.Set<String> entityIds = new java.util.LinkedHashSet<>();
        // 玩家坐标与手持物品（null = 不修改）
        public Double posX, posY, posZ;
        // 背包（JSON 直接改写；null = 不修改）
        public String invSection;
        public String invJson;
        /** 直接给 Tag（按格子编辑用）；非 null 时优先于 invJson，类型完全不丢。 */
        public Nbt.Tag invTag;
        public String heldItem;
        public int heldCount = 1;
        public int heldAux = 0;

        // 世界参数（null = 不修改）
        public Integer gameType;
        public Integer difficulty;
        public Boolean cheats, pvp, keepInventory, daylight;
        public Long time;
        /** 只改“一天中的时刻”（0-23999 tick），保留已过的天数（Time 会按 当前天数*24000 + 该值 写入）。 */
        public Integer timeOfDay;
        /** 随机种子（只影响尚未生成的区块）。 */
        public Long seed;
        public Integer spawnX, spawnY, spawnZ;
        /** 天气：1=晴 2=雨 3=雷雨；weatherMinutes 为持续分钟。 */
        public Integer weather;
        public Integer weatherMinutes = 10;
        /** 游戏规则键 → 0/1（只写这里给出的键）。 */
        public final java.util.Map<String, Integer> rules = new java.util.LinkedHashMap<>();

        // 单格改方块（null / false = 不修改）
        public boolean blockEdit;
        public int blockDim = 0;          // 0 主世界 / 1 下界 / 2 末地
        public int blockX, blockY, blockZ;
        public String blockName;
        public String blockStates;
    }

    public static class Stats {
        public String worldName = "";
        public int entityCount;
        public int digestCount;
        public int keptCount;
        public int playerEntities;
        public final Map<String, Integer> byType = new LinkedHashMap<>();
        public final Map<String, Integer> byKind = new LinkedHashMap<>();
        public boolean encrypted;
        public String worldDir;
        public LevelDat.Info worldInfo;
        public double[] playerPos;
        public String mainhand;
        public int purged;
        public String blockMessage;   // 改方块的结果说明
    }

    /** 只读分析：解包 -> 解密 -> 读库 -> 统计，不做任何写操作。 */
    public static Stats scan(File zip, File workDir, Progress cb) throws Exception {
        Stats st = new Stats();
        File extract = extractZip(zip, new File(workDir, "ex"));
        if (cb != null) cb.onProgress(20, "解包完成");
        File world = findWorld(extract);
        if (world == null) throw new Exception("没找到存档世界目录（缺少 level.dat / db）");
        st.worldDir = world.getName();
        st.worldName = readWorldName(world);

        File dbDir = new File(world, "db");
        if (!dbDir.isDirectory()) throw new Exception("存档里没有 db 目录");
        byte[] dbKey = null;
        File currentFile = new File(dbDir, "CURRENT");
        st.encrypted = currentFile.isFile() && BedrockDb.isEncrypted(BedrockDb.readFile(currentFile));
        if (st.encrypted) {
            String manifestName = null;
            for (File f : dbDir.listFiles()) if (f.getName().startsWith("MANIFEST")) { manifestName = f.getName(); break; }
            if (manifestName == null) throw new Exception("找不到 MANIFEST 文件");
            dbKey = BedrockDb.deriveKey(BedrockDb.readFile(currentFile), manifestName);
        }
        List<BedrockDb.Entry> all = BedrockDb.readAll(dbDir, dbKey, null);
        if (cb != null) cb.onProgress(60, "读取键值 " + all.size() + " 条");

        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (k.startsWith("actorprefix")) {
                st.entityCount++;
                String id = e.value == null ? null : readIdentifier(e.value);
                if (id == null) id = "(无法解析)";
                String kind = kindOf(id);
                if ("player".equals(kind)) { st.playerEntities++; continue; }
                count(st.byType, id);
                count(st.byKind, kind);
            } else if (k.startsWith("digp") || (e.key.length == 9 && (e.key[8] & 0xFF) == 0x32)) {
                st.digestCount++;
            } else {
                st.keptCount++;
            }
        }
        for (BedrockDb.Entry e : all) {
            if (e.value != null && "~local_player".equals(new String(e.key, java.nio.charset.StandardCharsets.ISO_8859_1))) {
                st.playerPos = PlayerEdit.readPos(e.value);
                st.mainhand = PlayerEdit.readMainhand(e.value);
            }
        }
        try {
            File levelDat = new File(world, "level.dat");
            if (levelDat.isFile()) st.worldInfo = LevelDat.read(levelDat);
        } catch (Exception ignored) {
        }
        if (cb != null) cb.onProgress(100, "分析完成");
        return st;
    }

    /** 读取 zip 内所有文件到临时目录。 */
    private static File extractZip(File zip, File extract) throws Exception {
        deleteRecursive(extract);
        extract.mkdirs();
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        try {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) { new File(extract, e.getName()).mkdirs(); continue; }
                File f = new File(extract, e.getName());
                f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    int n;
                    while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                } finally { fos.close(); }
            }
        } finally { zin.close(); }
        return extract;
    }

    public static Stats process(File zip, File outZip, Options opt, File workDir, Progress cb) throws Exception {
        Stats st = new Stats();
        File extract = extractZip(zip, new File(workDir, "ex"));
        if (cb != null) cb.onProgress(15, "解包完成");

        File world = findWorld(extract);
        if (world == null) throw new Exception("没找到存档世界目录（缺少 level.dat / db）");
        st.worldDir = world.getName();
        st.worldName = readWorldName(world);

        File dbDir = new File(world, "db");
        if (!dbDir.isDirectory()) throw new Exception("存档里没有 db 目录");
        File currentFile = new File(dbDir, "CURRENT");
        if (!currentFile.isFile()) throw new Exception("db 目录缺少 CURRENT 文件");

        byte[] currentRaw = BedrockDb.readFile(currentFile);
        byte[] dbKey = null;
        st.encrypted = BedrockDb.isEncrypted(currentRaw);
        if (st.encrypted) {
            String manifestName = null;
            for (File f : dbDir.listFiles()) if (f.getName().startsWith("MANIFEST")) { manifestName = f.getName(); break; }
            if (manifestName == null) throw new Exception("找不到 MANIFEST 文件");
            dbKey = BedrockDb.deriveKey(currentRaw, manifestName);
            if (cb != null) cb.onProgress(22, "已推导解密密钥（" + dbKey.length + " 字节）");
        }

        List<String> logs = new ArrayList<>();
        List<BedrockDb.Entry> all = BedrockDb.readAll(dbDir, dbKey, logs);
        if (debug) for (String l : logs) System.out.println("  " + l);
        if (cb != null) cb.onProgress(45, "读取键值 " + all.size() + " 条");

        int ent = 0, dig = 0;
        List<BedrockDb.Entry> keep = new ArrayList<>();
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, java.nio.charset.StandardCharsets.ISO_8859_1);
            boolean isActor = k.startsWith("actorprefix");
            boolean isDigest = k.startsWith("digp") || (e.key.length == 9 && (e.key[8] & 0xFF) == 0x32);
            boolean isLocalPlayer = k.startsWith("~local_player");

            // 每个实体只解析一次 NBT：id 同时用于分类统计与精确匹配
            String id = null;
            String kind = null;
            if (isActor) {
                id = e.value == null ? null : readIdentifier(e.value);
                kind = kindOf(id);
                if ("player".equals(kind)) {
                    st.playerEntities++;
                } else {
                    ent++;
                    String shown = id == null ? "(无法解析)" : id;
                    count(st.byType, shown);
                    count(st.byKind, kind);
                }
            }
            if (isDigest) dig++;

            if (opt.repackOnly) { keep.add(e); continue; }
            if (isActor && !"player".equals(kind)) {
                boolean purge;
                if (!opt.entityIds.isEmpty()) {
                    purge = matchesId(id, opt.entityIds);
                } else {
                    purge = opt.purgeEntities && (opt.entityKinds.isEmpty() || opt.entityKinds.contains(kind));
                }
                if (purge) { st.purged++; continue; }
            }
            // 只有真的执行了实体清理才丢区块实体索引，否则会留下“有方块没实体”的空索引
            if (isDigest) { if (!(opt.purgeEntities && opt.clearDigests)) keep.add(e); continue; }
            if (isLocalPlayer && opt.clearPlayer) continue;
            keep.add(e);
        }
        all.clear();   // 全量表已无用，尽快释放（大存档内存吃紧）
        st.entityCount = ent;
        st.digestCount = opt.repackOnly ? 0 : dig;
        st.keptCount = keep.size();
        if (cb != null) cb.onProgress(60, "实体 " + ent + "，索引 " + dig + "，保留 " + keep.size());

        // 玩家坐标 / 手持物品（直接改写 ~local_player 的字节）
        if (!opt.repackOnly) {
            for (BedrockDb.Entry e : keep) {
                if (e.value == null) continue;
                if (!"~local_player".equals(new String(e.key, java.nio.charset.StandardCharsets.ISO_8859_1))) continue;
                byte[] v = e.value.clone();
                boolean changed = false;
                if (opt.posX != null && opt.posY != null && opt.posZ != null) {
                    changed = PlayerEdit.setPos(v, opt.posX, opt.posY, opt.posZ);
                }
                if (opt.heldItem != null && !opt.heldItem.trim().isEmpty()) {
                    byte[] nv = PlayerEdit.setMainhand(v, opt.heldItem.trim(), opt.heldCount, opt.heldAux);
                    if (nv != null) { v = nv; changed = true; }
                }
                if (opt.invSection != null && opt.invTag != null) {
                    byte[] nv = PlayerEdit.setSection(v, opt.invSection, opt.invTag);
                    if (nv == null) throw new IllegalStateException("写入 " + opt.invSection + " 失败（字段结构异常）");
                    v = nv;
                    changed = true;
                } else if (opt.invSection != null && opt.invJson != null) {
                    // 解析失败会抛 IllegalArgumentException，由上层显示具体原因
                    v = PlayerEdit.setInventorySection(v, opt.invSection, opt.invJson);
                    changed = true;
                }
                if (changed) e.value = v;
            }
        }

        // 单格改方块（直接改 SubChunkPrefix 的位打包索引 + 调色板）
        if (opt.blockEdit && !opt.repackOnly) {
            java.util.Map<String, String> states = BlockEdit.parseStates(opt.blockStates);
            BlockEdit.Result br = BlockEdit.setBlock(keep, opt.blockDim, opt.blockX, opt.blockY, opt.blockZ,
                    opt.blockName, states);
            st.blockMessage = br.message;
            if (cb != null) cb.onProgress(70, br.message);
        }

        // 备份并重建 db 目录
        byte[] presets = null;
        File presetsFile = new File(dbDir, "presets.json");
        if (presetsFile.isFile()) presets = BedrockDb.readFile(presetsFile);
        deleteRecursive(dbDir);
        dbDir.mkdirs();
        BedrockDb.writeDb(dbDir, keep, dbKey);
        if (presets != null) BedrockDb.writeFile(presetsFile, presets);
        if (cb != null) cb.onProgress(75, "已重建数据库（" + keep.size() + " 条）");

        // 世界参数 / 玩家数据（level.dat）
        File levelDat = new File(world, "level.dat");
        if (!opt.repackOnly) {
            boolean touched = false;
            if (opt.clearPlayer && levelDat.isFile() && LevelDat.stripPlayer(levelDat)) touched = true;

            if (opt.timeOfDay != null && levelDat.isFile()) {
                long cur = LevelDat.read(levelDat).time;
                if (cur < 0) cur = 0;
                int tod = Math.max(0, Math.min(23999, opt.timeOfDay));
                opt.time = (cur / 24000L) * 24000L + tod;
            }

            int changes = 0;
            boolean editWorld = opt.gameType != null || opt.difficulty != null || opt.cheats != null
                    || opt.pvp != null || opt.keepInventory != null || opt.daylight != null || opt.time != null
                    || opt.seed != null || opt.spawnX != null || opt.weather != null || !opt.rules.isEmpty();
            // 天气换算：rainTime / lightningTime 单位是 tick（20 tick = 1 秒）
            Double rainLevel = null, lightningLevel = null;
            Integer rainTime = null, lightningTime = null;
            if (opt.weather != null) {
                int ticks = Math.max(1, (opt.weatherMinutes == null ? 10 : opt.weatherMinutes)) * 1200;
                if (opt.weather == 1) {
                    rainLevel = 0.0; rainTime = ticks; lightningLevel = 0.0; lightningTime = ticks;
                } else if (opt.weather == 2) {
                    rainLevel = 1.0; rainTime = ticks; lightningLevel = 0.0; lightningTime = ticks;
                } else {
                    rainLevel = 1.0; rainTime = ticks; lightningLevel = 1.0; lightningTime = ticks;
                }
            }
            if (editWorld && levelDat.isFile()) {
                changes = LevelDat.applyAll(levelDat, opt.gameType, opt.difficulty, opt.cheats,
                        opt.pvp, opt.keepInventory, opt.daylight, opt.time,
                        opt.seed, opt.spawnX, opt.spawnY, opt.spawnZ,
                        rainLevel, rainTime, lightningLevel, lightningTime, opt.rules);
            }
            if (changes > 0 || touched) {
                if (cb != null) cb.onProgress(82, "已修改 level.dat（参数 " + changes + " 项）");
            }
        }

        FileOutputStream fos = new FileOutputStream(outZip);
        ZipOutputStream zos = new ZipOutputStream(fos);
        try {
            zos.setLevel(6);
            addDir(extract, "", zos, cb);
        } finally {
            zos.close();
        }
        if (cb != null) cb.onProgress(100, "完成");
        return st;
    }

    // ------------------------------------------------------------------ 目录模式

    /** 把世界目录打成 zip（条目前缀 = 目录名），交给 zip 流程复用。 */
    static File zipWorld(File worldDir, File outZip) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip));
        try {
            zos.setLevel(1);
            addDir(worldDir, worldDir.getName(), zos, null);
        } finally {
            zos.close();
        }
        return outZip;
    }

    /** 只读分析一个世界目录（不写任何东西）。 */
    public static Stats scanDir(File worldDir, File workDir, Progress cb) throws Exception {
        workDir.mkdirs();
        File zip = zipWorld(worldDir, new File(workDir, "in.zip"));
        return scan(zip, workDir, cb);
    }

    /** 处理一个世界目录，结果写到 outDir（已剥掉最外层世界目录）。原目录不动。 */
    public static Stats processDir(File worldDir, File outDir, Options opt, File workDir, Progress cb) throws Exception {
        workDir.mkdirs();
        File zip = zipWorld(worldDir, new File(workDir, "in.zip"));
        File outZip = new File(workDir, "out.zip");
        Stats st = process(zip, outZip, opt, new File(workDir, "p"), cb);

        deleteRecursive(outDir);
        outDir.mkdirs();
        ZipInputStream zis = new ZipInputStream(new FileInputStream(outZip));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (!e.isDirectory()) {
                    int slash = name.indexOf('/');
                    String rel = slash >= 0 ? name.substring(slash + 1) : name;
                    if (!rel.isEmpty()) {
                        File dst = new File(outDir, rel);
                        File parent = dst.getParentFile();
                        if (parent != null) parent.mkdirs();
                        FileOutputStream fo = new FileOutputStream(dst);
                        try {
                            copyStream(zis, fo);
                        } finally {
                            fo.close();
                        }
                    }
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return st;
    }

    /** 把世界存档里玩家的某个背包字段读成 NBT Tag；没有该字段返回 null。 */
    public static Nbt.Tag readPlayerSectionTag(File worldDir, String section) throws Exception {
        File dbDir = new File(worldDir, "db");
        if (!dbDir.isDirectory()) throw new Exception("存档里没有 db 目录");
        byte[] key = dbKeyOf(dbDir);
        List<BedrockDb.Entry> all = BedrockDb.readAll(dbDir, key, null);
        try {
            for (BedrockDb.Entry e : all) {
                if (e.value == null) continue;
                if (!"~local_player".equals(new String(e.key, java.nio.charset.StandardCharsets.ISO_8859_1))) continue;
                return PlayerEdit.readSection(e.value, section);
            }
            return null;
        } finally {
            all.clear();
        }
    }

    /** 把世界存档里玩家的某个背包字段读成 JSON；没有该字段返回 null。 */
    public static String readPlayerSectionJson(File worldDir, String section) throws Exception {
        File dbDir = new File(worldDir, "db");
        if (!dbDir.isDirectory()) throw new Exception("存档里没有 db 目录");
        byte[] key = dbKeyOf(dbDir);
        List<BedrockDb.Entry> all = BedrockDb.readAll(dbDir, key, null);
        try {
            for (BedrockDb.Entry e : all) {
                if (e.value == null) continue;
                if (!"~local_player".equals(new String(e.key, java.nio.charset.StandardCharsets.ISO_8859_1))) continue;
                Nbt.Tag t = PlayerEdit.readSection(e.value, section);
                return t == null ? null : NbtJson.toJson(t);
            }
            return null;
        } finally {
            all.clear();
        }
    }

    /** 从 db 目录推导解密密钥（网易加密库）；不加密返回 null。 */
    public static byte[] dbKeyOf(File dbDir) throws Exception {
        File cur = new File(dbDir, "CURRENT");
        if (!cur.isFile()) return null;
        byte[] c = BedrockDb.readFile(cur);
        if (!BedrockDb.isEncrypted(c)) return null;
        String manifestName = null;
        File[] kids = dbDir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f.getName().startsWith("MANIFEST")) { manifestName = f.getName(); break; }
            }
        }
        if (manifestName == null) throw new Exception("找不到 MANIFEST 文件");
        return BedrockDb.deriveKey(c, manifestName);
    }

    public static void copyStream(InputStream in, java.io.OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    public static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            copyStream(in, out);
        } finally {
            in.close();
            out.close();
        }
    }

    public static void copyDir(File src, File dst, Progress cb) throws Exception {
        File[] kids = src.listFiles();
        if (kids == null) return;
        dst.mkdirs();
        for (File k : kids) {
            File t = new File(dst, k.getName());
            if (k.isDirectory()) copyDir(k, t, cb);
            else copyFile(k, t);
        }
    }

    static void deleteRecursivePublic(File f) {
        deleteRecursive(f);
    }

    private static File findWorld(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File c : children) {
            if (c.isDirectory() && new File(c, "level.dat").isFile() && new File(c, "db").isDirectory()) return c;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                File r = findWorld(c);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String readWorldName(File world) {
        File f = new File(world, "levelname.txt");
        if (f.isFile()) {
            try {
                byte[] b = BedrockDb.readFile(f);
                return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {
            }
        }
        return world.getName();
    }

    private static String readIdentifier(byte[] nbt) {
        try {
            Nbt.Tag root = Nbt.parse(nbt, 0);
            Nbt.Tag id = root.asCompound().get("identifier");
            return id == null ? null : NbtString(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String NbtString(Nbt.Tag t) {
        Object v = t.value;
        return v instanceof String ? (String) v : null;
    }

    /** 实体类别：player / boat / mob / item / xp / other。 */
    public static String kindOf(String id) {
        if (id == null) return "other";
        String name = id.toLowerCase();
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);   // 去掉命名空间，避免 "minecraft" 里的 raft 干扰
        if (name.equals("player")) return "player";
        if (name.equals("item")) return "item";
        if (name.contains("xp_orb") || name.contains("experience_orb")) return "xp";
        if (name.contains("boat") || name.contains("raft")) return "boat";
        String[] nonLiving = {"minecart", "armor_stand", "falling_block", "tnt", "lightning_bolt",
                "fishing_hook", "arrow", "snowball", "egg", "ender_pearl", "fireball", "thrown_",
                "xp_bottle", "camera", "leash_knot", "item_frame", "glow_item_frame", "painting",
                "area_effect_cloud", "evocation_fang", "eye_of_ender", "npc", "projectile",
                "bottle", "splash_potion", "lingering_potion", "wind_charge", "breeze_wind_charge"};
        for (String n : nonLiving) if (name.contains(n)) return "other";
        return "mob";
    }

    /** 用户输入的实体过滤词是否命中该 ID（支持省略 minecraft: 前缀、支持包含匹配）。 */
    private static boolean matchesId(String id, java.util.Set<String> filters) {
        if (id == null) return false;
        String full = id.toLowerCase();
        String shortId = full.startsWith("minecraft:") ? full.substring(10) : full;
        for (String f : filters) {
            String t = f.trim().toLowerCase();
            if (t.isEmpty()) continue;
            if (t.startsWith("minecraft:")) t = t.substring(10);
            if (t.equals(shortId) || t.equals(full) || full.contains(t)) return true;
        }
        return false;
    }

    public static String kindLabel(String kind) {
        if ("boat".equals(kind)) return "船/筏";
        if ("mob".equals(kind)) return "生物";
        if ("item".equals(kind)) return "掉落物";
        if ("xp".equals(kind)) return "经验球";
        if ("player".equals(kind)) return "玩家";
        return "其它";
    }

    private static void count(Map<String, Integer> map, String key) {
        Integer n = map.get(key);
        map.put(key, n == null ? 1 : n + 1);
    }

    private static void addDir(File dir, String base, ZipOutputStream zos, Progress cb) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            String name = base.isEmpty() ? f.getName() : base + "/" + f.getName();
            if (f.isDirectory()) {
                addDir(f, name, zos, cb);
            } else {
                ZipEntry e = new ZipEntry(name);
                zos.putNextEntry(e);
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                } finally {
                    in.close();
                }
                zos.closeEntry();
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}

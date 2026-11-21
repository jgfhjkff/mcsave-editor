package com.nmsave.editor;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PathEffect;
import android.graphics.DashPathEffect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.CompoundButton;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 主界面：列出手机里网易存档（同一 minecraftWorlds 目录下所有账号的存档都会列出来），
 * 点某个存档进入详情修改。界面全部代码构建，不依赖布局 XML。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 2001;
    private static final int REQ_SAVE = 2002;
    private static final int REQ_SAVE_RAW = 2003;
    private static final String CRASH_FILE = "last_crash.txt";

    private final Handler ui = new Handler(Looper.getMainLooper());

    // 列表界面
    private TextView tvStatus, tvHint, tvPerm;
    private LinearLayout worldList;

    // 详情界面
    private WorldStore.World current;
    private TextView tvInfo, tvLog;
    private CheckBox cbPurge, cbKindAll, cbBoat, cbMob, cbItemKind, cbXp, cbOther,
            cbDigests, cbPlayer, cbRepack, cbPos, cbHeld, cbBlock;
    private EditText etBlockX, etBlockY, etBlockZ, etBlockName, etBlockStates;
    private Spinner spBlockDim;
    private Spinner spMode, spDifficulty, spCheats, spPvp, spKeepInv, spDaylight;
    private Spinner spTime, spWeather;
    private EditText etTimeCustom, etWeatherMin, etSeed;
    private CheckBox cbSpawn;
    private EditText etSpawnX, etSpawnY, etSpawnZ;
    private final java.util.Map<String, Spinner> ruleSpins = new java.util.LinkedHashMap<>();
    private EditText etEntityIds, etPosX, etPosY, etPosZ, etItem, etItemCount, etItemAux;
    private Button btnApply, btnExportZip;
    private FrameLayout frame;
    private LinearLayout drawer;
    private View overlay;
    private int drawerWidth;
    private boolean drawerOpen;
    private boolean detailMode;
    private boolean backupMode;
    private boolean settingsMode;
    private java.util.List<WorldStore.World> lastWorlds = new java.util.ArrayList<>();
    // 列表缓存：来回切页面不再重新扫描/重绘，只有数据变了才重扫
    private WorldStore.ScanResult cachedScan;
    private boolean scanNeeded = true;   // true = 下次进列表要重新扫描
    private boolean scanning;            // 正在扫描（防重复线程）
    private boolean themeSwitching;      // 主题过场动画进行中

    // 按格子编辑的运行时状态（只在一次编辑会话内有效）
    private java.util.Map<Integer, Nbt.Tag> invSlots = new java.util.LinkedHashMap<>();
    private LinearLayout invRows;

    private Editor.Options pendingOptions;
    private WorldStore.World pendingRawWorld;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 隐藏系统自带的 ActionBar（它会把应用名再画一遍，和顶部栏重复）
        try {
            android.app.ActionBar ab = getActionBar();
            if (ab != null) ab.hide();
        } catch (Throwable ignored) {
        }
        applyPalette(prefBool(KEY_DARK, systemIsDark()));
        applySystemBars();
        installCrashLogger();
        try {
            showListScreen();
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    // ================================================================ 列表界面
    private void showListScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        tvStatus = text("正在检查存储权限…", 13, false, C_SUB);
        root.addView(tvStatus);

        tvPerm = link(root, "授予文件权限 ›");
        tvPerm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestFilePermission(); }
        });
        tvPerm.setVisibility(hasFilePermission() ? View.GONE : View.VISIBLE);

        worldList = column();
        root.addView(worldList);

        tvHint = text("", 12, false, C_HINT);
        root.addView(tvHint);
        tvHint.setText("写入前会自动备份原存档到 " + WorldStore.backupRoot().getAbsolutePath()
                + "。请先在游戏里退出该世界并结束游戏进程，再回来写入。");

        detailMode = false;
        backupMode = false;
        settingsMode = false;
        shell(scroll, "我的世界存档编辑器", "刷新", new Runnable() {
            public void run() {
                scanNeeded = true;
                startScan();
            }
        });

        if (hasFilePermission()) {
            if (!scanNeeded && cachedScan != null) {
                worldList.removeAllViews();
                renderWorlds(cachedScan);
            } else {
                startScan();
            }
        } else {
            tvStatus.setText("需要存储权限才能读取存档目录");
            requestFilePermission();
        }
    }

    private boolean hasFilePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestFilePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_PERM);
                tvStatus.setText("请在系统弹窗里打开“允许管理所有文件”，然后返回本应用");
                return;
            } catch (Throwable ignored) {
            }
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_PERM);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            startScan();
        }
    }

    private void startScan() {
        if (scanning) return;   // 已有扫描在跑，忽略这次请求
        boolean granted = hasFilePermission();
        boolean wasShowing = tvPerm != null && tvPerm.getVisibility() == View.VISIBLE;
        if (tvPerm != null) tvPerm.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) {
            tvStatus.setText("还没有存储权限，点“授予文件权限”再试");
            return;
        }
        if (wasShowing) Toast.makeText(this, "已获得存储权限", Toast.LENGTH_SHORT).show();
        tvStatus.setText("正在扫描存档目录…");
        worldList.removeAllViews();
        final boolean includeIntl = prefBool(KEY_INTL, true);
        scanning = true;
        new Thread(new Runnable() {
            public void run() {
                final WorldStore.ScanResult r = WorldStore.scan(includeIntl);
                ui.post(new Runnable() {
                    public void run() {
                        scanning = false;
                        cachedScan = r;
                        scanNeeded = false;
                        renderWorlds(r);
                    }
                });
            }
        }).start();
    }

    private void renderWorlds(final WorldStore.ScanResult r) {
        lastWorlds = r.worlds;
        worldList.removeAllViews();
        if (r.worlds.isEmpty()) {
            tvStatus.setText(r.permissionDenied
                    ? "读不到存档目录（权限不足）"
                    : "没找到存档。确认游戏里至少创建过一个世界，且存档位置设为“外部存储/external”。");
            StringBuilder sb = new StringBuilder("已尝试的路径：\n");
            for (String p : r.tried) sb.append("· ").append(p).append("\n");
            for (String e : r.errors) sb.append("! ").append(e).append("\n");
            tvHint.setText(sb.toString());
            return;
        }
        tvStatus.setText("找到 " + r.worlds.size() + " 个存档（同目录下不同账号的存档都会列出）");

        for (int i = 0; i < r.worlds.size(); i++) {
            final WorldStore.World w = r.worlds.get(i);
            LinearLayout card = column();
            card.setPadding(dp(14), dp(13), dp(14), dp(13));
            card.setBackground(bg(C_CARD, 12, C_STROKE));
            card.setClickable(true);

            LinearLayout headRow = new LinearLayout(this);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView nameView = text(w.name, 16, true, C_TEXT);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            headRow.addView(nameView, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView eye = new TextView(this);
            eye.setText("\uD83D\uDDBC\uFE0F");
            eye.setTextSize(20);
            eye.setGravity(Gravity.CENTER);
            eye.setContentDescription("查看世界图标 world_icon.jpeg");
            eye.setPadding(dp(8), dp(6), dp(4), dp(6));
            if (!new File(w.dir, "world_icon.jpeg").isFile()) eye.setAlpha(0.3f);
            eye.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showWorldIcon(w); }
            });
            headRow.addView(eye, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(headRow);
            card.addView(text("目录 " + w.id + "    修改 " + WorldStore.timeText(w.modified), 12, false, C_SUB));
            String flags = ("intl".equals(w.edition) ? "国际版" : ("netease".equals(w.edition) ? "网易版" : "未知版本"))
                    + "    " + WorldStore.sizeText(w.sizeBytes)
                    + (w.encrypted ? "    加密库" : "    未加密库")
                    + (w.hasPlayer ? "    含玩家数据" : "    无玩家数据");
            if (!w.readable) flags += "    level.dat 解析失败";
            card.addView(text(flags, 12, false, C_SUB));
            TextView cp = text(w.dir.getAbsolutePath(), 11, false, C_HINT);
            cp.setSingleLine(true);
            cp.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(cp);

            LinearLayout acts1 = new LinearLayout(this);
            acts1.setOrientation(LinearLayout.HORIZONTAL);
            Button btnBak = smallButton(acts1, "备份到手机");
            Button btnExp = smallButton(acts1, "导出 zip");
            btnBak.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { quickBackup(w); }
            });
            btnExp.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { quickExport(w); }
            });
            LinearLayout.LayoutParams a1lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            a1lp.topMargin = dp(8);
            card.addView(acts1, a1lp);



            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openDetail(w); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            worldList.addView(card, lp);
        }
        tvHint.setText("写入前会自动备份原存档到 " + WorldStore.backupRoot().getAbsolutePath()
                + "。请先在游戏里退出该世界并结束游戏进程，再回来写入。");
    }

    /** 看存档目录里的 world_icon.jpeg，解码后居中显示（不依赖系统看图应用）。 */
    private void showWorldIcon(final WorldStore.World w) {
        final File f = new File(w.dir, "world_icon.jpeg");
        if (!f.isFile()) {
            Toast.makeText(this, "这个存档没有 world_icon.jpeg", Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap bm;
        final int[] dim = new int[2];
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            int sw = Math.max(1, probe.outWidth), sh = Math.max(1, probe.outHeight);
            int sample = 1;
            while (sw / sample > 1200 || sh / sample > 1200) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            dim[0] = probe.outWidth;
            dim[1] = probe.outHeight;
        } catch (Throwable t) {
            Toast.makeText(this, "图标读不出来：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            return;
        }
        if (bm == null) {
            Toast.makeText(this, "图标解码失败（可能不是标准 JPEG）", Toast.LENGTH_LONG).show();
            return;
        }
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setPadding(dp(22), dp(24), dp(22), dp(24));
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });

        LinearLayout card = column();
        card.setBackground(bg(C_CARD, 12, C_STROKE));
        card.setPadding(dp(16), dp(14), dp(16), dp(10));
        card.setClickable(true);

        card.addView(text(w.name, 16, true, C_TEXT));
        TextView sub = text("world_icon.jpeg   " + dim[0] + " × " + dim[1] + " 像素   "
                + WorldStore.sizeText(f.length()), 11, false, C_HINT);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        card.addView(sub, slp);

        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bm);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(10);
        card.addView(iv, ilp);

        TextView tip = text("点空白处关闭", 11, false, C_HINT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        card.addView(tip, blp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        root.addView(card, clp);

        dlg.setContentView(root);
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dlg.show();
    }

    /** 列表卡片里的等宽小按钮。 */
    private Button smallButton(LinearLayout row, String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(C_BTN_TEXT);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x332F6FED),
                bg(C_BTN_BG, 10, C_BTN_STROKE), null));
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(8), dp(11), dp(8), dp(11));
        b.setElevation(0f);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        row.addView(b, lp);
        return b;
    }

    private void status(String msg) {
        if (tvStatus != null) tvStatus.setText(msg);
        if (tvLog != null) tvLog.append(msg + "\n");
    }

    /** 原样整目录备份：不经过任何处理逻辑。 */
    private void quickBackup(final WorldStore.World w) {
        if (busy) return;
        busy = true;
        status("正在备份「" + w.name + "」…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final File dst = WorldStore.fullBackup(w);
                    ui.post(new Runnable() {
                        public void run() {
                            status("备份完成：" + dst.getAbsolutePath());
                            busy = false;
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        public void run() {
                            status("备份失败：" + friendlyError(e));
                            busy = false;
                        }
                    });
                }
            }
        }).start();
    }

    /** 原样导出 zip：整目录打包，不改任何数据。 */
    private void quickExport(final WorldStore.World w) {
        pendingRawWorld = w;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, w.name + "_存档备份.zip");
        try {
            startActivityForResult(i, REQ_SAVE_RAW);
        } catch (Throwable t) {
            status("打开保存失败：" + t);
        }
    }

    private void doRawExport(final WorldStore.World w, final Uri target) {
        busy = true;
        status("正在打包「" + w.name + "」…");
        new Thread(new Runnable() {
            public void run() {
                File zip = new File(getCacheDir(), "raw_" + System.currentTimeMillis() + ".zip");
                try {
                    zipWorldDirect(w.dir, zip, w.id);
                    InputStream in = new FileInputStream(zip);
                    java.io.OutputStream os = getContentResolver().openOutputStream(target);
                    Editor.copyStream(in, os);
                    in.close();
                    os.close();
                    final String size = WorldStore.sizeText(zip.length());
                    zip.delete();
                    ui.post(new Runnable() {
                        public void run() {
                            status("已导出（原样，未修改）：" + size);
                            busy = false;
                        }
                    });
                } catch (final Throwable e) {
                    final String msg = crashText(e);
                    zip.delete();
                    ui.post(new Runnable() {
                        public void run() {
                            status("导出失败：" + e);
                            if (tvLog != null) tvLog.append(msg + "\n");
                            busy = false;
                        }
                    });
                }
            }
        }).start();
    }

    // ================================================================ 详情界面
    private void openDetail(final WorldStore.World w) {
        current = w;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        root.addView(text("目录：" + w.id + "    " + WorldStore.sizeText(w.sizeBytes), 12, false, C_SUB));
        TextView path = text(w.dir.getAbsolutePath(), 11, false, C_HINT);
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(path);

        tvInfo = text("正在读取存档…", 13, false, C_TEXT);
        root.addView(tvInfo);

        addOptions(root);

        btnApply = primaryButton(root, "写回这个存档（自动备份）");
        btnExportZip = secondaryButton(root, "导出处理后的 zip");
        btnApply.setEnabled(false);
        btnExportZip.setEnabled(false);
        btnApply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmWriteBack(); }
        });
        btnExportZip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startExportZip(); }
        });

        sectionTitle(root, "日志");
        tvLog = text("", 11, false, C_SUB);
        root.addView(tvLog);

        detailMode = true;
        backupMode = false;
        settingsMode = false;
        shell(scroll, w.name, "列表", new Runnable() {
            public void run() { showListScreen(); }
        });
        updateKindEnable();

        new Thread(new Runnable() {
            public void run() {
                try {
                    File work = new File(getCacheDir(), "probe_" + System.currentTimeMillis());
                    final Editor.Stats st = Editor.scanDir(w.dir, work, null);
                    final String desc = describe(st);
                    Editor.deleteRecursivePublic(work);
                    ui.post(new Runnable() {
                        public void run() {
                            tvInfo.setText(desc);
                            btnApply.setEnabled(true);
                            btnExportZip.setEnabled(true);
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        public void run() {
                            tvInfo.setText("读取失败：" + friendlyError(e));
                            tvLog.append("读取失败：" + friendlyError(e) + "\n");
                        }
                    });
                }
            }
        }).start();
    }

    private void addOptions(LinearLayout root) {
        sectionTitle(root, "① 实体清理");
        LinearLayout c1 = cardBox(root);
        cbPurge = check(c1, "执行实体清理", true);
        cbKindAll = check(c1, "全部类别（船 / 生物 / 掉落物 / 其它）", true);
        cbBoat = check(c1, "只删船 / 木筏", false);
        cbMob = check(c1, "只删生物（动物、怪物）", false);
        cbItemKind = check(c1, "只删掉落物", false);
        cbXp = check(c1, "只删经验球", false);
        cbOther = check(c1, "只删其它实体（矿车、盔甲架、箭、TNT 等）", false);
        cbDigests = check(c1, "同步清空区块实体索引", true);
        etEntityIds = edit(c1, "只删指定实体：cow,parrot（留空按上面类别）");

        sectionTitle(root, "② 世界参数");
        LinearLayout c2 = cardBox(root);
        addField(c2, "游戏模式");
        spMode = spinner(c2, new String[]{"不修改", "生存", "创造", "冒险"});
        addField(c2, "难度");
        spDifficulty = spinner(c2, new String[]{"不修改", "和平", "简单", "普通", "困难"});
        addField(c2, "允许作弊指令");
        spCheats = spinner(c2, new String[]{"不修改", "开", "关"});
        addField(c2, "允许 PVP");
        spPvp = spinner(c2, new String[]{"不修改", "开", "关"});
        addField(c2, "死亡不掉落");
        spKeepInv = spinner(c2, new String[]{"不修改", "开", "关"});
        addField(c2, "昼夜循环");
        spDaylight = spinner(c2, new String[]{"不修改", "开", "关"});

        sectionTitle(root, "③ 玩家坐标与手持物品");
        LinearLayout c3 = cardBox(root);
        cbPos = check(c3, "修改玩家坐标", false);
        LinearLayout posRow = row(c3);
        etPosX = editInRow(posRow, "X", true);
        etPosY = editInRow(posRow, "Y", true);
        etPosZ = editInRow(posRow, "Z", true);

        cbHeld = check(c3, "修改手持物品", false);
        etItem = edit(c3, "物品 ID，如 diamond_sword");
        LinearLayout itemRow = row(c3);
        etItemCount = editInRow(itemRow, "数量 1-64", false);
        etItemAux = editInRow(itemRow, "附加值 0", false);

        sectionTitle(root, "④ 玩家与诊断");
        LinearLayout c4 = cardBox(root);
        cbPlayer = check(c4, "清空旧玩家数据（换账号时用，会丢背包与坐标）", false);
        cbRepack = check(c4, "仅重打包不改数据（诊断用）", false);

        sectionTitle(root, "⑤ 背包编辑（JSON）");
        LinearLayout c5 = cardBox(root);
        c5.addView(text("按格编辑玩家背包的 36 个格子：每格写完整的物品 NBT（含 Name / Count / Slot 等），写入前自动备份。", 12, false, C_HINT));
        Button btnSlot = secondaryButton(c5, "打开背包格子（36 格）");
        btnSlot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSlotPage(); }
        });

        sectionTitle(root, "⑥ 改单个方块");
        LinearLayout c6 = cardBox(root);
        c6.addView(text("按方块坐标改一格：X/Z 换算成区块，Y 换算成子区块。只改这一格方块，"
                + "不会自动创建箱子/告示牌这类方块实体，也不会重算高度图与光照。", 11, false, C_HINT));

        cbBlock = check(c6, "改指定坐标的方块", false);

        LinearLayout dimRow = row(c6);
        dimRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dimLabel = text("维度", 12, false, C_HINT);
        dimRow.addView(dimLabel, new LinearLayout.LayoutParams(dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        spBlockDim = new Spinner(this);
        spBlockDim.setPopupBackgroundDrawable(bg(C_CARD, 8, C_STROKE));
        spBlockDim.setAdapter(new TintAdapter(this, new String[]{"主世界", "下界", "末地"}));
        dimRow.addView(spBlockDim, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout blockPosRow = row(c6);
        etBlockX = editInRow(blockPosRow, "X", true);
        etBlockY = editInRow(blockPosRow, "Y", true);
        etBlockZ = editInRow(blockPosRow, "Z", true);

        etBlockName = edit(c6, "方块名，如 minecraft:stone");
        etBlockStates = edit(c6, "状态可空：pillar_axis=x; facing_direction=2");
        etBlockStates.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etBlockName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etBlockStates.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        cbBlock.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { updateBlockEnable(); }
        });



        sectionTitle(root, "⑦ 时间 · 天气 · 出生点 · 种子");
        LinearLayout c7 = cardBox(root);
        addField(c7, "时间");
        spTime = spinner(c7, new String[]{"不修改", "早晨（1000）", "正午（6000）",
                "黄昏（12000）", "夜晚（15000）", "午夜（18000）"});
        etTimeCustom = edit(c7, "自定义 tick（0-23999，填了就忽略上面选项）");

        addField(c7, "天气");
        spWeather = spinner(c7, new String[]{"不修改", "晴", "雨", "雷雨"});
        etWeatherMin = edit(c7, "持续分钟（默认 10；20 tick = 1 秒）");

        cbSpawn = check(c7, "修改世界出生点", false);
        LinearLayout spawnRow = row(c7);
        etSpawnX = editInRow(spawnRow, "X", true);
        etSpawnY = editInRow(spawnRow, "Y", true);
        etSpawnZ = editInRow(spawnRow, "Z", true);

        addField(c7, "世界种子（RandomSeed）");
        etSeed = edit(c7, "留空不改；只影响还没生成的区块");
        TextView c7note = text("出生点 Y 在很多存档里是 32767（表示“自动”），填具体数值才会固定。"
                + "改种子等于换地形，已生成的区块不会重新生成。雨天/雷雨会把 rainLevel 置 1，晴置 0。",
                11, false, C_HINT);
        c7note.setLineSpacing(dp(2), 1f);
        c7.addView(c7note);

        sectionTitle(root, "⑧ 游戏规则");
        LinearLayout c8 = cardBox(root);
        TextView c8note = text("规则写的是 level.dat 里的 byte 键（如 showcoordinates、doentitydrops），"
                + "只改这里选中的项，其余保持原样。", 11, false, C_HINT);
        c8note.setLineSpacing(dp(2), 1f);
        c8.addView(c8note);

        LinearLayout presetRow = row(c8);
        final Button pr1 = chip(presetRow, "生存纯净");
        final Button pr2 = chip(presetRow, "创造自由");
        pr1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { applyPreset(1); }
        });
        pr2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { applyPreset(2); }
        });

        final LinearLayout rulesBox = column();
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c8.addView(rulesBox, rbLp);
        rulesBox.setVisibility(View.GONE);
        final String[][] ruleDefs = new String[][]{
                {"showcoordinates", "显示坐标"},
                {"doimmediaterespawn", "死亡后立即重生"},
                {"doentitydrops", "生物死亡掉落物品"},
                {"dotiledrops", "方块破坏掉落物品"},
                {"domobspawning", "自然生成生物"},
                {"naturalregeneration", "自然回血"},
                {"mobgriefing", "生物破坏 / 搬走方块"},
                {"tntexplodes", "TNT 会爆炸"},
                {"commandblocksenabled", "命令方块可用"},
                {"doweathercycle", "天气会自然变化"},
        };
        for (int i = 0; i < ruleDefs.length; i++) {
            addField(rulesBox, ruleDefs[i][1]);
            ruleSpins.put(ruleDefs[i][0], spinner(rulesBox, new String[]{"不修改", "开", "关"}));
        }
        final TextView tvExpand = link(c8, "展开游戏规则（10 项）›");
        tvExpand.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = rulesBox.getVisibility() != View.VISIBLE;
                rulesBox.setVisibility(show ? View.VISIBLE : View.GONE);
                tvExpand.setText(show ? "收起游戏规则 ‹" : "展开游戏规则（10 项）›");
            }
        });

        cbKindAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { updateKindEnable(); }
        });
        cbPurge.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { updateKindEnable(); }
        });
        cbRepack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { applyRepackState(); }
        });
        updateBlockEnable();
    }

    private void updateBlockEnable() {
        boolean on = cbBlock.isChecked() && !cbRepack.isChecked();
        spBlockDim.setEnabled(on);
        for (EditText e : new EditText[]{etBlockX, etBlockY, etBlockZ, etBlockName, etBlockStates}) e.setEnabled(on);
    }

    private void updateKindEnable() {
        boolean purgeOn = cbPurge.isChecked() && !cbRepack.isChecked();
        cbKindAll.setEnabled(purgeOn);
        boolean kindsOn = purgeOn && !cbKindAll.isChecked();
        for (CheckBox cb : new CheckBox[]{cbBoat, cbMob, cbItemKind, cbXp, cbOther}) cb.setEnabled(kindsOn);
        etEntityIds.setEnabled(purgeOn);
    }

    private void applyRepackState() {
        boolean on = !cbRepack.isChecked();
        cbPurge.setEnabled(on);
        cbDigests.setEnabled(on);
        cbPlayer.setEnabled(on);
        cbPos.setEnabled(on);
        cbHeld.setEnabled(on);
        spMode.setEnabled(on);
        spDifficulty.setEnabled(on);
        spCheats.setEnabled(on);
        spPvp.setEnabled(on);
        spKeepInv.setEnabled(on);
        spDaylight.setEnabled(on);
        if (cbBlock != null) updateBlockEnable();
        updateKindEnable();
    }

    // ================================================================ 组装参数
    private Boolean tri(Spinner sp) {
        int p = sp.getSelectedItemPosition();
        if (p == 1) return Boolean.TRUE;
        if (p == 2) return Boolean.FALSE;
        return null;
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private String summary(Editor.Options opt) {
        StringBuilder sb = new StringBuilder();
        sb.append("世界：").append(current.name).append("\n");
        if (opt.repackOnly) sb.append("· 仅重打包，不改数据\n");
        if (opt.purgeEntities) {
            sb.append("· 删除实体：");
            if (!opt.entityIds.isEmpty()) {
                boolean f = true;
                for (String id : opt.entityIds) {
                    if (!f) sb.append("+");
                    sb.append(id);
                    f = false;
                }
            } else if (opt.entityKinds.isEmpty()) {
                sb.append("全部非玩家实体");
            } else {
                boolean first = true;
                for (String k : opt.entityKinds) {
                    if (!first) sb.append("+");
                    sb.append(Editor.kindLabel(k));
                    first = false;
                }
            }
            sb.append(opt.clearDigests ? "，并清空区块索引\n" : "\n");
        }
        if (opt.clearPlayer) sb.append("· 清空玩家数据（背包与坐标会丢）\n");
        if (opt.posX != null) sb.append("· 玩家坐标 → ").append(opt.posX).append(", ")
                .append(opt.posY).append(", ").append(opt.posZ).append("\n");
        if (opt.blockEdit) sb.append("· 改方块 → ").append(new String[]{"主世界", "下界", "末地"}[Math.min(2, opt.blockDim)])
                .append(" (").append(opt.blockX).append(",").append(opt.blockY).append(",").append(opt.blockZ)
                .append(") = ").append(opt.blockName)
                .append(opt.blockStates == null || opt.blockStates.isEmpty() ? "" : " [" + opt.blockStates + "]")
                .append("\n");
        if (opt.heldItem != null) sb.append("· 手持 → ").append(opt.heldItem)
                .append(" ×").append(opt.heldCount).append("\n");
        if (opt.gameType != null || opt.difficulty != null || opt.cheats != null
                || opt.pvp != null || opt.keepInventory != null || opt.daylight != null) {
            sb.append("· 世界参数会一并修改\n");
        }
        if (opt.timeOfDay != null) {
            long clock = (opt.timeOfDay + 6000L) % 24000L;
            sb.append("· 时间 → ").append(String.format("%02d:%02d",
                    clock / 1000L, (clock % 1000L) * 60L / 1000L))
              .append("（保留当前已过的天数）\n");
        }
        if (opt.time != null) sb.append("· 时间 → ").append(opt.time)
                .append(" tick（绝对值，天数会归零）\n");
        if (opt.weather != null) {
            sb.append("· 天气 → ").append(new String[]{"", "晴", "雨", "雷雨"}[opt.weather])
              .append("，持续 ").append(opt.weatherMinutes).append(" 分钟\n");
        }
        if (opt.spawnX != null) {
            sb.append("· 出生点 → ").append(opt.spawnX).append(", ")
              .append(opt.spawnY).append(", ").append(opt.spawnZ).append("\n");
        }
        if (opt.seed != null) sb.append("· 种子 → ").append(opt.seed).append("（只影响新生成的区块）\n");
        if (!opt.rules.isEmpty()) sb.append("· 游戏规则 → 改 ").append(opt.rules.size()).append(" 项\n");
        sb.append("\n写入前会自动备份原存档。确认写入？");
        return sb.toString();
    }

    private Editor.Options collectOptions() {
        Editor.Options opt = new Editor.Options();
        opt.repackOnly = cbRepack.isChecked();
        opt.clearDigests = cbDigests.isChecked();
        opt.clearPlayer = cbPlayer.isChecked();

        opt.purgeEntities = cbPurge.isChecked();
        String ids = etEntityIds.getText().toString().trim();
        if (!ids.isEmpty()) {
            for (String t : ids.split(",")) {
                String v = t.trim();
                if (!v.isEmpty()) opt.entityIds.add(v);
            }
            opt.purgeEntities = true;
        } else if (opt.purgeEntities && !cbKindAll.isChecked()) {
            if (cbBoat.isChecked()) opt.entityKinds.add("boat");
            if (cbMob.isChecked()) opt.entityKinds.add("mob");
            if (cbItemKind.isChecked()) opt.entityKinds.add("item");
            if (cbXp.isChecked()) opt.entityKinds.add("xp");
            if (cbOther.isChecked()) opt.entityKinds.add("other");
            if (opt.entityKinds.isEmpty()) opt.purgeEntities = false;
        }

        if (cbPos.isChecked()) {
            opt.posX = Double.parseDouble(etPosX.getText().toString().trim());
            opt.posY = Double.parseDouble(etPosY.getText().toString().trim());
            opt.posZ = Double.parseDouble(etPosZ.getText().toString().trim());
        }
        if (cbHeld.isChecked()) {
            opt.heldItem = etItem.getText().toString().trim();
            opt.heldCount = Math.max(1, Math.min(64, parseInt(etItemCount.getText().toString(), 1)));
            opt.heldAux = Math.max(0, Math.min(32767, parseInt(etItemAux.getText().toString(), 0)));
        }
        if (cbBlock != null && cbBlock.isChecked()) {
            opt.blockEdit = true;
            opt.blockDim = Math.max(0, spBlockDim.getSelectedItemPosition());
            opt.blockX = parseInt(etBlockX.getText().toString(), 0);
            opt.blockY = parseInt(etBlockY.getText().toString(), 0);
            opt.blockZ = parseInt(etBlockZ.getText().toString(), 0);
            opt.blockName = etBlockName.getText().toString().trim();
            opt.blockStates = etBlockStates.getText().toString().trim();
        }
        int mode = spMode.getSelectedItemPosition();
        if (mode > 0) opt.gameType = mode - 1;
        int diff = spDifficulty.getSelectedItemPosition();
        if (diff > 0) opt.difficulty = diff - 1;
        opt.cheats = tri(spCheats);
        opt.pvp = tri(spPvp);
        opt.keepInventory = tri(spKeepInv);
        opt.daylight = tri(spDaylight);

        String tc = etTimeCustom.getText().toString().trim();
        if (!tc.isEmpty()) {
            opt.time = Long.parseLong(tc);
        } else {
            int tp = spTime.getSelectedItemPosition();
            if (tp > 0) opt.timeOfDay = (int) new long[]{0, 1000, 6000, 12000, 15000, 18000}[tp];
        }
        int wpos = spWeather.getSelectedItemPosition();
        if (wpos > 0) {
            opt.weather = wpos;
            opt.weatherMinutes = Math.max(1,
                    Math.min(1440, parseInt(etWeatherMin.getText().toString(), 10)));
        }
        if (cbSpawn.isChecked()) {
            opt.spawnX = parseInt(etSpawnX.getText().toString(), 0);
            opt.spawnY = parseInt(etSpawnY.getText().toString(), 32767);
            opt.spawnZ = parseInt(etSpawnZ.getText().toString(), 0);
        }
        String seedText = etSeed.getText().toString().trim();
        if (!seedText.isEmpty()) opt.seed = Long.parseLong(seedText);
        for (java.util.Map.Entry<String, Spinner> e : ruleSpins.entrySet()) {
            int p = e.getValue().getSelectedItemPosition();
            if (p > 0) opt.rules.put(e.getKey(), p == 1 ? 1 : 0);
        }
        return opt;
    }

    /** 一键预设：把世界参数与游戏规则设成常见组合（只改界面，写回时才生效）。 */
    private void applyPreset(int which) {
        if (which == 1) {
            spMode.setSelection(1);      // 生存
            spDifficulty.setSelection(3); // 普通
            spCheats.setSelection(2);    // 关
            spPvp.setSelection(1);
            spKeepInv.setSelection(2);
            spDaylight.setSelection(1);
            setRule("showcoordinates", 2);
            setRule("doimmediaterespawn", 2);
            setRule("doentitydrops", 1);
            setRule("dotiledrops", 1);
            setRule("domobspawning", 1);
            setRule("naturalregeneration", 1);
            setRule("mobgriefing", 1);
            setRule("tntexplodes", 1);
            setRule("commandblocksenabled", 2);
            setRule("doweathercycle", 1);
        } else {
            spMode.setSelection(2);      // 创造
            spDifficulty.setSelection(1); // 和平
            spCheats.setSelection(1);    // 开
            spPvp.setSelection(2);
            spKeepInv.setSelection(1);
            spDaylight.setSelection(1);
            setRule("showcoordinates", 1);
            setRule("doimmediaterespawn", 1);
            setRule("doentitydrops", 2);
            setRule("dotiledrops", 1);
            setRule("domobspawning", 2);
            setRule("naturalregeneration", 1);
            setRule("mobgriefing", 2);
            setRule("tntexplodes", 2);
            setRule("commandblocksenabled", 1);
            setRule("doweathercycle", 1);
        }
        Toast.makeText(this, which == 1 ? "已套用「生存纯净」" : "已套用「创造自由」",
                Toast.LENGTH_SHORT).show();
    }

    private void setRule(String key, int pos) {
        Spinner sp = ruleSpins.get(key);
        if (sp != null) sp.setSelection(pos);
    }

    private boolean validate() {
        if (cbPos.isChecked()) {
            try {
                Double.parseDouble(etPosX.getText().toString().trim());
                Double.parseDouble(etPosY.getText().toString().trim());
                Double.parseDouble(etPosZ.getText().toString().trim());
            } catch (Exception e) {
                tvInfo.setText("坐标没填好：X / Y / Z 都要填数字");
                return false;
            }
        }
        if (cbBlock != null && cbBlock.isChecked()) {
            for (EditText e : new EditText[]{etBlockX, etBlockY, etBlockZ}) {
                String v = e.getText().toString().trim();
                if (v.isEmpty() || v.equals("-")) {
                    tvInfo.setText("改方块要填完整的 X / Y / Z（方块坐标，可以是负数）");
                    return false;
                }
                try { Integer.parseInt(v); } catch (Exception ex) {
                    tvInfo.setText("方块坐标要填整数：" + v);
                    return false;
                }
            }
            if (etBlockName.getText().toString().trim().isEmpty()) {
                tvInfo.setText("请填写方块名，例如 minecraft:stone");
                return false;
            }
            try {
                BlockEdit.parseStates(etBlockStates.getText().toString());
            } catch (Exception ex) {
                tvInfo.setText("状态格式不对：" + ex.getMessage());
                return false;
            }
        }
        if (cbHeld.isChecked() && etItem.getText().toString().trim().isEmpty()) {
            tvInfo.setText("请填写手持物品 ID，例如 diamond_sword");
            return false;
        }
        String tc = etTimeCustom.getText().toString().trim();
        if (!tc.isEmpty()) {
            try {
                long v = Long.parseLong(tc);
                if (v < 0 || v > 24000) throw new NumberFormatException();
            } catch (Exception e) {
                tvInfo.setText("自定义时间要填 0-23999 的整数（tick）");
                return false;
            }
        }
        if (spWeather.getSelectedItemPosition() > 0) {
            String m = etWeatherMin.getText().toString().trim();
            if (!m.isEmpty()) {
                try {
                    int v = Integer.parseInt(m);
                    if (v < 1 || v > 1440) throw new NumberFormatException();
                } catch (Exception e) {
                    tvInfo.setText("天气持续分钟要填 1-1440 的整数");
                    return false;
                }
            }
        }
        if (cbSpawn.isChecked()) {
            for (EditText e : new EditText[]{etSpawnX, etSpawnY, etSpawnZ}) {
                String v = e.getText().toString().trim();
                if (v.isEmpty() || v.equals("-")) {
                    tvInfo.setText("出生点要填完整的 X / Y / Z 整数");
                    return false;
                }
                try { Integer.parseInt(v); } catch (Exception ex) {
                    tvInfo.setText("出生点坐标要填整数：" + v);
                    return false;
                }
            }
        }
        String seedText = etSeed.getText().toString().trim();
        if (!seedText.isEmpty()) {
            try {
                Long.parseLong(seedText);
            } catch (Exception e) {
                tvInfo.setText("种子要填整数（可以带负号），例如 -4172420867104890325");
                return false;
            }
        }
        return true;
    }

    // ================================================================ 执行
    private void confirmWriteBack() {
        if (busy || current == null) return;
        if (!validate()) return;
        final Editor.Options opt = collectOptions();
        new AlertDialog.Builder(this)
                .setTitle("确认写入存档")
                .setMessage(summary(opt))
                .setPositiveButton("备份并写入", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) { runWriteBack(opt); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runWriteBack(final Editor.Options opt) {
        busy = true;
        btnApply.setEnabled(false);
        btnExportZip.setEnabled(false);
        final File outDir = new File(getCacheDir(), "out_" + System.currentTimeMillis());
        final File work = new File(getCacheDir(), "work_" + System.currentTimeMillis());
        new Thread(new Runnable() {
            public void run() {
                try {
                    Editor.Stats st = Editor.processDir(current.dir, outDir, opt, work, new Editor.Progress() {
                        public void onProgress(int pct, final String msg) {
                            ui.post(new Runnable() {
                                public void run() { tvInfo.setText(pct + "% " + msg); }
                            });
                        }
                    });
                    final File backup = WorldStore.writeBack(current, outDir);
                    final String log = "写入完成：删除实体 " + st.purged + " 个，保留键值 " + st.keptCount
                            + " 条"
                            + (st.blockMessage == null ? "" : "\n" + st.blockMessage)
                            + "\n备份在：" + backup.getAbsolutePath();
                    Editor.deleteRecursivePublic(work);
                    Editor.deleteRecursivePublic(outDir);
                    ui.post(new Runnable() {
                        public void run() {
                            current = WorldStore.probe(current.dir);
                            scanNeeded = true;   // 世界名/大小可能变了，返回列表时重扫
                            tvInfo.setText("写入完成，可以回游戏查看了");
                            tvLog.append(log + "\n");
                            busy = false;
                            btnApply.setEnabled(true);
                            btnExportZip.setEnabled(true);
                        }
                    });
                } catch (final Throwable e) {
                    final String msg = crashText(e);
                    Editor.deleteRecursivePublic(work);
                    Editor.deleteRecursivePublic(outDir);
                    ui.post(new Runnable() {
                        public void run() {
                            tvInfo.setText("写入失败：" + friendlyError(e));
                            tvLog.append("写入失败：\n" + msg + "\n");
                            busy = false;
                            btnApply.setEnabled(true);
                            btnExportZip.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private void startExportZip() {
        if (busy || current == null) return;
        if (!validate()) return;
        pendingOptions = collectOptions();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, current.name + "_已处理.zip");
        try {
            startActivityForResult(i, REQ_SAVE);
        } catch (Throwable t) {
            tvLog.append("打开保存失败：" + t + "\n");
        }
    }

    private void doExport(final Uri target, final Editor.Options opt) {
        busy = true;
        btnApply.setEnabled(false);
        btnExportZip.setEnabled(false);
        final File outDir = new File(getCacheDir(), "out_export_" + System.currentTimeMillis());
        final File work = new File(getCacheDir(), "work_export_" + System.currentTimeMillis());
        new Thread(new Runnable() {
            public void run() {
                try {
                    Editor.Stats st = Editor.processDir(current.dir, outDir, opt, work, null);
                    File zip = new File(getCacheDir(), "export.zip");
                    zipWorldDirect(outDir, zip, current.id);
                    InputStream in = new FileInputStream(zip);
                    java.io.OutputStream os = getContentResolver().openOutputStream(target);
                    Editor.copyStream(in, os);
                    in.close();
                    os.close();
                    Editor.deleteRecursivePublic(work);
                    Editor.deleteRecursivePublic(outDir);
                    zip.delete();
                    final String log = "已导出，删除实体 " + st.purged + " 个";
                    ui.post(new Runnable() {
                        public void run() {
                            tvInfo.setText("导出完成");
                            tvLog.append(log + "\n");
                            busy = false;
                            btnApply.setEnabled(true);
                            btnExportZip.setEnabled(true);
                        }
                    });
                } catch (final Throwable e) {
                    final String msg = crashText(e);
                    Editor.deleteRecursivePublic(work);
                    Editor.deleteRecursivePublic(outDir);
                    ui.post(new Runnable() {
                        public void run() {
                            tvInfo.setText("导出失败：" + friendlyError(e));
                            tvLog.append("导出失败：\n" + msg + "\n");
                            busy = false;
                            btnApply.setEnabled(true);
                            btnExportZip.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    /** 把处理结果目录打成 zip，条目前缀用世界 ID，可直接放回 minecraftWorlds。 */
    private void zipWorldDirect(File dir, File zip, String worldId) throws Exception {
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new FileOutputStream(zip));
        try {
            zos.setLevel(6);
            addToZip(dir, worldId, zos);
        } finally {
            zos.close();
        }
    }

    private void addToZip(File dir, String base, java.util.zip.ZipOutputStream zos) throws Exception {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (File k : kids) {
            String name = base == null || base.isEmpty() ? k.getName() : base + "/" + k.getName();
            if (k.isDirectory()) {
                addToZip(k, name, zos);
            } else {
                zos.putNextEntry(new java.util.zip.ZipEntry(name));
                FileInputStream in = new FileInputStream(k);
                try {
                    Editor.copyStream(in, zos);
                } finally {
                    in.close();
                }
                zos.closeEntry();
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PERM) {
            if (hasFilePermission()) startScan();
            else if (tvStatus != null) tvStatus.setText("权限未授予，读不到存档目录");
            return;
        }
        if (req == REQ_SAVE) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingOptions != null) {
                doExport(data.getData(), pendingOptions);
            }
            return;
        }
        if (req == REQ_SAVE_RAW) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingRawWorld != null) {
                doRawExport(pendingRawWorld, data.getData());
            }
            return;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERM) {
            if (hasFilePermission()) startScan();
            else if (tvStatus != null) tvStatus.setText("权限被拒绝，读不到存档目录");
        }
    }

    // ================================================================ 抽屉菜单
    private static final String PREF = "nm_settings";
    private static final String KEY_INTL = "include_intl";
    private static final String KEY_DARK = "dark_theme";

    /** 状态栏跟随当前主题（深色时清掉浅色图标标记）。 */
    private void applySystemBars() {
        try {
            android.view.Window win = getWindow();
            if (Build.VERSION.SDK_INT >= 21) {
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                win.setStatusBarColor(DARK ? C_BG : 0xFFFFFFFF);
                win.setNavigationBarColor(DARK ? C_BG : 0xFFFFFFFF);
                win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(C_BG));
            }
            if (Build.VERSION.SDK_INT >= 23) {
                android.view.View dv = win.getDecorView();
                int vis = dv.getSystemUiVisibility();
                if (DARK) vis &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else vis |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                dv.setSystemUiVisibility(vis);
            }
        } catch (Throwable ignored) {
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private boolean prefBool(String key, boolean def) {
        try {
            return prefs().getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private void setPrefBool(String key, boolean v) {
        try {
            prefs().edit().putBoolean(key, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 顶部栏 + 内容 + 左侧抽屉。 */
    /**
     * 主题过场：一块「新配色」的面板从屏幕上方往下铺满，铺满后换配色并重画当前设置页。
     * 用 translationY 动画，全程在渲染线程做，不会像 recreate() 那样露出窗口默认底色。
     */
    /**
     * 主题过场：直接让「页面背景色」从旧底色渐变到新底色（文字跟着整体淡出），
     * 渐完再换配色重建页面并淡入。不用任何覆盖层，所以不存在“遮住文字”。
     */
    private void animateThemeSwitch(final boolean toDark) {
        if (themeSwitching) return;
        themeSwitching = true;
        final View host = findViewById(android.R.id.content);
        final View page = (host instanceof ViewGroup && ((ViewGroup) host).getChildCount() > 0)
                ? ((ViewGroup) host).getChildAt(0) : host;
        final int from = C_BG;
        final int to = toDark ? 0xFF12151A : 0xFFF7F8FA;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(320);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float f = ((Float) a.getAnimatedValue()).floatValue();
                if (page == null) return;
                page.setBackgroundColor(blendColor(from, to, f));
                // 后半段整体淡出，避免深底压着深色文字那一刻发黑
                page.setAlpha(f < 0.5f ? 1f : 1f - (f - 0.5f) * 2f);
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                applyPalette(toDark);
                applySystemBars();
                showSettings();
                View nc = findViewById(android.R.id.content);
                if (nc != null) {
                    nc.setAlpha(0f);
                    nc.animate().alpha(1f).setDuration(200).start();
                }
                themeSwitching = false;
            }
        });
        va.start();
    }

    /** 两个 ARGB 颜色按比例混合。 */
    private static int blendColor(int from, int to, float f) {
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * f);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * f);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * f);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void shell(View content, String title, String rightLabel, final Runnable rightAction) {
        frame = new FrameLayout(this);

        LinearLayout rootCol = column();
        rootCol.setBackgroundColor(C_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_CARD);

        TextView menuBtn = new TextView(this);
        menuBtn.setText("≡");
        menuBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        menuBtn.setTextColor(C_TEXT);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleDrawer(); }
        });
        bar.addView(menuBtn, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT));
        menuBtn.setPadding(0, dp(16), 0, dp(16));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvTitle.setPadding(dp(4), dp(2), dp(8), dp(2));
        bar.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (rightLabel != null) {
            TextView right = new TextView(this);
            right.setText(rightLabel);
            right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            right.setTextColor(C_ACCENT);
            right.setGravity(Gravity.CENTER);
            right.setPadding(dp(12), 0, dp(14), 0);
            right.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (rightAction != null) rightAction.run(); }
            });
            right.setPadding(dp(12), dp(16), dp(14), dp(16));
            bar.addView(right, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        bar.setMinimumHeight(dp(56));
        bar.setPadding(dp(6), 0, dp(6), 0);
        rootCol.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View hair = new View(this);
        hair.setBackgroundColor(C_STROKE);
        rootCol.addView(hair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        rootCol.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        frame.addView(rootCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.setBackgroundColor(C_BG);

        overlay = new View(this);
        overlay.setBackgroundColor(0x99000000);
        overlay.setVisibility(View.GONE);
        frame.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });

        drawerWidth = Math.round(getResources().getDisplayMetrics().widthPixels * 0.78f);
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(C_CARD);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(drawerWidth,
                ViewGroup.LayoutParams.MATCH_PARENT);
        frame.addView(drawer, dlp);
        drawer.setTranslationX(-drawerWidth);
        drawerOpen = false;

        TextView head = new TextView(this);
        head.setText("我的世界存档编辑器");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(C_TEXT);
        head.setPadding(dp(18), dp(18), dp(18), dp(10));
        drawer.addView(head);

        TextView ver = new TextView(this);
        ver.setText("2.7.10   支持网易版 / 国际基岩版");
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ver.setTextColor(C_HINT);
        ver.setPadding(dp(18), 0, dp(18), dp(12));
        drawer.addView(ver);

        addDrawerItem("主菜单", new Runnable() {
            public void run() {
                if (detailMode || backupMode || settingsMode) showListScreen();
            }
        });
        addDrawerItem("备份管理", new Runnable() { public void run() { showBackupScreen(); } });
        addDrawerDivider();
        addDrawerItem("关于本工具", new Runnable() { public void run() { showAbout(); } });
        addDrawerItem("设置", new Runnable() { public void run() { showSettings(); } });

        setContentView(frame, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void addDrawerDivider() {
        DashedDivider line = new DashedDivider();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(9));
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        drawer.addView(line, lp);
    }

    /** 一条虚线分隔，用自绘 View 实现（shape 描边无法只画一条虚线）。 */
    private class DashedDivider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DashedDivider() {
            super(MainActivity.this);
            float d = getResources().getDisplayMetrics().density;
            p.setColor(C_STROKE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * d);
            PathEffect pe = new DashPathEffect(new float[]{4 * d, 3 * d}, 0);
            p.setPathEffect(pe);
        }
        @Override
        protected void onDraw(Canvas c) {
            float y = getHeight() / 2f;
            c.drawLine(0, y, getWidth(), y, p);
        }
    }

    private void addDrawerItem(String title, final Runnable action) {
        TextView item = new TextView(this);
        item.setText(title);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        item.setTextColor(C_TEXT);
        item.setPadding(dp(18), dp(16), dp(18), dp(16));
        item.setClickable(true);
        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!drawerOpen) return;   // 关闭动画进行中，忽略连点，避免二次重建打断动画
                closeDrawer(action);
            }
        });
        drawer.addView(item);
    }

    private void toggleDrawer() {
        if (drawerOpen) closeDrawer();
        else openDrawer();
    }

    private void openDrawer() {
        if (drawer == null) return;
        drawerOpen = true;
        drawer.animate().cancel();
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().cancel();
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(OPEN_MS - 40).start();
        drawer.animate().translationX(0f).setDuration(OPEN_MS).start();
    }

    private void closeDrawer() {
        closeDrawer(null);
    }

    /**
     * 关抽屉；after 在滑动动画真正跑完之后才执行。
     * 切页面会重建整个界面（抽屉实例被丢弃），若同时进行，动画只会画出半程。
     */
    private void closeDrawer(final Runnable after) {
        if (drawer == null || !drawerOpen) {
            if (after != null) after.run();
            return;
        }
        drawerOpen = false;
        // 先取消可能还在跑的打开动画，避免两个动画抢同一个属性
        drawer.animate().cancel();
        if (overlay != null) {
            overlay.animate().cancel();
            overlay.animate().alpha(0f).setDuration(CLOSE_MS - 40).start();
        }
        drawer.animate().translationX(-drawerWidth).setDuration(CLOSE_MS)
                .withEndAction(new Runnable() {
                    public void run() {
                        if (overlay != null) overlay.setVisibility(View.GONE);
                        if (after != null) after.run();
                    }
                }).start();
    }

    private static final int OPEN_MS = 220;
    private static final int CLOSE_MS = 220;

    private void showSettings() {
        detailMode = false;
        backupMode = false;
        settingsMode = true;

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(root);

        sectionTitle(root, "存档扫描");
        LinearLayout box = cardBox(root);

        LinearLayout row = row(box);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("读取国际基岩版存档", 15, true, C_TEXT);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, llp);

        final Switch sw = new Switch(this);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        sw.setTrackTintList(new ColorStateList(states, new int[]{0x552F6FED, C_BTN_STROKE}));
        sw.setThumbTintList(new ColorStateList(states, new int[]{C_ACCENT, C_HINT}));
        sw.setChecked(prefBool(KEY_INTL, true));
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View sep = new View(this);
        sep.setBackgroundColor(C_STROKE);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        sepLp.topMargin = dp(10);
        sepLp.bottomMargin = dp(10);
        box.addView(sep, sepLp);

        TextView note = text("关闭后只显示网易版存档（com.netease.x19 等目录）；开启时会一并扫描国际版目录（com.mojang.minecraftpe）。改动立即保存，刷新列表后生效。",
                12, false, C_HINT);
        note.setLineSpacing(dp(3), 1f);
        box.addView(note);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                setPrefBool(KEY_INTL, checked);
                scanNeeded = true;
                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
            }
        });

        sectionTitle(root, "外观");
        LinearLayout box3 = cardBox(root);
        LinearLayout row3 = row(box3);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        TextView lb3 = text("深色模式", 15, true, C_TEXT);
        row3.addView(lb3, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch sw3 = new Switch(this);
        sw3.setTrackTintList(new ColorStateList(states, new int[]{0x552F6FED, C_BTN_STROKE}));
        sw3.setThumbTintList(new ColorStateList(states, new int[]{C_ACCENT, C_HINT}));
        sw3.setChecked(DARK);
        row3.addView(sw3, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sw3.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (themeSwitching) { b.setChecked(DARK); return; }
                setPrefBool(KEY_DARK, checked);
                animateThemeSwitch(checked);
            }
        });
        TextView noteDark = text("切换时会有一层新底色从上往下铺满的过场，随后本页按新配色重画；不会丢失其它页面里已填但未写回的内容。", 12, false, C_HINT);
        noteDark.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams ndLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ndLp.topMargin = dp(8);
        box3.addView(noteDark, ndLp);

        sectionTitle(root, "写入与备份");
        LinearLayout box2 = cardBox(root);
        TextView info = text("每次写回前自动备份到 /sdcard/NM存档备份，失败自动回滚；写入前请先在游戏内退出该世界并结束游戏进程。",
                12, false, C_HINT);
        info.setLineSpacing(dp(3), 1f);
        box2.addView(info);

        shell(scroll, "设置", null, null);
    }

    /** 36 格背包总览（0-8 快捷栏，9-35 背包，不含合成栏）。 */
    private void showSlotPage() {
        if (current == null) { Toast.makeText(this, "先打开一个世界", Toast.LENGTH_SHORT).show(); return; }
        detailMode = true;
        backupMode = false;
        settingsMode = false;

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(root);

        root.addView(text("玩家的 36 个背包格：0-8 快捷栏，9-35 背包。合成栏不在存档里，这里也没有。点一格修改。",
                12, false, C_HINT));

        sectionTitle(root, "格子");
        invRows = cardBox(root);

        shell(scroll, "背包格子", "返回", new Runnable() {
            public void run() { openDetail(current); }
        });
        loadSlots();
    }

    /** 从存档读 Inventory 到内存，然后刷新格子列表。 */
    private void loadSlots() {
        invRows.removeAllViews();
        invRows.addView(text("读取中…", 12, false, C_HINT));
        new Thread(new Runnable() {
            public void run() {
                Nbt.Tag tag = null;
                String err = null;
                try {
                    tag = Editor.readPlayerSectionTag(current.dir, "Inventory");
                } catch (Throwable t) {
                    err = friendlyError(t);
                }
                final Nbt.Tag ft = tag;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (fe != null) {
                            invRows.removeAllViews();
                            invRows.addView(text("读取失败：" + fe, 12, false, C_HINT));
                            return;
                        }
                        invSlots.clear();
                        if (ft != null && ft.type == Nbt.TAG_LIST) {
                            Nbt.ListTag lt = (Nbt.ListTag) ft.value;
                            for (Nbt.Tag item : lt.items) {
                                if (item.type != Nbt.TAG_COMPOUND) continue;
                                Nbt.Tag slot = ((Nbt.Compound) item.value).get("Slot");
                                if (slot == null || !(slot.value instanceof Number)) continue;
                                invSlots.put(((Number) slot.value).intValue(), item);
                            }
                        }
                        renderSlots();
                    }
                });
            }
        }).start();
    }

    private void renderSlots() {
        invRows.removeAllViews();
        int shown = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (slot == 0) addSlotGroup("快捷栏（0-8）");
            if (slot == 9) addSlotGroup("背包第一排（9-17）");
            if (slot == 18) addSlotGroup("背包第二排（18-26）");
            if (slot == 27) addSlotGroup("背包第三排（27-35）");

            final int s = slot;
            Nbt.Tag item = invSlots.get(slot);
            String label;
            if (item == null) {
                label = "空";
            } else {
                try {
                    Nbt.Compound c = (Nbt.Compound) item.value;
                    Nbt.Tag name = c.get("Name");
                    Nbt.Tag cnt = c.get("Count");
                    String id = name == null ? "(缺 Name)" : String.valueOf(name.value);
                    String n = (cnt == null || !(cnt.value instanceof Number))
                            ? "?" : String.valueOf(((Number) cnt.value).intValue());
                    label = id.replace("minecraft:", "") + " × " + n;
                    shown++;
                } catch (Throwable t) {
                    label = "(这一格读不出来)";
                }
            }

            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, 0, 0, 0);
            r.setClickable(true);
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1A2F6FED),
                    bg(C_CARD, 8, 0), null));

            TextView idx = text("槽 " + slot, 12, false, C_HINT);
            idx.setSingleLine(true);
            r.addView(idx, new LinearLayout.LayoutParams(dp(58), dp(46)));

            TextView val = text(label, 14, item != null, item != null ? C_TEXT : C_HINT);
            val.setSingleLine(true);
            val.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            r.addView(val, vlp);

            TextView arrow = text("›", 16, false, C_HINT);
            arrow.setSingleLine(true);
            r.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(46)));

            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showSlotEditor(s); }
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            invRows.addView(r, rowLp);

            View hair = new View(this);
            hair.setBackgroundColor(C_STROKE);
            invRows.addView(hair, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        addSlotGroup("共 " + shown + " / 36 格有物品");
    }

    private void addSlotGroup(String s) {
        TextView t = text(s, 11, true, C_HINT);
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        invRows.addView(t, lp);
        View hair = new View(this);
        hair.setBackgroundColor(C_STROKE);
        invRows.addView(hair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    /** 单格编辑页：文本框里是这一格物品的完整 NBT（JSON），保存时类型按原档还原。 */
    private void showSlotEditor(final int slot) {
        detailMode = true;
        backupMode = false;
        settingsMode = false;

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(root);

        final Nbt.Tag old = invSlots.get(slot);

        sectionTitle(root, "槽 " + slot + "（" + (slot < 9 ? "快捷栏" : "背包") + "）");
        LinearLayout c1 = cardBox(root);
        c1.addView(text("整段物品 NBT。必填 Name（物品 ID）、Count（数量 1-127）；Slot 会被强制设成 "
                + slot + "，不用自己写。清空文本框 = 删除这一格的物品。", 12, false, C_HINT));

        final EditText et = new EditText(this);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        styleInput(et);
        et.setTypeface(Typeface.MONOSPACE);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setHorizontallyScrolling(false);
        et.setText(old == null ? defaultItemJson(slot) : NbtJson.toJson(old));
        c1.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        et.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            }
        });

        TextView fmt = text("类型默认按原存档推断（Count=byte、Damage=short）；要写死类型就用包装："
                + "{\"__type__\":\"short\",\"__value__\":9}。", 11, false, C_HINT);
        fmt.setLineSpacing(dp(3), 1f);
        c1.addView(fmt);

        final TextView state = text("", 12, false, C_HINT);
        c1.addView(state);

        sectionTitle(root, "名称与介绍（display）");
        LinearLayout c3 = cardBox(root);
        c3.addView(text("名称=显示名（不是物品 ID），留空=默认；介绍每行一条，空行忽略。",
                11, false, C_HINT));

        LinearLayout nameRow = row(c3);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameLabel = text("名称", 12, false, C_HINT);
        nameRow.addView(nameLabel, new LinearLayout.LayoutParams(dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText etName = new EditText(this);
        etName.setHint("留空=默认名");
        styleInput(etName);
        etName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etName.setInputType(InputType.TYPE_CLASS_TEXT);
        etName.setSingleLine(true);
        etName.setText(ItemDisplay.displayNameOf(old));
        nameRow.addView(etName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final EditText etLore = new EditText(this);
        etLore.setHint("介绍，每行一条");
        styleInput(etLore);
        etLore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        etLore.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etLore.setGravity(Gravity.TOP | Gravity.START);
        etLore.setText(ItemDisplay.displayLoreOf(old));
        etLore.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            }
        });
        c3.addView(etLore, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

        LinearLayout opRow = row(c3);
        opRow.setGravity(Gravity.CENTER_VERTICAL);
        final Switch kd = new Switch(this);
        kd.setText("死亡不掉落");
        kd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        kd.setChecked(ItemDisplay.hasKeepOnDeath(old));
        opRow.addView(kd);

        LinearLayout applyRow = row(c3);
        Button btnApplyDisplay = chip(applyRow, "写入名称/介绍/掉落");

        final TextView dispState = text("", 11, false, C_HINT);
        c3.addView(dispState);
        btnApplyDisplay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String out = ItemDisplay.patchDisplay(et.getText().toString(), old,
                        etName.getText().toString(), etLore.getText().toString(), kd.isChecked());
                if (out == null) {
                    dispState.setText("上面的 NBT 解析不了，先修好再用这个按钮");
                    return;
                }
                et.setText(out);
                String nm = etName.getText().toString().trim();
                dispState.setText("已写入：名称=" + (nm.length() == 0 ? "默认" : nm)
                        + "，介绍=" + ItemDisplay.countLore(etLore.getText().toString()) + " 行，死亡不掉落="
                        + (kd.isChecked() ? "开" : "关"));
            }
        });

        sectionTitle(root, "附魔（tag.ench）");
        LinearLayout c2 = cardBox(root);
        c2.addView(text("基岩版：附魔存在物品 tag 下的 ench 列表，每项 {id, lvl}。id 是附魔数字 ID，lvl 1-32767（short 上限，网易版可写满）。",
                11, false, C_HINT));

        final Spinner spEnch = spinner(c2, ENCH_LABELS);
        LinearLayout lvlRow = row(c2);
        lvlRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView lvlLabel = text("等级", 12, false, C_HINT);
        lvlRow.addView(lvlLabel, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText etLvl = editInRow(lvlRow, "1-32767", false);
        etLvl.setInputType(InputType.TYPE_CLASS_NUMBER);
        etLvl.setText("1");

        LinearLayout enchBtns = row(c2);
        Button btnAddEnch = chip(enchBtns, "添加到该物品");
        Button btnClearEnch = chip(enchBtns, "清除全部附魔");

        final TextView enchState = text("", 12, false, C_HINT);
        c2.addView(enchState);

        btnAddEnch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int lvl = parseInt(etLvl.getText().toString(), 0);
                if (lvl < 1 || lvl > 32767) { enchState.setText("等级必须在 1-32767 之间（short 上限）"); return; }
                int id = (int) ENCH_IDS[Math.max(0, spEnch.getSelectedItemPosition())];
                String out = patchEnchants(et.getText().toString(), old, id, lvl);
                if (out == null) { enchState.setText("先修正上面的 NBT，再加附魔"); return; }
                et.setText(out);
                enchState.setText("已加入 " + ENCH_LABELS[Math.max(0, spEnch.getSelectedItemPosition())]
                        + " " + lvl + " 级");
            }
        });
        btnClearEnch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String out = patchEnchants(et.getText().toString(), old, -1, 0);
                if (out == null) { enchState.setText("先修正上面的 NBT"); return; }
                et.setText(out);
                enchState.setText("已清除附魔");
            }
        });

        final Button btnSave = primaryButton(root, "保存并写入存档");
        final Button btnClear = secondaryButton(root, "清空这一格");

        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String text = et.getText().toString().trim();
                if (text.isEmpty()) { applySlotEdit(slot, null, state, btnSave); return; }

                Nbt.Tag parsed;
                try {
                    parsed = NbtJson.fromJsonAny(text, old);
                } catch (Throwable t) {
                    state.setText("NBT 不合法：" + t.getMessage());
                    return;
                }
                if (parsed.type != Nbt.TAG_COMPOUND) {
                    state.setText("最外层必须是对象 {…}，现在是 " + parsed.type);
                    return;
                }
                Nbt.Compound c = (Nbt.Compound) parsed.value;

                // 必填校验 + 规范化
                Nbt.Tag name = c.get("Name");
                if (name == null || name.type != Nbt.TAG_STRING || String.valueOf(name.value).trim().isEmpty()) {
                    state.setText("缺少 Name（物品 ID），例如 \"minecraft:stone\"");
                    return;
                }
                String id = String.valueOf(name.value).trim();
                if (!id.contains(":")) {
                    id = "minecraft:" + id;
                    c.put("Name", new Nbt.Tag(Nbt.TAG_STRING, "Name", id));
                }
                Nbt.Tag cnt = c.get("Count");
                if (cnt == null) {
                    c.put("Count", new Nbt.Tag(Nbt.TAG_BYTE, "Count", (byte) 1));
                } else if (!(cnt.value instanceof Number)) {
                    state.setText("Count 必须是数字");
                    return;
                } else {
                    int n = ((Number) cnt.value).intValue();
                    if (n < 1 || n > 127) { state.setText("Count 必须在 1-127 之间，现在是 " + n); return; }
                }
                Nbt.Tag dmg = c.get("Damage");
                if (dmg == null) {
                    c.put("Damage", new Nbt.Tag(Nbt.TAG_SHORT, "Damage", (short) 0));
                } else if (!(dmg.value instanceof Number)) {
                    state.setText("Damage 必须是数字");
                    return;
                }
                if (c.get("wasPickedUp") == null && c.get("WasPickedUp") == null) {
                    c.put("WasPickedUp", new Nbt.Tag(Nbt.TAG_BYTE, "WasPickedUp", (byte) 1));
                }
                putByte(c, "Slot", slot);

                applySlotEdit(slot, new Nbt.Tag(Nbt.TAG_COMPOUND, "", c), state, btnSave);
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applySlotEdit(slot, null, state, btnSave);
            }
        });

        shell(scroll, "槽 " + slot, "返回", new Runnable() {
            public void run() { showSlotPage(); }
        });
    }

    /** 附魔数字 ID（基岩版数据值）。 */
    private static final int[] ENCH_IDS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36};
    private static final String[] ENCH_LABELS = {
            "0 保护", "1 火焰保护", "2 摔落保护", "3 爆炸保护", "4 弹射物保护", "5 荆棘",
            "6 水下呼吸", "7 深海探索者", "8 水下速掘", "9 锋利", "10 亡灵杀手", "11 节肢杀手",
            "12 击退", "13 火焰附加", "14 抢夺", "15 效率", "16 精准采集", "17 耐久", "18 时运",
            "19 力量", "20 冲击", "21 火矢", "22 无限", "23 海之眷顾", "24 饵钓", "25 冰霜行者",
            "26 经验修补", "27 消失诅咒", "28 穿刺", "29 激流", "30 忠诚", "31 引雷",
            "32 多重射击", "33 穿透", "34 快速装填", "35 灵魂疾行", "36 迅捷潜行"};

    /**
     * 在物品 NBT 文本里加/清附魔。id<0 表示清空 ench。
     * 返回新的 JSON 文本；文本本身解析不了时返回 null（不动原文）。
     */
    private String patchEnchants(String text, Nbt.Tag template, int id, int lvl) {
        Nbt.Tag parsed;
        try {
            parsed = NbtJson.fromJsonAny(text, template);
        } catch (Throwable t) {
            return null;
        }
        if (parsed.type != Nbt.TAG_COMPOUND) return null;
        Nbt.Compound c = (Nbt.Compound) parsed.value;

        Nbt.Tag tag = c.get("tag");
        if (tag == null || tag.type != Nbt.TAG_COMPOUND) {
            tag = new Nbt.Tag(Nbt.TAG_COMPOUND, "tag", new Nbt.Compound());
            c.put("tag", tag);
        }
        Nbt.Compound tc = (Nbt.Compound) tag.value;

        if (id < 0) {
            tc.remove("ench");
            if (tc.isEmpty()) c.remove("tag");
            return NbtJson.toJson(parsed);
        }

        Nbt.Tag ench = tc.get("ench");
        if (ench == null || ench.type != Nbt.TAG_LIST) {
            Nbt.ListTag lt = new Nbt.ListTag();
            lt.elemType = Nbt.TAG_COMPOUND;
            ench = new Nbt.Tag(Nbt.TAG_LIST, "ench", lt);
            tc.put("ench", ench);
        }
        Nbt.ListTag lt = (Nbt.ListTag) ench.value;
        // 同 ID 覆盖，不重复堆
        for (Nbt.Tag e : lt.items) {
            if (e.type != Nbt.TAG_COMPOUND) continue;
            Nbt.Tag eid = ((Nbt.Compound) e.value).get("id");
            if (eid != null && eid.value instanceof Number && ((Number) eid.value).intValue() == id) {
                ((Nbt.Compound) e.value).put("lvl", new Nbt.Tag(Nbt.TAG_SHORT, "lvl", (short) lvl));
                return NbtJson.toJson(parsed);
            }
        }
        Nbt.Compound item = new Nbt.Compound();
        item.put("id", new Nbt.Tag(Nbt.TAG_SHORT, "id", (short) id));
        item.put("lvl", new Nbt.Tag(Nbt.TAG_SHORT, "lvl", (short) lvl));
        lt.elemType = Nbt.TAG_COMPOUND;
        lt.items.add(new Nbt.Tag(Nbt.TAG_COMPOUND, "", item));
        return NbtJson.toJson(parsed);
    }

    private String defaultItemJson(int slot) {
        return "{\n"
                + "  \"Name\": \"minecraft:stone\",\n"
                + "  \"Count\": 1,\n"
                + "  \"Damage\": 0,\n"
                + "  \"Slot\": " + slot + ",\n"
                + "  \"wasPickedUp\": 1\n"
                + "}";
    }

    private void putStr(Nbt.Compound c, String k, String v) {
        Nbt.Tag t = c.get(k);
        if (t == null) c.put(k, new Nbt.Tag(Nbt.TAG_STRING, k, v));
        else { t.type = Nbt.TAG_STRING; t.value = v; }
    }

    private void putByte(Nbt.Compound c, String k, int v) {
        Nbt.Tag t = c.get(k);
        if (t == null) c.put(k, new Nbt.Tag(Nbt.TAG_BYTE, k, (byte) v));
        else { t.type = Nbt.TAG_BYTE; t.value = (byte) v; }
    }

    private void putShort(Nbt.Compound c, String k, int v) {
        Nbt.Tag t = c.get(k);
        if (t == null) c.put(k, new Nbt.Tag(Nbt.TAG_SHORT, k, (short) v));
        else { t.type = Nbt.TAG_SHORT; t.value = (short) v; }
    }

    /** 把改好的单格写回存档：先更新内存表，再整表 Tag 直写（不经 JSON，类型不丢）。 */
    private void applySlotEdit(final int slot, final Nbt.Tag newItem, final TextView state, Button busyBtn) {
        if (newItem == null) invSlots.remove(slot);
        else invSlots.put(slot, newItem);

        Nbt.ListTag lt = new Nbt.ListTag();
        lt.elemType = Nbt.TAG_COMPOUND;
        for (int i = 0; i < 36; i++) {
            Nbt.Tag t = invSlots.get(i);
            if (t == null) continue;
            if (t.type == Nbt.TAG_COMPOUND) {
                putByte((Nbt.Compound) t.value, "Slot", i);
            }
            lt.items.add(t);
        }
        // 36 以外的旧项（如果有）原样保留
        for (java.util.Map.Entry<Integer, Nbt.Tag> e : invSlots.entrySet()) {
            if (e.getKey() >= 36 || e.getKey() < 0) lt.items.add(e.getValue());
        }
        final Nbt.Tag listTag = new Nbt.Tag(Nbt.TAG_LIST, "Inventory", lt);

        state.setText("写入中…");
        busyBtn.setEnabled(false);
        final File outDir = new File(getCacheDir(), "slot_out_" + System.currentTimeMillis());
        final File work = new File(getCacheDir(), "slot_work_" + System.currentTimeMillis());
        new Thread(new Runnable() {
            public void run() {
                try {
                    Editor.Options opt = new Editor.Options();
                    opt.purgeEntities = false;
                    opt.clearDigests = false;
                    opt.invSection = "Inventory";
                    opt.invTag = listTag;
                    Editor.processDir(current.dir, outDir, opt, work, null);
                    WorldStore.writeBack(current, outDir);
                    Editor.deleteRecursivePublic(work);
                    Editor.deleteRecursivePublic(outDir);
                    ui.post(new Runnable() {
                        public void run() {
                            current = WorldStore.probe(current.dir);
                            Toast.makeText(MainActivity.this, "槽 " + slot + " 已写入", Toast.LENGTH_SHORT).show();
                            showSlotPage();
                        }
                    });
                } catch (final Throwable t) {
                    Editor.deleteRecursivePublic(work);
                    Editor.deleteRecursivePublic(outDir);
                    ui.post(new Runnable() {
                        public void run() {
                            state.setText("写入失败：" + friendlyError(t));
                            busyBtn.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    /** 关于本工具：与「看图标」弹窗同一套自绘样式，跟随深浅主题。 */
    private void showAbout() {
        String ver;
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            ver = "未知";
        }
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });

        LinearLayout card = column();
        card.setBackground(bg(C_CARD, 14, C_STROKE));
        card.setPadding(dp(18), dp(16), dp(18), dp(8));
        card.setClickable(true);

        // 标题 + 版本徽标同一行
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("关于本工具", 18, true, C_TEXT);
        headRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chipVer = new TextView(this);
        chipVer.setText("v" + ver);
        chipVer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chipVer.setTextColor(C_ACCENT);
        chipVer.setBackground(bg(C_BTN_BG, 7, C_BTN_STROKE));
        chipVer.setPadding(dp(8), dp(3), dp(8), dp(3));
        headRow.addView(chipVer);
        card.addView(headRow);

        ScrollView body = new ScrollView(this);
        LinearLayout bodyCol = column();
        body.addView(bodyCol, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = text("读取手机里的基岩版存档目录，列出所有世界，点进去就能改。"
                + "支持网易版（加密库）与国际基岩版（标准库）。", 12, false, C_SUB);
        intro.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iLp.topMargin = dp(8);
        bodyCol.addView(intro, iLp);

        bodyCol.addView(sectionLabel("能做的事"));
        String[] items = {
                "删除非玩家实体（整体 / 分类 / 指定生物）",
                "改世界参数：模式、难度、作弊、PVP、不掉落、昼夜",
                "改时间、天气、出生点、随机种子",
                "游戏规则 10 项 + 生存 / 创造预设",
                "改玩家坐标、手持物品、36 格背包、附魔、名称与介绍",
                "改单个方块（含方块状态）",
                "整目录原样备份、原样导出 zip（不改数据）",
                "深色 / 浅色主题",
        };
        for (String it : items) {
            TextView row = text("·  " + it, 12, false, C_SUB);
            row.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(5);
            bodyCol.addView(row, rlp);
        }

        bodyCol.addView(sectionLabel("写入安全"));
        TextView safe = text("每次写入前自动备份到 /sdcard/NM存档备份，失败自动回滚。"
                + "写入前请先在游戏内退出该世界并结束游戏进程。", 12, false, C_SUB);
        safe.setLineSpacing(dp(4), 1f);
        bodyCol.addView(safe);

        bodyCol.addView(sectionLabel("说明"));
        TextView note = text("只重写存档里的 db 与 level.dat，其它文件（资源包、图标等）原样搬运；"
                + "改方块不会重算高度图与光照，也不会创建箱子/告示牌这类方块实体。", 12, false, C_HINT);
        note.setLineSpacing(dp(4), 1f);
        bodyCol.addView(note);

        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(10);
        card.addView(body, bodyLp);

        View hair = new View(this);
        hair.setBackgroundColor(C_STROKE);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        hLp.topMargin = dp(10);
        card.addView(hair, hLp);

        TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ok.setTypeface(null, Typeface.BOLD);
        ok.setTextColor(C_ACCENT);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(dp(12), dp(12), dp(12), dp(12));
        ok.setClickable(true);
        // 必须给 mask，否则 RippleDrawable 按“无边界”画成一大团圆，长按时会糊出屏幕
        GradientDrawable okMask = new GradientDrawable();
        okMask.setColor(0xFF000000);
        okMask.setCornerRadius(dp(8));
        ok.setBackground(new RippleDrawable(ColorStateList.valueOf(0x332F6FED), null, okMask));
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        card.addView(ok, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        clp.topMargin = dp(18);
        clp.bottomMargin = dp(18);
        root.addView(card, clp);

        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dlg.show();
        // 卡片高度按内容，但不超出可用高度（超高时正文区滚动）
        root.post(new Runnable() {
            public void run() {
                int max = root.getHeight() - dp(48);
                if (max <= 0 || card.getHeight() <= max) return;
                // 内容超高：把正文区压到剩余高度，改由它内部滚动
                int fixed = card.getHeight() - body.getHeight();
                LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) body.getLayoutParams();
                blp.height = Math.max(max - fixed, dp(120));
                body.setLayoutParams(blp);
            }
        });
    }

    /** 弹窗里的小节标题。 */
    private TextView sectionLabel(String s) {
        TextView tv = text(s, 12, true, C_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ================================================================ 备份管理
    private void showBackupScreen() {
        detailMode = false;
        backupMode = true;
        settingsMode = false;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView bp = text(WorldStore.backupRoot().getAbsolutePath(), 11, false, C_HINT);
        bp.setSingleLine(true);
        bp.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(bp);

        LinearLayout note = cardBox(root);
        TextView nt = text("备份分两种：整目录备份（db + level.dat + 资源包 + 图标）和写回前的自动备份（只含 db + level.dat + 图标）。\n还原方法：把备份里的世界 ID 文件夹复制回 minecraftWorlds 覆盖即可。", 12, false, C_SUB);
        nt.setLineSpacing(dp(3), 1f);
        note.addView(nt);

        TextView rf = link(root, "刷新列表 ›");
        rf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showBackupScreen(); }
        });

        final LinearLayout list = column();
        root.addView(list);

        final List<WorldStore.BackupEntry> items = WorldStore.listBackups();
        if (items.isEmpty()) {
            root.addView(text("这个目录里还没有备份。\n在存档列表里点「备份到手机」就会生成。", 14, false, C_SUB));
        } else {
            root.addView(text("共 " + items.size() + " 个备份，点某个可查看说明或删除", 13, false, C_SUB));
        }
        long total = 0;
        for (final WorldStore.BackupEntry b : items) {
            total += b.size;
            LinearLayout card = column();
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(bg(C_CARD, 12, C_STROKE));
            card.setClickable(true);
            card.addView(text(b.name, 15, true, C_TEXT));
            card.addView(text(b.type + "    世界 ID：" + b.worldId, 12, false, C_SUB));
            card.addView(text(WorldStore.sizeText(b.size) + "    " + WorldStore.timeText(b.time), 12, false, C_SUB));
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showBackupDetail(b); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            list.addView(card, lp);
        }
        if (!items.isEmpty()) {
            root.addView(text("合计占用 " + WorldStore.sizeText(total), 12, false, C_HINT));
        }

        shell(scroll, "备份管理", null, null);
    }

    private void showBackupDetail(final WorldStore.BackupEntry b) {
        String note = WorldStore.readNote(b.dir);
        new AlertDialog.Builder(this)
                .setTitle(b.name)
                .setMessage(note + "\n路径：\n" + b.dir.getAbsolutePath())
                .setPositiveButton("删除这个备份", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) { confirmDeleteBackup(b); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmDeleteBackup(final WorldStore.BackupEntry b) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("将永久删除：\n" + b.name + "\n（" + WorldStore.sizeText(b.size) + "）\n\n删除后无法恢复，原存档不受影响。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            WorldStore.deleteBackup(b.dir);
                            showBackupScreen();
                        } catch (Throwable e) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("删除失败")
                                    .setMessage(friendlyError(e))
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (detailMode || backupMode || settingsMode) {
            showListScreen();
            return;
        }
        super.onBackPressed();
    }

    // ================================================================ 外观样件
    private static int C_BG = 0xFFF7F8FA;
    private static int C_CARD = 0xFFFFFFFF;
    private static int C_STROKE = 0xFFE4E7EC;
    private static int C_TEXT = 0xFF111827;
    private static int C_SUB = 0xFF6B7280;
    private static int C_HINT = 0xFF9CA3AF;
    private static int C_ACCENT = 0xFF2F6FED;
    // 次级按钮配色（跟随主题）
    private static int C_BTN_BG = 0xFFF4F6FA;
    private static int C_BTN_TEXT = 0xFF374151;
    private static int C_BTN_STROKE = 0xFFE1E4EA;
    private static ColorStateList COLOR_STATES = new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{0xFF2F6FED, 0xFF9CA3AF});
    private static boolean DARK = false;

    /** 切换整套配色；深色不做纯黑，避免 OLED 上边界糊在一起。 */
    static void applyPalette(boolean dark) {
        DARK = dark;
        if (dark) {
            C_BG = 0xFF12151A;
            C_CARD = 0xFF1B1F26;
            C_STROKE = 0xFF2B313A;
            C_TEXT = 0xFFE9ECF1;
            C_SUB = 0xFFAEB7C4;
            C_HINT = 0xFF838D9C;
            C_ACCENT = 0xFF6E9BFF;
            C_BTN_BG = 0xFF232830;
            C_BTN_TEXT = 0xFFDCE2EA;
            C_BTN_STROKE = 0xFF333A45;
        } else {
            C_BG = 0xFFF7F8FA;
            C_CARD = 0xFFFFFFFF;
            C_STROKE = 0xFFE4E7EC;
            C_TEXT = 0xFF111827;
            C_SUB = 0xFF6B7280;
            C_HINT = 0xFF9CA3AF;
            C_ACCENT = 0xFF2F6FED;
            C_BTN_BG = 0xFFF4F6FA;
            C_BTN_TEXT = 0xFF374151;
            C_BTN_STROKE = 0xFFE1E4EA;
        }
        COLOR_STATES = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{C_ACCENT, C_HINT});
    }

    /** 圆角背景（0 表示无描边）。 */
    private Drawable bg(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private LinearLayout cardBox(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(bg(C_CARD, 12, C_STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(box, lp);
        return box;
    }

    private void sectionTitle(LinearLayout parent, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(C_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(2);
        parent.addView(tv, lp);
    }

    /** 等宽小按钮（卡片里的次级操作）。 */
    private Button chip(LinearLayout row, String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(C_BTN_TEXT);
        b.setBackground(bg(C_BTN_BG, 9, C_BTN_STROKE));
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(6), dp(9), dp(6), dp(9));
        b.setElevation(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.topMargin = dp(10);
        lp.rightMargin = dp(4);
        row.addView(b, lp);
        return b;
    }

    private Button primaryButton(LinearLayout parent, String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(bg(C_ACCENT, 10, 0));
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        b.setElevation(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        parent.addView(b, lp);
        return b;
    }

    private Button secondaryButton(LinearLayout parent, String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(C_BTN_TEXT);
        b.setBackground(bg(C_BTN_BG, 10, C_BTN_STROKE));
        b.setPadding(dp(10), dp(11), dp(10), dp(11));
        b.setElevation(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(b, lp);
        return b;
    }

    private TextView link(LinearLayout parent, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(C_ACCENT);
        tv.setPadding(0, dp(10), 0, dp(2));
        tv.setClickable(true);
        parent.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private void addField(LinearLayout parent, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(C_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(tv, lp);
    }

    // ================================================================ 小工具
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(l, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private Button button(LinearLayout parent, String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(C_BTN_TEXT);
        b.setBackground(bg(C_BTN_BG, 10, C_BTN_STROKE));
        b.setPadding(dp(10), dp(11), dp(10), dp(11));
        b.setElevation(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(b, lp);
        return b;
    }

    private CheckBox check(LinearLayout parent, String s, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(s);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cb.setTextColor(C_TEXT);
        cb.setButtonTintList(COLOR_STATES);
        cb.setChecked(checked);
        parent.addView(cb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cb;
    }

    private EditText edit(LinearLayout parent, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        styleInput(et);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        parent.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private EditText editInRow(LinearLayout parent, String hint, boolean signed) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        styleInput(et);
        if (signed) {
            et.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
        } else {
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        parent.addView(et, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return et;
    }

    private Spinner spinner(LinearLayout parent, String[] items) {
        Spinner sp = new Spinner(this);
        TintAdapter ad = new TintAdapter(this, items);
        sp.setPopupBackgroundDrawable(bg(C_CARD, 8, C_STROKE));
        sp.setAdapter(ad);
        parent.addView(sp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sp;
    }

    /** 输入框统一外观（浅色/深色都跟随主题）。 */
    private void styleInput(EditText et) {
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_HINT);
        et.setBackground(bg(C_BTN_BG, 8, C_BTN_STROKE));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
    }

    private boolean systemIsDark() {
        try {
            int mode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 下拉框文字跟随主题（系统默认布局的文字色在无 theme 环境下不可控）。 */
    private class TintAdapter extends ArrayAdapter<String> {
        TintAdapter(android.content.Context c, String[] items) {
            super(c, android.R.layout.simple_spinner_item, items);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }
        private void paint(TextView t) {
            if (t != null) t.setTextColor(C_TEXT);
        }
        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            View v = super.getView(pos, cv, parent);
            paint(v instanceof TextView ? (TextView) v : null);
            return v;
        }
        @Override
        public View getDropDownView(int pos, View cv, ViewGroup parent) {
            View v = super.getDropDownView(pos, cv, parent);
            paint(v instanceof TextView ? (TextView) v : null);
            return v;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String describe(Editor.Stats st) {
        StringBuilder sb = new StringBuilder();
        sb.append("世界名：").append(st.worldName).append("\n");
        sb.append("库：").append(st.encrypted ? "网易加密" : "未加密");
        if (st.worldInfo != null) {
            LevelDat.Info wi = st.worldInfo;
            sb.append("   版本：").append(wi.version.isEmpty() ? "未知" : wi.version);
            sb.append("\n模式：").append(modeLabel(wi.gameType))
              .append("    难度：").append(diffLabel(wi.difficulty))
              .append("    作弊：").append(wi.cheats ? "开" : "关")
              .append("    不掉落：").append(wi.keepInventory ? "开" : "关");
            long clock = (wi.time + 6000L) % 24000L;
            if (clock < 0) clock += 24000L;
            sb.append("\n时间：第 ").append(wi.time / 24000L + 1).append(" 天 ")
              .append(String.format("%02d:%02d", clock / 1000L, (clock % 1000L) * 60L / 1000L))
              .append("    种子：").append(wi.seed)
              .append("\n出生点：").append(wi.spawnX).append(", ").append(wi.spawnY)
              .append(", ").append(wi.spawnZ);
        }
        sb.append("\n");
        if (st.playerPos != null) {
            sb.append("玩家坐标：").append(fmt(st.playerPos[0])).append(", ")
              .append(fmt(st.playerPos[1])).append(", ")
              .append(fmt(st.playerPos[2]));
        } else {
            sb.append("玩家坐标：读取不到");
        }
        sb.append("\n手持：").append(st.mainhand == null || st.mainhand.trim().isEmpty() ? "空手" : st.mainhand);
        sb.append("\n非玩家实体：").append(st.entityCount).append(" 个");
        if (!st.byKind.isEmpty()) {
            sb.append("（");
            boolean first = true;
            for (java.util.Map.Entry<String, Integer> e : st.byKind.entrySet()) {
                if (!first) sb.append(" / ");
                sb.append(Editor.kindLabel(e.getKey())).append(" ").append(e.getValue());
                first = false;
            }
            sb.append("）");
        }
        sb.append("\n区块实体索引：").append(st.digestCount).append(" 条    其它键值：").append(st.keptCount).append(" 条");
        return sb.toString();
    }

    private String fmt(double d) {
        if (Math.abs(d - Math.round(d)) < 0.001) return String.valueOf(Math.round(d));
        return String.format("%.2f", d);
    }

    private String modeLabel(int gt) {
        if (gt == 0) return "生存";
        if (gt == 1) return "创造";
        if (gt == 2) return "冒险";
        if (gt == 3) return "旁观";
        return "未知";
    }

    private String diffLabel(int d) {
        if (d == 0) return "和平";
        if (d == 1) return "简单";
        if (d == 2) return "普通";
        if (d == 3) return "困难";
        return "未知";
    }

    /** 把 OutOfMemoryError 翻译成用户能懂的话，并给出下一步。 */
    private String friendlyError(Throwable e) {
        if (e instanceof OutOfMemoryError) {
            long max = Runtime.getRuntime().maxMemory() / 1048576L;
            return "内存不足（可用堆约 " + max + "MB），这个存档的库索引太大。\n"
                    + "先别急：可以用列表里的「备份到手机」原样保住存档，不会丢数据。\n"
                    + "然后把这个存档的目录名发我，我改成分批读取的版本。";
        }
        return String.valueOf(e);
    }

    // ================================================================ 崩溃兜底
    private String crashText(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return sw.toString();
        } catch (Throwable ignored) {
            return String.valueOf(t);
        }
    }

    private void showFatal(Throwable t) {
        try {
            String text = crashText(t);
            saveCrash(text);
            ScrollView scroll = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setTextColor(Color.RED);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setPadding(dp(12), dp(12), dp(12), dp(12));
            tv.setGravity(Gravity.START);
            tv.setText("启动失败，请把下面内容发给我：\n\n" + text);
            scroll.addView(tv);
            setContentView(scroll);
        } catch (Throwable ignored) {
        }
    }

    private void saveCrash(String text) {
        try {
            File f = new File(getCacheDir(), CRASH_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    saveCrash(crashText(e));
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
}

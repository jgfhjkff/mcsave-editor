package com.nmsave.editor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * 小窗模式：前台服务里挂一个蓝色圆形悬浮按钮，点开后弹小窗面板。
 * 面板只提供两项简易能力：改玩家 NBT（坐标 / 手持）、清空非玩家生物实体。
 */
public class FloatService extends Service {

    public static boolean running = false;
    public static volatile boolean windowShown = false;
    public static volatile String lastError = null;

    private static final String PREF = "nm_settings";
    private static final String CHANNEL = "nm_float";
    private static final int NOTIF_ID = 2101;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout ball;
    private LinearLayout panel;
    private TextView panelTitle;
    private LinearLayout panelBody;
    private ScrollView panelScroll;
    private boolean panelOpen = false;

    private final Runnable watchdog = new Runnable() {
        public void run() {
            ensureWindow();
            ui.postDelayed(this, 1000);
        }
    };

    /** 每秒自检：圆钮还在就无事发生，不在就重新生成。 */
    private void ensureWindow() {
        try {
            if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (showing()) return;
            removeView(ball);
            ball = null;
            addBall();
        } catch (Throwable ignored) {
        }
    }

    private int darkText, subText, hintText, accent, cardBg, stroke, fieldBg;
    private boolean dark;

    public static void start(Context c) {
        try {
            c.startService(new Intent(c, FloatService.class));
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, FloatService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        readPalette();
        startForegroundCompat();
        addBall();
        ui.postDelayed(watchdog, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureWindow();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 清掉最近任务后仍保留小窗，并补挂一次（部分 ROM 会顺手收走悬浮窗）
        ui.postDelayed(new Runnable() {
            public void run() { ensureWindow(); }
        }, 600);
        super.onTaskRemoved(rootIntent);
    }

    private boolean showing() {
        return ball != null && ball.isAttachedToWindow() && ball.getWindowToken() != null;
    }

    @Override
    public void onDestroy() {
        running = false;
        windowShown = false;
        ui.removeCallbacks(watchdog);
        removeView(panel);
        removeView(ball);
        panel = null;
        ball = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ 通知（前台服务保活）
    private void startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(CHANNEL, "小窗模式",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            b.setContentTitle("小窗模式运行中")
                    .setContentText("点蓝色圆形按钮可快速改存档")
                    .setSmallIcon(R.drawable.ic_launcher_fg)
                    .setOngoing(true);
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable ignored) {
        }
    }

    private void readPalette() {
        dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            darkText = 0xFFE9ECF1; subText = 0xFFAEB7C4; hintText = 0xFF838D9C;
            accent = 0xFF6E9BFF; cardBg = 0xFF1B1F26; stroke = 0xFF2B313A; fieldBg = 0xFF232830;
        } else {
            darkText = 0xFF111827; subText = 0xFF6B7280; hintText = 0xFF9CA3AF;
            accent = 0xFF2F6FED; cardBg = 0xFFFFFFFF; stroke = 0xFFE4E7EC; fieldBg = 0xFFF4F6FA;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private GradientDrawable bg(int color, int radius, int strokeColor) {
        GradientDrawable g = bg(color, radius);
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    private void removeView(View v) {
        try {
            if (v != null && v.getParent() != null) wm.removeView(v);
            if (v == ball) windowShown = false;
        } catch (Throwable ignored) {
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ------------------------------------------------------------ 悬浮圆钮
    private void addBall() {
        if (wm == null) return;
        ball = new FrameLayout(this);
        ball.setBackground(bg(0xFF2F6FED, 28));
        ball.setElevation(dp(8));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher_fg);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(34), dp(34));
        ilp.gravity = Gravity.CENTER;
        ball.addView(icon, ilp);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(56), dp(56), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = getResources().getDisplayMetrics().widthPixels - dp(72);
        lp.y = getResources().getDisplayMetrics().heightPixels / 3;

        final int[] downX = {0}, downY = {0}, startX = {0}, startY = {0};
        final boolean[] moved = {false};
        ball.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX[0] = (int) e.getRawX();
                        downY[0] = (int) e.getRawY();
                        startX[0] = lp.x;
                        startY[0] = lp.y;
                        moved[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) e.getRawX() - downX[0];
                        int dy = (int) e.getRawY() - downY[0];
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved[0] = true;
                        int sw = getResources().getDisplayMetrics().widthPixels;
                        int sh = getResources().getDisplayMetrics().heightPixels;
                        lp.x = Math.max(0, Math.min(sw - dp(56), startX[0] + dx));
                        lp.y = Math.max(0, Math.min(sh - dp(56), startY[0] + dy));
                        try {
                            wm.updateViewLayout(ball, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved[0]) togglePanel();
                        return true;
                    default:
                        return true;
                }
            }
        });
        try {
            wm.addView(ball, lp);
            windowShown = true;
            lastError = null;
        } catch (Throwable t) {
            // 权限被收走 / 窗口被系统占用时不结束服务，下一秒 ensureWindow 再试
            windowShown = false;
            lastError = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
            removeView(ball);
            ball = null;
        }
    }

    // ------------------------------------------------------------ 小窗面板
    private void togglePanel() {
        if (panelOpen) {
            closePanel();
        } else {
            openPanel();
        }
    }

    private void closePanel() {
        panelOpen = false;
        removeView(panel);
    }

    private void openPanel() {
        if (panel == null) {
            panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(bg(cardBg, 16, stroke));
            panel.setPadding(dp(14), dp(12), dp(14), dp(12));
            panel.setElevation(dp(10));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            panelTitle = new TextView(this);
            panelTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            panelTitle.setTextColor(darkText);
            panelTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            panelTitle.setText("小窗 · 存档");
            head.addView(panelTitle, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = new TextView(this);
            close.setText("✕");
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            close.setTextColor(hintText);
            close.setGravity(Gravity.CENTER);
            close.setPadding(dp(10), dp(4), dp(4), dp(4));
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { closePanel(); }
            });
            head.addView(close);
            panel.addView(head);

            ScrollView sc = new ScrollView(this);
            panelScroll = sc;
            panelBody = new LinearLayout(this);
            panelBody.setOrientation(LinearLayout.VERTICAL);
            sc.addView(panelBody);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
            slp.topMargin = dp(6);
            panel.addView(sc, slp);
        }
        showWorldList();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(360)),
                WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(56);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        try {
            wm.addView(panel, lp);
            panelOpen = true;
        } catch (Throwable t) {
            panelOpen = false;
        }
    }

    /** 列表页把可视区放大到屏幕的 72%（最多 560dp），编��页保持 300dp 给键盘留位。 */
    private void setBodyTall(boolean tall) {
        if (panelScroll == null) return;
        int density = getResources().getDisplayMetrics().densityDpi / 160;
        if (density <= 0) density = 1;
        int screenDp = getResources().getDisplayMetrics().heightPixels / density;
        int hDp = tall ? Math.min((int) (screenDp * 0.72f), 600) : 300;
        int px = dp(hDp);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panelScroll.getLayoutParams();
        if (lp.height == px) return;
        lp.height = px;
        panelScroll.setLayoutParams(lp);
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(6), 0, dp(2));
        return tv;
    }

    private TextView action(String s, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(11), dp(10), dp(11));
        tv.setBackground(bg(fieldBg, 10, stroke));
        tv.setClickable(true);
        return tv;
    }

    /** 列表：只扫一次，之后用缓存；「刷新列表」才重扫。 */
    private java.util.List<WorldStore.World> cache;
    private String cacheErr;
    private boolean scanning;

    private void showWorldList() {
        showWorldList(false);
    }

    private void showWorldList(boolean force) {
        if (panelBody == null) return;
        if (panelTitle != null) panelTitle.setText("小窗 · 存档");
        setBodyTall(true);
        panelBody.removeAllViews();
        if (!force && cache != null) {
            renderWorlds(cache, cacheErr);
            return;
        }
        if (scanning) {
            panelBody.addView(label("正在扫描存档…", 12, hintText, false));
            return;
        }
        scanning = true;
        panelBody.addView(label("正在扫描存档…（只扫这一次，以后用缓存）", 12, hintText, false));
        final boolean includeIntl = prefs().getBoolean("include_intl", true);
        new Thread(new Runnable() {
            public void run() {
                final WorldStore.ScanResult r = WorldStore.scan(includeIntl);
                ui.post(new Runnable() {
                    public void run() {
                        scanning = false;
                        cache = r.worlds;
                        cacheErr = r.worlds.isEmpty() ? "没找到存档。请确认已授予「所有文件访问权限」。" : null;
                        if (panelBody == null) return;
                        renderWorlds(cache, cacheErr);
                    }
                });
            }
        }).start();
    }

    private void renderWorlds(final java.util.List<WorldStore.World> worlds, String emptyTip) {
        if (panelBody == null) return;
        panelBody.removeAllViews();
        TextView refresh = label("刷新列表", 12, accent, false);
        refresh.setClickable(true);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showWorldList(true); }
        });
        panelBody.addView(refresh);
        if (worlds == null || worlds.isEmpty()) {
            panelBody.addView(label(emptyTip == null ? "没找到存档。" : emptyTip, 12, hintText, false));
            return;
        }
        panelBody.addView(label("共 " + worlds.size() + " 个存档，点一个进入简易编辑", 12, hintText, false));
        for (final WorldStore.World w : worlds) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(bg(fieldBg, 12, stroke));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setClickable(true);
            card.addView(label(w.name, 14, darkText, true));
            String sub = WorldStore.relTime(w.modified)
                    + "    " + ("intl".equals(w.edition) ? "国际版" : ("netease".equals(w.edition) ? "网易版" : "未知版"))
                    + "    " + WorldStore.sizeText(w.sizeBytes)
                    + (w.encrypted ? "    加密库" : "    未加密库");
            TextView t2 = label(sub, 11, hintText, false);
            t2.setPadding(0, 0, 0, 0);
            card.addView(t2);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showWorldActions(w); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            panelBody.addView(card, lp);
        }
    }

    private void showWorldActions(final WorldStore.World w) {
        if (panelBody == null) return;
        setBodyTall(false);
        if (panelTitle != null) panelTitle.setText(w.name);
        panelBody.removeAllViews();

        TextView back = label("‹ 返回列表", 12, accent, false);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showWorldList(); }
        });
        panelBody.addView(back);

        TextView note = label("简易模式只有两项。写入前请先在游戏里退出该世界；每次写入会自动备份到 /sdcard/NM存档备份。", 11, hintText, false);
        note.setLineSpacing(dp(3), 1f);
        panelBody.addView(note);

        final TextView state = label("", 12, hintText, false);
        state.setLineSpacing(dp(3), 1f);

        TextView nbt = action("修改 NBT（JSON 编辑 / 快速附魔）", accent);
        nbt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showInventory(w); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.topMargin = dp(10);
        panelBody.addView(nbt, lp1);

        TextView mob = action("清空非玩家生物实体", 0xFFE5484D);
        mob.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Editor.Options opt = new Editor.Options();
                opt.purgeEntities = true;
                opt.clearDigests = true;
                runWrite(w, opt, state);
            }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(8);
        panelBody.addView(mob, lp2);

        panelBody.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------ 背包 NBT（JSON + 快速附魔）
    private java.util.Map<Integer, Nbt.Tag> inv = new java.util.LinkedHashMap<>();
    private int enchSel = 0;

    private void showInventory(final WorldStore.World w) {
        if (panelBody == null) return;
        setBodyTall(true);
        if (panelTitle != null) panelTitle.setText("修改 NBT · " + w.name);
        panelBody.removeAllViews();
        TextView back = label("‹ 返回", 12, accent, false);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showWorldActions(w); }
        });
        panelBody.addView(back);
        panelBody.addView(label("读取背包…", 12, hintText, false));

        new Thread(new Runnable() {
            public void run() {
                Nbt.Tag tag = null;
                String err = null;
                try {
                    tag = Editor.readPlayerSectionTag(w.dir, "Inventory");
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final Nbt.Tag ft = tag;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (panelBody == null) return;
                        inv.clear();
                        if (ft != null && ft.type == Nbt.TAG_LIST) {
                            Nbt.ListTag lt = (Nbt.ListTag) ft.value;
                            for (Nbt.Tag item : lt.items) {
                                if (item.type != Nbt.TAG_COMPOUND) continue;
                                Nbt.Tag sl = ((Nbt.Compound) item.value).get("Slot");
                                if (sl == null || !(sl.value instanceof Number)) continue;
                                inv.put(((Number) sl.value).intValue(), item);
                            }
                        }
                        panelBody.removeAllViews();
                        TextView bk = label("‹ 返回", 12, accent, false);
                        bk.setClickable(true);
                        bk.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) { showWorldActions(w); }
                        });
                        LinearLayout hdr = new LinearLayout(FloatService.this);
                        hdr.setOrientation(LinearLayout.HORIZONTAL);
                        hdr.setGravity(Gravity.CENTER_VERTICAL);
                        hdr.addView(bk, new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        TextView rf = label("刷新", 12, accent, false);
                        rf.setClickable(true);
                        rf.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) { showInventory(w); }
                        });
                        hdr.addView(rf);
                        panelBody.addView(hdr);
                        if (fe != null) panelBody.addView(label("读取失败：" + fe, 12, 0xFFE5484D, false));
                        else panelBody.addView(label("点一格看它的完整 NBT（JSON），可加附魔后再写回。", 12, hintText, false));

                        for (int slot = 0; slot < 36; slot++) {
                            final int sIdx = slot;
                            Nbt.Tag it = inv.get(slot);
                            String txt = "空";
                            if (it != null) {
                                try {
                                    Nbt.Compound c = (Nbt.Compound) it.value;
                                    Nbt.Tag nm = c.get("Name");
                                    Nbt.Tag cn = c.get("Count");
                                    String id = nm == null ? "(缺 Name)" : String.valueOf(nm.value).replace("minecraft:", "");
                                    txt = id + " × " + (cn != null && cn.value instanceof Number
                                            ? ((Number) cn.value).intValue() : 1);
                                } catch (Throwable t) {
                                    txt = "(读不出)";
                                }
                            }
                            LinearLayout r = new LinearLayout(FloatService.this);
                            r.setOrientation(LinearLayout.HORIZONTAL);
                            r.setGravity(Gravity.CENTER_VERTICAL);
                            r.setPadding(dp(10), dp(9), dp(8), dp(9));
                            r.setBackground(bg(fieldBg, 8, stroke));
                            r.setClickable(true);
                            TextView idx = label("槽 " + slot, 11, hintText, false);
                            idx.setPadding(0, 0, 0, 0);
                            idx.setSingleLine(true);
                            r.addView(idx, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
                            TextView v = label(txt, 13, it != null ? darkText : hintText, it != null);
                            v.setSingleLine(true);
                            v.setPadding(0, 0, 0, 0);
                            r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                            r.setOnClickListener(new View.OnClickListener() {
                                public void onClick(View view) {
                                    showItemEditor(w, sIdx, inv.get(sIdx), null);
                                }
                            });
                            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            rlp.topMargin = dp(6);
                            panelBody.addView(r, rlp);
                        }
                    }
                });
            }
        }).start();
    }

    private void showItemEditor(final WorldStore.World w, final int slot, final Nbt.Tag old, String keepText) {
        if (panelBody == null) return;
        setBodyTall(false);
        if (panelTitle != null) panelTitle.setText("槽 " + slot + " · NBT");
        panelBody.removeAllViews();

        TextView back = label("‹ 返回背包", 12, accent, false);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showInventory(w); }
        });
        panelBody.addView(back);

        panelBody.addView(label("整段物品 NBT。必填 Name、Count；Slot 会自动设为 " + slot
                + "。清空文本框 = 删除这一格。", 11, hintText, false));

        final EditText json = new EditText(this);
        json.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        json.setTypeface(android.graphics.Typeface.MONOSPACE);
        json.setTextColor(darkText);
        json.setBackground(bg(fieldBg, 8, stroke));
        json.setPadding(dp(8), dp(8), dp(8), dp(8));
        json.setGravity(Gravity.TOP | Gravity.START);
        json.setHorizontallyScrolling(false);
        json.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        String init = keepText != null ? keepText
                : (old == null ? defaultItemJson(slot) : NbtJson.toJson(old));
        json.setText(init);
        json.setFocusable(true);
        json.setFocusableInTouchMode(true);
        json.setClickable(true);
        json.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { focusAndIme(json); }
        });
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        jlp.topMargin = dp(8);
        panelBody.addView(json, jlp);

        // 快速附魔
        panelBody.addView(label("快速附魔", 12, subText, true));
        final TextView pick = label("附魔：" + MainActivity.ENCH_LABELS[enchSel], 12, accent, false);
        pick.setClickable(true);
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseEnchant(w, slot, old, json.getText().toString()); }
        });
        panelBody.addView(pick);

        LinearLayout enchRow = new LinearLayout(this);
        enchRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText lvl = field("等级 1-32767", true, false);
        lvl.setText("1");
        enchRow.addView(lvl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = action("加附魔", accent);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int l = parseInt(lvl.getText().toString(), 0);
                if (l < 1 || l > 32767) { stateView("等级要在 1-32767。", true); return; }
                String out = patchEnchants(json.getText().toString(), old, MainActivity.ENCH_IDS[enchSel], l);
                if (out == null) { stateView("JSON 解析不了，先修好再加附魔。", true); return; }
                json.setText(out);
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        alp.leftMargin = dp(8);
        enchRow.addView(add, alp);
        LinearLayout.LayoutParams erlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        erlp.topMargin = dp(8);
        panelBody.addView(enchRow, erlp);

        TextView clear = action("清除全部附魔", 0xFFE5484D);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String out = patchEnchants(json.getText().toString(), old, -1, 0);
                if (out == null) { stateView("JSON 解析不了，先修好再清附魔。", true); return; }
                json.setText(out);
            }
        });
        panelBody.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView st = label("", 12, hintText, false);
        st.setLineSpacing(dp(3), 1f);

        TextView save = action("保存并写入存档（自动备份）", accent);
        save.setTextColor(Color.WHITE);
        save.setBackground(bg(accent, 10));
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String text = json.getText().toString().trim();
                Nbt.Tag parsed = null;
                if (text.length() > 0) {
                    try {
                        parsed = NbtJson.fromJsonAny(text, old);
                    } catch (Throwable t) {
                        st.setTextColor(0xFFE5484D);
                        st.setText("JSON 解析失败：" + t.getMessage());
                        return;
                    }
                    if (parsed.type != Nbt.TAG_COMPOUND) {
                        st.setTextColor(0xFFE5484D);
                        st.setText("这一格必须是对象（{...}）。");
                        return;
                    }
                    if (((Nbt.Compound) parsed.value).get("Name") == null) {
                        st.setTextColor(0xFFE5484D);
                        st.setText("缺少 Name（物品 ID）。");
                        return;
                    }
                }
                java.util.Map<Integer, Nbt.Tag> copy = new java.util.LinkedHashMap<>(inv);
                if (parsed == null) copy.remove(slot);
                else copy.put(slot, parsed);
                Nbt.ListTag lt = new Nbt.ListTag();
                lt.elemType = Nbt.TAG_COMPOUND;
                for (java.util.Map.Entry<Integer, Nbt.Tag> e : copy.entrySet()) {
                    int i = e.getKey();
                    Nbt.Tag t = e.getValue();
                    if (i < 0 || i >= 36) { lt.items.add(t); continue; }
                    if (t.type != Nbt.TAG_COMPOUND) continue;
                    Nbt.Compound c = (Nbt.Compound) t.value;
                    Nbt.Tag sn = c.get("Slot");
                    if (sn == null) c.put("Slot", new Nbt.Tag(Nbt.TAG_BYTE, "Slot", (byte) i));
                    else { sn.type = Nbt.TAG_BYTE; sn.value = (byte) i; }
                    lt.items.add(t);
                }
                Editor.Options opt = new Editor.Options();
                opt.purgeEntities = false;
                opt.clearDigests = false;
                opt.invSection = "Inventory";
                opt.invTag = new Nbt.Tag(Nbt.TAG_LIST, "Inventory", lt);
                hideKeyboard();
                runWriteInv(w, opt, st, slot, parsed, new Runnable() {
                    public void run() { showInventory(w); }
                });
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        panelBody.addView(save, slp);
        panelBody.addView(st, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void stateView(String text, boolean error) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void chooseEnchant(final WorldStore.World w, final int slot, final Nbt.Tag old, final String keep) {
        if (panelBody == null) return;
        setBodyTall(true);
        panelBody.removeAllViews();
        if (panelTitle != null) panelTitle.setText("选择附魔");
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label("点一个附魔，回到编辑页。", 11, hintText, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rf = label("刷新", 12, accent, false);
        rf.setClickable(true);
        rf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseEnchant(w, slot, old, keep); }
        });
        top.addView(rf);
        panelBody.addView(top);
        for (int i = 0; i < MainActivity.ENCH_LABELS.length; i++) {
            final int idx = i;
            LinearLayout r = new LinearLayout(this);
            r.setPadding(dp(10), dp(8), dp(10), dp(8));
            r.setBackground(bg(fieldBg, 8, stroke));
            r.setClickable(true);
            r.addView(label(MainActivity.ENCH_LABELS[i], 13, i == enchSel ? accent : darkText, i == enchSel));
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    enchSel = idx;
                    showItemEditor(w, slot, old, keep);
                }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(6);
            panelBody.addView(r, rlp);
        }
    }

    private String defaultItemJson(int slot) {
        return "{\n  \"Name\": \"minecraft:stone\",\n  \"Count\": 1,\n"
                + "  \"Damage\": 0,\n  \"Slot\": " + slot + "\n}";
    }

    /** 在物品 NBT JSON 里加/清附魔；id<0 清空。解析失败返回 null。 */
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
            Nbt.ListTag lt0 = new Nbt.ListTag();
            lt0.elemType = Nbt.TAG_COMPOUND;
            ench = new Nbt.Tag(Nbt.TAG_LIST, "ench", lt0);
            tc.put("ench", ench);
        }
        Nbt.ListTag lt = (Nbt.ListTag) ench.value;
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

    private EditText field(String hint, boolean number, boolean signed) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setTextColor(darkText);
        et.setHintTextColor(hintText);
        et.setBackground(bg(fieldBg, 8, stroke));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (number) {
            et.setInputType(signed
                    ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                    : InputType.TYPE_CLASS_NUMBER);
        }
        return et;
    }

    private void showNbtForm(final WorldStore.World w) {
        if (panelBody == null) return;
        panelBody.removeAllViews();
        if (panelTitle != null) panelTitle.setText("修改 NBT · " + w.name);

        TextView back = label("‹ 返回", 12, accent, false);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showWorldActions(w); }
        });
        panelBody.addView(back);

        panelBody.addView(label("玩家坐标（留空不改）", 11, subText, false));
        LinearLayout posRow = new LinearLayout(this);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText ex = field("X", true, true);
        final EditText ey = field("Y", true, true);
        final EditText ez = field("Z", true, true);
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams w3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        w2.leftMargin = dp(8);
        w3.leftMargin = dp(8);
        posRow.addView(ex, w1);
        posRow.addView(ey, w2);
        posRow.addView(ez, w3);
        panelBody.addView(posRow);

        panelBody.addView(label("手持物品（留空不改）", 11, subText, false));
        final EditText item = field("物品 ID，如 diamond_sword", false, false);
        panelBody.addView(item);
        LinearLayout cntRow = new LinearLayout(this);
        cntRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText count = field("数量 1-64", true, false);
        final EditText aux = field("附加值 0", true, false);
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c2.leftMargin = dp(8);
        cntRow.addView(count, c1);
        cntRow.addView(aux, c2);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp(8);
        panelBody.addView(cntRow, crlp);

        final TextView state = label("", 12, hintText, false);
        state.setLineSpacing(dp(3), 1f);

        TextView write = action("写入这个存档（自动备份）", accent);
        write.setTextColor(Color.WHITE);
        write.setBackground(bg(accent, 10));
        write.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Editor.Options opt = new Editor.Options();
                opt.purgeEntities = false;
                opt.clearDigests = false;
                String sx = ex.getText().toString().trim();
                String sy = ey.getText().toString().trim();
                String sz = ez.getText().toString().trim();
                boolean anyPos = sx.length() > 0 || sy.length() > 0 || sz.length() > 0;
                if (anyPos) {
                    if (sx.length() == 0 || sy.length() == 0 || sz.length() == 0) {
                        state.setText("坐标要三个都填，或全部留空不改。");
                        return;
                    }
                    try {
                        opt.posX = Double.parseDouble(sx);
                        opt.posY = Double.parseDouble(sy);
                        opt.posZ = Double.parseDouble(sz);
                    } catch (Throwable t) {
                        state.setText("坐标格式不对。");
                        return;
                    }
                }
                String id = item.getText().toString().trim();
                if (id.length() > 0) {
                    opt.heldItem = id;
                    opt.heldCount = Math.max(1, Math.min(64, parseInt(count.getText().toString(), 1)));
                    opt.heldAux = Math.max(0, parseInt(aux.getText().toString(), 0));
                }
                if (!anyPos && id.length() == 0) {
                    state.setText("没有要改的内容。");
                    return;
                }
                hideKeyboard();
                runWrite(w, opt, state);
            }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(10);
        panelBody.addView(write, wlp);
        panelBody.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void focusAndIme(final EditText et) {
        try {
            et.requestFocus();
            et.postDelayed(new Runnable() {
                public void run() {
                    try {
                        InputMethodManager imm =
                                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, 250);
        } catch (Throwable ignored) {
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && panel != null) imm.hideSoftInputFromWindow(panel.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private String nameOf(Nbt.Tag item) {
        try {
            Nbt.Tag n = ((Nbt.Compound) item.value).get("Name");
            return n == null ? null : String.valueOf(n.value);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写完后从存档里读回这一格，和期望值比对，把真实结果告诉用户。 */
    /** db/LOCK 被别的进程持有 = 游戏正在跑这个世界（LevelDB 会加 fcntl 排他锁）。 */
    private boolean worldLocked(File worldDir) {
        File lock = new File(new File(worldDir, "db"), "LOCK");
        if (!lock.isFile()) return false;
        java.io.RandomAccessFile raf = null;
        java.nio.channels.FileLock fl = null;
        try {
            raf = new java.io.RandomAccessFile(lock, "rw");
            fl = raf.getChannel().tryLock();
            return fl == null;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (fl != null) fl.release(); } catch (Throwable ignored) {}
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    private void log(String line) {
        AppLog.i("小窗", line);
    }

    private String nameWithCount(Nbt.Tag item) {
        try {
            Nbt.Compound c = (Nbt.Compound) item.value;
            Nbt.Tag nm = c.get("Name");
            Nbt.Tag cn = c.get("Count");
            return (nm == null ? "?" : String.valueOf(nm.value))
                    + " × " + (cn != null && cn.value instanceof Number ? ((Number) cn.value).intValue() : 1);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 读某个目录里的槽，和期望值比对；一致返回 null，不一致返回差异描述。 */
    private String checkSlot(File worldDir, int slot, Nbt.Tag intended) {
        try {
            Nbt.Tag back = Editor.readPlayerSectionTag(worldDir, "Inventory");
            Nbt.Tag now = null;
            if (back != null && back.type == Nbt.TAG_LIST) {
                for (Nbt.Tag it : ((Nbt.ListTag) back.value).items) {
                    if (it.type != Nbt.TAG_COMPOUND) continue;
                    Nbt.Tag sl = ((Nbt.Compound) it.value).get("Slot");
                    if (sl != null && sl.value instanceof Number
                            && ((Number) sl.value).intValue() == slot) { now = it; break; }
                }
            }
            String want = intended == null ? "（删除）" : nameWithCount(intended);
            String got = now == null ? "（空）" : nameWithCount(now);
            if (intended == null ? now == null : got.equals(want)) return null;
            return "期望 " + want + "，实际 " + got;
        } catch (Throwable t) {
            return "校验异常 " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
        }
    }

    /** 背包写入：Tag 直写失败时，自动退到 JSON 文本写入再试一次。 */
    private void runWriteInv(final WorldStore.World w, final Editor.Options opt,
                             final TextView state, final int slot, final Nbt.Tag intended,
                             final Runnable after) {
        state.setText("处理中，请稍候…");
        state.setTextColor(accent);
        final File out = new File(getCacheDir(), "flt_out_" + System.currentTimeMillis());
        final File work = new File(getCacheDir(), "flt_work_" + System.currentTimeMillis());
        new Thread(new Runnable() {
            public void run() {
                Throwable last = null;
                try {
                    if (worldLocked(w.dir)) {
                        throw new IllegalStateException("游戏正在使用这个存档（db 被锁住），现在写必被覆盖。请先退出该世界再写。");
                    }
                    Editor.processDir(w.dir, out, opt, work, new Editor.Progress() {
                        public void onProgress(int pct, final String msg) {
                            ui.post(new Runnable() {
                                public void run() {
                                    state.setTextColor(subText);
                                    state.setText("处理中 " + pct + "% · " + msg);
                                }
                            });
                        }
                    });
                    String inOut = checkSlot(out, slot, intended);
                    if (inOut != null) {
                        log("处理产物未改动 dir=" + w.dir + " slot=" + slot + " " + inOut);
                        throw new IllegalStateException("处理产物没有改动：" + inOut);
                    }
                    WorldStore.writeBack(w, out);
                    String inWorld = checkSlot(w.dir, slot, intended);
                    if (inWorld != null) {
                        log("写回后世界未变 dir=" + w.dir + " slot=" + slot + " " + inWorld);
                        throw new IllegalStateException("写回后存档仍未变：" + inWorld);
                    }
                    log("写入成功 dir=" + w.dir + " slot=" + slot);
                    ui.post(new Runnable() {
                        public void run() {
                            state.setTextColor(accent);
                            state.setText("已写入，正在确认存档没有被游戏回写…");
                        }
                    });
                    Thread.sleep(1500);
                    String late = checkSlot(w.dir, slot, intended);
                    if (late != null) {
                        log("写入后被回写 dir=" + w.dir + " slot=" + slot + " " + late);
                        ui.post(new Runnable() {
                            public void run() {
                                state.setTextColor(0xFFE5484D);
                                state.setText("写入被覆盖了：" + late
                                        + "\n游戏还在运行这个世界，退出该世界后再写才会生效。");
                                android.widget.Toast.makeText(FloatService.this,
                                        "写入被游戏覆盖：请先退出这个世界", android.widget.Toast.LENGTH_LONG).show();
                                if (after != null) after.run();
                            }
                        });
                        return;
                    }
                    log("写入成功 dir=" + w.dir + " slot=" + slot);
                    ui.post(new Runnable() {
                        public void run() {
                            state.setTextColor(accent);
                            state.setText("已写入并确认生效。原档已备份到 /sdcard/NM存档备份。");
                            android.widget.Toast.makeText(FloatService.this, "已写入存档", android.widget.Toast.LENGTH_SHORT).show();
                            if (after != null) after.run();
                        }
                    });
                    return;
                } catch (Throwable t) {
                    last = t;
                }
                // 回退 1：Tag 直写异常时，改成 JSON 文本写入
                if (opt.invTag != null && opt.invSection != null) {
                    try {
                        ui.post(new Runnable() {
                            public void run() { state.setText("Tag 直写失败，改用 JSON 写入重试…"); }
                        });
                        Editor.Options o2 = new Editor.Options();
                        o2.purgeEntities = opt.purgeEntities;
                        o2.clearDigests = opt.clearDigests;
                        o2.invSection = opt.invSection;
                        o2.invJson = NbtJson.toJson(opt.invTag);
                        Editor.deleteRecursivePublic(out);
                        Editor.deleteRecursivePublic(work);
                        Editor.processDir(w.dir, out, o2, work, null);
                        String inOut2 = checkSlot(out, slot, intended);
                        if (inOut2 != null) throw new IllegalStateException("JSON 通道产物仍未改动：" + inOut2);
                        WorldStore.writeBack(w, out);
                        String inWorld2 = checkSlot(w.dir, slot, intended);
                        if (inWorld2 != null) throw new IllegalStateException("JSON 通道写回后仍未变：" + inWorld2);
                        log("写入成功(JSON通道) dir=" + w.dir + " slot=" + slot);
                        ui.post(new Runnable() {
                            public void run() {
                                state.setTextColor(accent);
                                state.setText("已写入并确认生效（JSON 通道）。原档已备份到 /sdcard/NM存档备份。");
                                android.widget.Toast.makeText(FloatService.this, "已写入存档", android.widget.Toast.LENGTH_SHORT).show();
                                if (after != null) after.run();
                            }
                        });
                        return;
                    } catch (Throwable t2) {
                        last = t2;
                    }
                }
                final Throwable ft = last;
                ui.post(new Runnable() {
                    public void run() {
                        String err = ft == null ? "未知错误" : (ft.getClass().getSimpleName()
                                + (ft.getMessage() == null ? "（无详细信息）" : "：" + ft.getMessage()));
                        state.setTextColor(0xFFE5484D);
                        state.setText("失败：" + err);
                        android.widget.Toast.makeText(FloatService.this, "写入失败：" + err,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** 后台执行：处理到临时目录 → 写回原档（内部先备份、失败回滚）。 */
    private void runWrite(final WorldStore.World w, final Editor.Options opt, final TextView state) {
        runWrite(w, opt, state, null);
    }

    private void runWrite(final WorldStore.World w, final Editor.Options opt, final TextView state,
                          final Runnable after) {
        state.setText("处理中，请稍候…");
        state.setTextColor(accent);
        final File out = new File(getCacheDir(), "flt_out_" + System.currentTimeMillis());
        final File work = new File(getCacheDir(), "flt_work_" + System.currentTimeMillis());
        new Thread(new Runnable() {
            public void run() {
                try {
                    Editor.processDir(w.dir, out, opt, work, new Editor.Progress() {
                        public void onProgress(int pct, final String msg) {
                            ui.post(new Runnable() {
                                public void run() {
                                    state.setTextColor(subText);
                                    state.setText("处理中 " + pct + "% · " + msg);
                                }
                            });
                        }
                    });
                    ui.post(new Runnable() {
                        public void run() { state.setText("正在写回（先备份原档）…"); }
                    });
                    WorldStore.writeBack(w, out);
                    ui.post(new Runnable() {
                        public void run() {
                            state.setTextColor(accent);
                            state.setText("已写入。原档已备份到 /sdcard/NM存档备份。\n进游戏前确认游戏没有运行该世界。");
                            android.widget.Toast.makeText(FloatService.this, "已写入存档", android.widget.Toast.LENGTH_SHORT).show();
                            if (after != null) after.run();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        public void run() {
                            String err = t.getClass().getSimpleName()
                                    + (t.getMessage() == null ? "（无详细信息）" : "：" + t.getMessage());
                            state.setTextColor(0xFFE5484D);
                            state.setText("失败：" + err);
                            android.widget.Toast.makeText(FloatService.this, "写入失败：" + err,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    Editor.deleteRecursivePublic(out);
                    Editor.deleteRecursivePublic(work);
                }
            }
        }).start();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }
}

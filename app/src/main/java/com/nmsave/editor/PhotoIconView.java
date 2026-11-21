package com.nmsave.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 卡片右上角的「查看世界图标」：自绘细线图片图标（外框 + 小太阳 + 山线），
 * 替代原来那个彩色 emoji——emoji 自带配色，和全代码自绘的界面不搭。
 */
public class PhotoIconView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF frame = new RectF();
    private int tint = 0xFF8A94A3;

    public PhotoIconView(Context c) {
        super(c);
        paint.setStyle(Paint.Style.STROKE);
        setClickable(true);
    }

    /** 线条颜色跟随主题（浅色用 #9CA3AF，深色用 #838D9C）。 */
    public void setTint(int color) {
        tint = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());

        float stroke = Math.max(1.2f, w / 18f);
        paint.setColor(tint);
        paint.setStrokeWidth(stroke);

        // 外框
        float inset = stroke;
        frame.set(inset, inset, w - inset, h - inset);
        canvas.drawRoundRect(frame, w / 5f, w / 5f, paint);

        // 太阳（小圆点，实心）
        Paint dot = new Paint(paint);
        dot.setStyle(Paint.Style.FILL);
        canvas.drawCircle(w * 0.30f, h * 0.32f, w * 0.075f, dot);

        // 山线
        path.reset();
        path.moveTo(w * 0.12f, h * 0.74f);
        path.lineTo(w * 0.42f, h * 0.44f);
        path.lineTo(w * 0.58f, h * 0.60f);
        path.lineTo(w * 0.70f, h * 0.48f);
        path.lineTo(w * 0.88f, h * 0.74f);
        canvas.drawPath(path, paint);

        canvas.restore();
    }
}

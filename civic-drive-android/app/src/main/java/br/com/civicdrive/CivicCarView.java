package br.com.civicdrive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Silhueta original desenhada em código, sem imagens ou logos da parceria anterior. */
public final class CivicCarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path window = new Path();

    public CivicCarView(Context context) { super(context); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        c.save();
        c.translate(w * .07f, h * .04f);
        c.scale(w * .86f / 500f, h * .86f / 190f);

        // Luz ambiente
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 140, 500, 140,
                new int[]{Color.TRANSPARENT, 0x2244C8F0, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(25, 160, 490, 165), 2, 2, paint);
        paint.setShader(null);

        // Silhueta do veículo: traçado próprio, não extraído do APK original.
        outline.reset();
        outline.moveTo(22, 132);
        outline.cubicTo(26, 119, 48, 115, 82, 107);
        outline.lineTo(130, 101);
        outline.cubicTo(166, 63, 198, 49, 260, 45);
        outline.cubicTo(302, 42, 347, 52, 376, 80);
        outline.lineTo(407, 103);
        outline.lineTo(460, 114);
        outline.cubicTo(478, 121, 482, 132, 482, 145);
        outline.lineTo(467, 152);
        outline.lineTo(430, 152);
        outline.cubicTo(428, 125, 393, 115, 376, 151);
        outline.lineTo(137, 151);
        outline.cubicTo(132, 124, 102, 112, 83, 151);
        outline.lineTo(35, 151);
        outline.close();
        paint.setColor(0xFF27D9F4);
        paint.setStrokeWidth(3.5f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        c.drawPath(outline, paint);

        window.reset();
        window.moveTo(153, 101);
        window.cubicTo(181, 72, 203, 60, 260, 58);
        window.lineTo(266, 99);
        window.close();
        c.drawPath(window, paint);
        window.reset();
        window.moveTo(277, 58);
        window.cubicTo(326, 58, 354, 74, 385, 105);
        window.lineTo(279, 100);
        window.close();
        c.drawPath(window, paint);

        paint.setStrokeWidth(4f);
        c.drawCircle(109, 152, 27, paint);
        c.drawCircle(404, 152, 27, paint);
        paint.setStrokeWidth(2f);
        c.drawCircle(109, 152, 16, paint);
        c.drawCircle(404, 152, 16, paint);
        c.drawLine(35, 133, 65, 130, paint);
        c.drawLine(431, 125, 463, 132, paint);
        c.restore();
    }
}

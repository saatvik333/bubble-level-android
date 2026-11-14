package android.graphics;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    public enum Align { LEFT, CENTER, RIGHT }
    public static class FontMetrics { public float top, ascent, descent, bottom, leading; }
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    public Style style = Style.FILL; public Cap cap = Cap.BUTT; public Join join = Join.MITER;
    public Align align = Align.LEFT; public float strokeWidth; public int color = 0xFF000000;
    public Typeface tf; public float textSize = 12f;
    public Paint() {}
    public Paint(int flags) {}
    public void setStyle(Style s) { style = s; }
    public void setStrokeCap(Cap c) { cap = c; }
    public void setStrokeJoin(Join j) { join = j; }
    public void setTextAlign(Align a) { align = a; }
    public void setStrokeWidth(float w) { strokeWidth = w; }
    public float getStrokeWidth() { return strokeWidth; }
    public void setColor(int c) { color = c; }
    public void setTypeface(Typeface t) { tf = t; }
    public Typeface getTypeface() { return tf; }
    public void setTextSize(float s) { textSize = s; }
    public float getTextSize() { return textSize; }
    /** Kerning and ligatures on, as Android's default font features; derived once per face and size. */
    public Font font() {
        if (cached == null || cachedTf != tf || cachedSize != textSize) {
            Font base = tf != null ? tf.font : new Font("SansSerif", Font.PLAIN, 1);
            Map<TextAttribute, Object> attrs = new HashMap<>();
            attrs.put(TextAttribute.SIZE, textSize);
            attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
            attrs.put(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
            cached = base.deriveFont(attrs);
            cachedTf = tf;
            cachedSize = textSize;
        }
        return cached;
    }
    private Font cached; private Typeface cachedTf; private float cachedSize;
    public float measureText(String s) { return s.isEmpty() ? 0f : (float) font().getStringBounds(s, FRC).getWidth(); }
    public float measureText(String s, int start, int end) { return measureText(s.substring(start, end)); }
    public float getFontMetrics(FontMetrics fm) {
        LineMetrics lm = font().getLineMetrics("Hg", FRC);
        fm.ascent = -lm.getAscent(); fm.descent = lm.getDescent(); fm.top = fm.ascent; fm.bottom = fm.descent; fm.leading = lm.getLeading();
        return fm.descent - fm.ascent;
    }
}

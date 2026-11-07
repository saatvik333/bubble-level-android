package android.graphics;
import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayDeque;
public class Canvas {
    final Graphics2D g; final int w, h; final ArrayDeque<Object[]> stack = new ArrayDeque<>();
    public Canvas(Graphics2D g, int w, int h) {
        this.g = g; this.w = w; this.h = h;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }
    public void drawColor(int c) { AffineTransform t = g.getTransform(); g.setTransform(new AffineTransform()); g.setColor(new Color(c, true)); g.fillRect(0, 0, w, h); g.setTransform(t); }
    public int save() { stack.push(new Object[]{g.getTransform(), g.getClip()}); return stack.size(); }
    public void restore() { Object[] st = stack.pop(); g.setTransform((AffineTransform) st[0]); g.setClip((Shape) st[1]); }
    public boolean clipRect(float l, float t, float r, float b) { g.clip(new Rectangle2D.Float(l, t, r - l, b - t)); return true; }
    public void scale(float sx, float sy, float px, float py) { g.translate(px, py); g.scale(sx, sy); g.translate(-px, -py); }
    public void translate(float x, float y) { g.translate(x, y); }
    public void rotate(float deg, float px, float py) { g.rotate(Math.toRadians(deg), px, py); }
    private void paint(Shape s, Paint p) {
        g.setColor(new Color(p.color, true));
        if (p.style == Paint.Style.FILL) { g.fill(s); return; }
        int cap = p.cap == Paint.Cap.ROUND ? BasicStroke.CAP_ROUND : p.cap == Paint.Cap.SQUARE ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
        int join = p.join == Paint.Join.ROUND ? BasicStroke.JOIN_ROUND : p.join == Paint.Join.BEVEL ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER;
        g.setStroke(new BasicStroke(Math.max(1f, p.strokeWidth), cap, join));
        g.draw(s);
    }
    public void drawCircle(float cx, float cy, float r, Paint p) { paint(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r), p); }
    public void drawLine(float x1, float y1, float x2, float y2, Paint p) { Paint.Style s = p.style; p.style = Paint.Style.STROKE; paint(new Line2D.Float(x1, y1, x2, y2), p); p.style = s; }
    public void drawRoundRect(RectF r, float rx, float ry, Paint p) { paint(new RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), 2 * rx, 2 * ry), p); }
    public void drawArc(RectF r, float start, float sweep, boolean center, Paint p) { paint(new Arc2D.Float(r.left, r.top, r.width(), r.height(), -start, -sweep, center ? Arc2D.PIE : Arc2D.OPEN), p); }
    public void drawPath(Path path, Paint p) { paint(path.p, p); }
    public void drawText(String s, float x, float y, Paint p) {
        g.setColor(new Color(p.color, true));
        g.setFont(p.font());
        float wdt = p.measureText(s);
        if (p.align == Paint.Align.CENTER) x -= wdt / 2f; else if (p.align == Paint.Align.RIGHT) x -= wdt;
        g.drawString(s, x, y);
    }
}

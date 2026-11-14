package android.graphics;
public class Path {
    public java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
    public void reset() { p = new java.awt.geom.Path2D.Float(); }
    public void moveTo(float x, float y) { p.moveTo(x, y); }
    public void lineTo(float x, float y) { p.lineTo(x, y); }
    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) { p.curveTo(x1, y1, x2, y2, x3, y3); }
    public void close() { p.closePath(); }
}

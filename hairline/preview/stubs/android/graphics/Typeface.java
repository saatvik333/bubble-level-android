package android.graphics;
public class Typeface {
    public final java.awt.Font font;
    public Typeface(java.awt.Font f) { font = f; }
    public static Typeface fromFile(String path) {
        try { return new Typeface(java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(path))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}

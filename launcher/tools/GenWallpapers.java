import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Standalone generator: writes 12 placeholder 1920x1080 JPEG wallpapers.
 *
 * Compile:  javac tools/GenWallpapers.java
 * Run:      java -cp <classes-dir> GenWallpapers <outdir>
 *
 * The Gradle task `genWallpapers` in app/build.gradle invokes this before
 * resource merge so the app always has valid JPEGs in res/raw/.
 */
public class GenWallpapers {

    private static final int W = 1920;
    private static final int H = 1080;

    // Dark gradient pairs: blues, purples and teals, then (user, 30 Oct) green, red, blue, amber,
    // magenta and emerald. Existing files are kept, so adding pairs only writes the new ones.
    private static final Color[][] PALETTE = new Color[][] {
            { new Color(0x0A1A2F), new Color(0x1E3A5F) },  // deep night blue
            { new Color(0x2A1A3A), new Color(0x4B1E5F) },  // dark purple
            { new Color(0x062A2E), new Color(0x0D4F55) },  // teal depth
            { new Color(0x1A0F2E), new Color(0x3B1E6F) },  // indigo
            { new Color(0x0C1E26), new Color(0x1F4A5A) },  // slate teal
            { new Color(0x160B30), new Color(0x2D1465) },  // violet dusk
            { new Color(0x0B2A16), new Color(0x1F5A33) },  // forest green
            { new Color(0x2E0B12), new Color(0x5F1E28) },  // wine red
            { new Color(0x081F3F), new Color(0x1B4F8F) },  // ocean blue
            { new Color(0x2E1A08), new Color(0x6A3A12) },  // amber
            { new Color(0x2E0A26), new Color(0x6A1E5A) },  // magenta
            { new Color(0x07291F), new Color(0x14604A) },  // emerald
    };

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java GenWallpapers <outDir>");
            System.exit(2);
        }
        File outDir = new File(args[0]);
        if (!outDir.exists()) outDir.mkdirs();

        for (int i = 0; i < PALETTE.length; i++) {
            File out = new File(outDir, "wallpaper_" + (i + 1) + ".jpg");
            if (out.exists()) {
                System.out.println("exists: " + out.getName());
                continue;
            }
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            GradientPaint gp = new GradientPaint(0, 0, PALETTE[i][0], W, H, PALETTE[i][1]);
            g.setPaint(gp);
            g.fillRect(0, 0, W, H);
            // Soft vignette
            g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 0), 0, H,
                    new Color(0, 0, 0, 90)));
            g.fillRect(0, 0, W, H);
            g.dispose();

            ImageIO.write(img, "jpg", out);
            System.out.println("wrote: " + out.getAbsolutePath());
        }
    }
}

package com.novelforge.core.export;

import com.novelforge.core.models.Book;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * CoverGenerator — zero-dependency book cover synthesis (pure JDK).
 *
 * Renders a 600x900 PNG cover with an ink-wash inspired gradient, title
 * (vertical CJK-friendly wrapping), author name and a genre badge.
 * Uses the first available system CJK font (Microsoft YaHei / SimSun / Noto).
 */
public final class CoverGenerator {

    private static final int W = 600;
    private static final int H = 900;

    private CoverGenerator() {}

    /** Palettes inspired by the ink-wash (墨韵) Studio theme. */
    private static final Color[][] PALETTES = {
            {new Color(0x1a1a2e), new Color(0x4a2f44), new Color(0xd4a24e)},   // 墨夜鎏金
            {new Color(0x12232e), new Color(0x2e4a4a), new Color(0x9fd8cb)},   // 青瓷
            {new Color(0x2b1216), new Color(0x6b2737), new Color(0xe8c06a)},   // 朱砂
            {new Color(0x141e30), new Color(0x243b55), new Color(0xa8d0e6)},   // 夜航
            {new Color(0x1f1c2c), new Color(0x5f5b6b), new Color(0xc3aed6)},   // 紫烟
    };

    public static Path generate(Book book, Path outputPath, Integer paletteIndex) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color[] palette = PALETTES[new Random(book.getTitle() == null ? 42 : book.getTitle().hashCode())
                    .nextInt(PALETTES.length)];
            if (paletteIndex != null && paletteIndex >= 0 && paletteIndex < PALETTES.length) {
                palette = PALETTES[paletteIndex];
            }
            Color c0 = palette[0], c1 = palette[1], accent = palette[2];

            // Background: vertical gradient + soft vignette bands (ink-wash feel)
            java.awt.GradientPaint bg = new java.awt.GradientPaint(0, 0, c0, W, H, c1);
            g.setPaint(bg);
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(c0.getRed(), c0.getGreen(), c0.getBlue(), 90));
            for (int i = 0; i < 5; i++) {
                g.fillRoundRect(-80 + i * 30, 120 + i * 150, W + 160, 60, 60, 60);
            }

            // Border frame
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 200));
            g.setStroke(new java.awt.BasicStroke(3f));
            g.drawRoundRect(24, 24, W - 48, H - 48, 18, 18);

            String title = book.getTitle() == null ? "无题" : book.getTitle();
            String author = book.getAuthor() == null ? "" : book.getAuthor();
            String genre = book.getGenre() == null ? "" : book.getGenre();

            Font titleFont = cjkFont(Font.BOLD, title.length() > 6 ? 72 : 88);
            Font subFont = cjkFont(Font.PLAIN, 30);
            Font smallFont = cjkFont(Font.PLAIN, 22);

            // Title block — wrap CJK chars at most 5 per line
            g.setColor(Color.WHITE);
            g.setFont(titleFont);
            int y = 200;
            for (String line : wrapCjk(title, 5)) {
                int tw = g.getFontMetrics().stringWidth(line);
                g.drawString(line, (W - tw) / 2, y);
                y += g.getFontMetrics().getHeight() + 6;
            }

            // Accent seal under title
            g.setColor(accent);
            g.fillRect(W / 2 - 40, y + 4, 80, 6);

            // Genre badge
            if (!genre.isEmpty()) {
                g.setFont(subFont);
                int gw = g.getFontMetrics().stringWidth(genre) + 48;
                int gx = (W - gw) / 2;
                g.setColor(new Color(0, 0, 0, 110));
                g.fillRoundRect(gx, 560, gw, 52, 26, 26);
                g.setColor(accent);
                g.drawRoundRect(gx, 560, gw, 52, 26, 26);
                g.setColor(Color.WHITE);
                g.drawString(genre, gx + 24, 597);
            }

            // Author
            if (!author.isEmpty()) {
                g.setFont(subFont);
                g.setColor(new Color(255, 255, 255, 220));
                int aw = g.getFontMetrics().stringWidth(author);
                g.drawString(author, (W - aw) / 2, 780);
            }

            // Series label
            g.setFont(smallFont);
            g.setColor(new Color(255, 255, 255, 130));
            String label = "NovelForge · 墨阁";
            int lw = g.getFontMetrics().stringWidth(label);
            g.drawString(label, (W - lw) / 2, 845);

            ImageIO.write(img, "png", new File(outputPath.toString()));
            return outputPath;
        } finally {
            g.dispose();
        }
    }

    private static String[] wrapCjk(String text, int maxPerLine) {
        int n = (int) Math.ceil(text.length() / (double) maxPerLine);
        String[] lines = new String[n];
        for (int i = 0; i < n; i++) {
            int end = Math.min((i + 1) * maxPerLine, text.length());
            lines[i] = text.substring(i * maxPerLine, end);
        }
        return lines;
    }

    /** Pick the first available CJK-capable system font, with logical fallback. */
    private static Font cjkFont(int style, int size) {
        String[] preferred = {"Microsoft YaHei", "微软雅黑", "SimHei", "黑体", "Noto Sans CJK SC", "Source Han Sans SC", "SimSun"};
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        for (String p : preferred) {
            for (String a : available) {
                if (a.equalsIgnoreCase(p)) {
                    return new Font(a, style, size);
                }
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}

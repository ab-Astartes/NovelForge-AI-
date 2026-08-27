package com.novelforge.core.export;

import com.novelforge.core.models.Book;
import com.novelforge.core.models.Chapter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PdfWriter — dependency-free PDF generator with embedded CJK font support.
 *
 * Why hand-rolled: pulling in iText/PDFBox would add heavy dependencies; this
 * writer emits a valid multi-page PDF using a discovered system TrueType CJK
 * font embedded as a CIDFontType2 (Identity-H). If no CJK font is found on the
 * host, it throws a clear, actionable error so the caller can fall back to
 * EPUB/DOCX (which both render Chinese fine).
 */
public final class PdfWriter {

    private static final int PAGE_W = 595;   // A4
    private static final int PAGE_H = 842;
    private static final int MARGIN_X = 50;
    private static final int MARGIN_TOP = 56;
    private static final int LINE_H = 18;
    private static final int FONT_SIZE = 12;
    private static final int CHARS_PER_LINE = 36;

    private PdfWriter() {}

    public static void write(Book book, Path outputPath) throws Exception {
        byte[] font = findCjkFont();
        Map<Integer, Integer> cmap = parseCmap(font);

        List<String> lines = new ArrayList<>();
        lines.add(book.getTitle() != null ? book.getTitle() : "Untitled");
        lines.add("作者：" + (book.getAuthor() != null ? book.getAuthor() : "")
                + "    题材：" + (book.getGenre() != null ? book.getGenre() : ""));
        for (Chapter ch : book.getChapters()) {
            String text = ch.getFinalText() != null && !ch.getFinalText().isEmpty()
                    ? ch.getFinalText() : ch.getDraftText();
            if (text == null || text.isEmpty()) continue;
            lines.add("第" + ch.getNumber() + "章 " + (ch.getTitle() != null ? ch.getTitle() : ""));
            for (String para : text.split("\n{2,}")) {
                String p = para.replace("\n", " ").trim();
                if (!p.isEmpty()) lines.addAll(wrap(p, CHARS_PER_LINE));
            }
        }

        int perPage = (PAGE_H - 2 * MARGIN_TOP) / LINE_H;
        int pageCount = Math.max(1, (int) Math.ceil((double) lines.size() / perPage));

        // Pre-render each page's content stream
        List<byte[]> pageContents = new ArrayList<>();
        for (int p = 0; p < pageCount; p++) {
            int from = p * perPage;
            int to = Math.min(lines.size(), from + perPage);
            StringBuilder cs = new StringBuilder();
            int y = PAGE_H - MARGIN_TOP;
            for (int i = from; i < to; i++) {
                cs.append("BT /F1 ").append(FONT_SIZE).append(" Tf 1 0 0 1 ")
                  .append(MARGIN_X).append(" ").append(y).append(" Tm ")
                  .append(toHexCids(lines.get(i), cmap)).append(" Tj ET\n");
                y -= LINE_H;
            }
            pageContents.add(cs.toString().getBytes(StandardCharsets.US_ASCII));
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int firstPageObj = 7;
        int totalObjects = 6 + pageCount * 2;

        offsets.add(writeFull(body, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"));

        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pageCount; p++) kids.append(" ").append(firstPageObj + p * 2).append(" 0 R");
        offsets.add(writeFull(body, "2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount
                + " /MediaBox [0 0 " + PAGE_W + " " + PAGE_H + "] >>\nendobj\n"));

        offsets.add(writeFull(body,
                "3 0 obj\n<< /Type /Font /Subtype /Type0 /BaseFont /NFCJK /Encoding /Identity-H /DescendantFonts [4 0 R] >>\nendobj\n"));
        offsets.add(writeFull(body,
                "4 0 obj\n<< /Type /Font /Subtype /CIDFontType2 /BaseFont /NFCJK "
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                + "/FontDescriptor 5 0 R /DW 1000 >>\nendobj\n"));
        offsets.add(writeFull(body,
                "5 0 obj\n<< /Type /FontDescriptor /FontName /NFCJK /Flags 4 "
                + "/FontBBox [-200 -200 1000 1000] /ItalicAngle 0 /Ascent 800 /Descent -200 "
                + "/CapHeight 800 /StemV 80 /FontFile2 6 0 R >>\nendobj\n"));

        // 6: FontFile2 stream
        {
            String head = "6 0 obj\n<< /Length " + font.length + " /Length1 " + font.length + " >>\nstream\n";
            int pos = body.size();
            body.write(head.getBytes(StandardCharsets.US_ASCII));
            body.write(font);
            body.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            offsets.add(pos);
        }

        for (int p = 0; p < pageCount; p++) {
            int pageObj = firstPageObj + p * 2;
            int contentObj = pageObj + 1;
            offsets.add(writeFull(body, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                    + PAGE_W + " " + PAGE_H + "] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                    + contentObj + " 0 R >>\nendobj\n"));
            byte[] content = pageContents.get(p);
            String chead = contentObj + " 0 obj\n<< /Length " + content.length + " >>\nstream\n";
            int pos = body.size();
            body.write(chead.getBytes(StandardCharsets.US_ASCII));
            body.write(content);
            body.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            offsets.add(pos);
        }

        int xrefPos = body.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(totalObjects + 1).append("\n");
        xref.append("0000000000 65535 f \n");
        for (int off : offsets) xref.append(String.format("%010d 00000 n \n", off));
        body.write(xref.toString().getBytes(StandardCharsets.US_ASCII));

        StringBuilder trailer = new StringBuilder();
        trailer.append("trailer\n<< /Size ").append(totalObjects + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xrefPos).append("\n%%EOF\n");
        body.write(trailer.toString().getBytes(StandardCharsets.US_ASCII));

        Files.write(outputPath, body.toByteArray());
    }

    // ---- helpers ----

    private static int writeFull(ByteArrayOutputStream out, String full) {
        int pos = out.size();
        out.write(full.getBytes(StandardCharsets.US_ASCII), 0, full.length());
        return pos;
    }

    private static String toHexCids(String text, Map<Integer, Integer> cmap) {
        StringBuilder sb = new StringBuilder("<");
        for (int i = 0; i < text.length(); i++) {
            int cp = text.codePointAt(i);
            if (cp > 0xFFFF) i++; // skip low surrogate of a supplementary pair
            int gid = cmap.getOrDefault(cp, 0);
            sb.append(String.format("%04X", gid & 0xFFFF));
        }
        sb.append(">");
        return sb.toString();
    }

    private static List<String> wrap(String text, int max) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + max, text.length());
            out.add(text.substring(i, end));
            i = end;
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    // ---- CJK font discovery ----

    private static byte[] findCjkFont() throws Exception {
        String[] candidates = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/simkai.ttf",
            "C:/Windows/Fonts/STKAITI.TTF",
            "C:/Windows/Fonts/msjh.ttc",
            "/System/Library/Fonts/Supplemental/Songti.ttc",
            "/System/Library/Fonts/STHeiti Light.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
        };
        for (String c : candidates) {
            try {
                Path p = Path.of(c);
                if (!Files.exists(p)) continue;
                byte[] fb = Files.readAllBytes(p);
                if (isTrueType(fb) || isTtc(fb)) return fb;
            } catch (Exception ignored) {}
        }
        throw new UnsupportedOperationException(
            "未找到系统 CJK 字体，无法生成中文 PDF。请改用 EPUB 或 DOCX 导出（二者均原生支持中文）。" +
            "如需 PDF，请将一个 .ttf/.ttc 中文字体放到可访问路径，或安装中文字体后重试。");
    }

    private static boolean isTtc(byte[] b) { return b.length > 4 && b[0] == 't' && b[1] == 't' && b[2] == 'c' && b[3] == 'f'; }
    private static boolean isTrueType(byte[] b) { return b.length > 4 && b[0] == 0 && b[1] == 1 && b[2] == 0 && b[3] == 0; }

    // ---- cmap parsing ----

    private static Map<Integer, Integer> parseCmap(byte[] fb) throws Exception {
        int fo = 0;
        if (isTtc(fb)) {
            long num = u32(fb, 8);
            if (num > 0) fo = (int) u32(fb, 12);
        }
        int numTables = u16(fb, fo + 4);
        int dir = fo + 12;
        int cmapOff = -1;
        for (int i = 0; i < numTables; i++) {
            int t = dir + i * 16;
            String tag = new String(fb, t, 4, StandardCharsets.US_ASCII);
            if ("cmap".equals(tag)) { cmapOff = (int) u32(fb, t + 8); break; }
        }
        if (cmapOff < 0) throw new UnsupportedOperationException("字体缺少 cmap 表");
        cmapOff += fo;
        int numSub = u16(fb, cmapOff + 2);
        int best = -1;
        for (int i = 0; i < numSub; i++) {
            int s = cmapOff + 4 + i * 8;
            int pid = u16(fb, s), eid = u16(fb, s + 2);
            int off = (int) u32(fb, s + 4);
            if (pid == 3 && (eid == 1 || eid == 10)) { best = cmapOff + off; break; }
            if (pid == 0 && best < 0) { best = cmapOff + off; }
        }
        if (best < 0) throw new UnsupportedOperationException("字体缺少可用 cmap 子表");
        Map<Integer, Integer> map = new LinkedHashMap<>();
        int fmt = u16(fb, best);
        if (fmt == 4) parseFmt4(fb, best, map);
        else if (fmt == 12) parseFmt12(fb, best, map);
        else throw new UnsupportedOperationException("不支持的 cmap 格式 " + fmt);
        return map;
    }

    private static void parseFmt4(byte[] b, int o, Map<Integer, Integer> map) {
        int segCount = u16(b, o + 6) / 2;
        int endOff = o + 14;
        int startOff = endOff + 2 + segCount * 2;
        int deltaOff = startOff + segCount * 2;
        int rangeOffOff = deltaOff + segCount * 2;
        int gidArrOff = rangeOffOff + segCount * 2;
        for (int i = 0; i < segCount; i++) {
            int end = u16(b, endOff + i * 2);
            int start = u16(b, startOff + i * 2);
            int delta = (short) u16(b, deltaOff + i * 2);
            int rangeOff = u16(b, rangeOffOff + i * 2);
            if (start == 0xFFFF) break;
            for (int c = start; c <= end; c++) {
                int gid;
                if (rangeOff == 0) gid = (c + delta) & 0xFFFF;
                else gid = u16(b, gidArrOff + rangeOff + (c - start) * 2);
                map.put(c, gid);
            }
        }
    }

    private static void parseFmt12(byte[] b, int o, Map<Integer, Integer> map) {
        int ngroups = (int) u32(b, o + 12);
        int p = o + 16;
        for (int g = 0; g < ngroups; g++) {
            long startChar = u32(b, p), endChar = u32(b, p + 4);
            long startGid = u32(b, p + 8);
            for (long c = startChar; c <= endChar; c++) map.put((int) c, (int) (startGid + (c - startChar)));
            p += 12;
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((long) (b[off + 1] & 0xFF) << 16) |
               ((long) (b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}

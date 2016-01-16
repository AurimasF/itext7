package com.itextpdf.core.font;

import com.itextpdf.basics.PdfException;
import com.itextpdf.basics.font.FontEncoding;
import com.itextpdf.basics.font.FontNames;
import com.itextpdf.basics.font.TrueTypeFont;
import com.itextpdf.basics.font.cmap.CMapToUnicode;
import com.itextpdf.basics.font.otf.Glyph;
import com.itextpdf.basics.geom.Rectangle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import com.itextpdf.core.pdf.PdfArray;
import com.itextpdf.core.pdf.PdfDictionary;
import com.itextpdf.core.pdf.PdfDocument;
import com.itextpdf.core.pdf.PdfName;
import com.itextpdf.core.pdf.PdfNumber;
import com.itextpdf.core.pdf.PdfStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Note. For TrueType FontNames.getStyle() is the same to Subfamily(). So, we shouldn't add style to /BaseFont.
 */
public class PdfTrueTypeFont extends PdfSimpleFont<TrueTypeFont> {

    public PdfTrueTypeFont(PdfDocument pdfDocument, TrueTypeFont ttf, String encoding, boolean embedded) {
        super(pdfDocument);
        setFontProgram(ttf);
        this.embedded = embedded;
        FontNames fontNames = ttf.getFontNames();
        if (embedded && !fontNames.allowEmbedding()) {
            throw new PdfException("1.cannot.be.embedded.due.to.licensing.restrictions")
                    .setMessageParams(fontNames.getFontName());
        }
        if ((encoding == null || encoding.length() == 0) && ttf.isFontSpecific()) {
            encoding = FontEncoding.FontSpecific;
        }
        if (encoding != null && FontEncoding.FontSpecific.toLowerCase().equals(encoding.toLowerCase())) {
            fontEncoding = FontEncoding.createFontSpecificEncoding();
        } else {
            fontEncoding = FontEncoding.createFontEncoding(encoding);
        }
    }

    public PdfTrueTypeFont(PdfDocument pdfDocument, TrueTypeFont ttf, String encoding) {
        this(pdfDocument, ttf, encoding, false);
    }

    public PdfTrueTypeFont(PdfDocument pdfDocument, TrueTypeFont ttf) {
        this(pdfDocument, ttf, null, false);
    }

    public PdfTrueTypeFont(PdfDictionary fontDictionary) {
        super(fontDictionary);
        checkFontDictionary(fontDictionary, PdfName.TrueType);

        CMapToUnicode toUni = DocFontUtils.processToUnicode(fontDictionary);
        fontEncoding = DocFontEncoding.createDocFontEncoding(fontDictionary.get(PdfName.Encoding), toUni);
        fontProgram = DocTrueTypeFont.createSimpleFontProgram(fontDictionary, fontEncoding);
        embedded = ((DocTrueTypeFont) fontProgram).getFontFile() != null;
        subset = false;
    }

    public Glyph getGlyph(int ch) {
        if (fontEncoding.canEncode(ch)) {
            Glyph glyph = getFontProgram().getGlyph(fontEncoding.getUnicodeDifference(ch));
            //TODO TrueType what if font is specific?
            if (glyph == null && (glyph = notdefGlyphs.get(ch)) == null) {
                Glyph notdef = getFontProgram().getGlyphByCode(0);
                if (notdef != null) {
                    glyph = new Glyph(getFontProgram().getGlyphByCode(0), ch);
                    notdefGlyphs.put(ch, glyph);
                }
            }
            return glyph;
        }
        return null;
    }

    @Override
    public void flush() {
        PdfName subtype;
        String fontName;
        if (fontProgram instanceof DocTrueTypeFont) {
            subtype = ((DocTrueTypeFont) fontProgram).getSubtype();
            fontName = fontProgram.getFontNames().getFontName();
        } else if (fontProgram.isCff()) {
            subtype = PdfName.Type1;
            fontName = fontProgram.getFontNames().getFontName();
        } else {
            subtype = PdfName.TrueType;
            fontName = subset
                    ? createSubsetPrefix() + fontProgram.getFontNames().getFontName()
                    : fontProgram.getFontNames().getFontName();
        }
        flushFontData(fontName, subtype);
    }

    protected void addRangeUni(HashSet<Integer> longTag) {
        if (!subset && (subsetRanges != null || getFontProgram().getDirectoryOffset() > 0)) {
            int[] rg = subsetRanges == null && getFontProgram().getDirectoryOffset() > 0
                    ? new int[]{0, 0xffff} : compactRanges(subsetRanges);
            HashMap<Integer, int[]> usemap = getFontProgram().getActiveCmap();
            assert usemap != null;
            for (Map.Entry<Integer, int[]> e : usemap.entrySet()) {
                int[] v = e.getValue();
                Integer gi = v[0];
                if (longTag.contains(gi)) {
                    continue;
                }
                int c = e.getKey();
                boolean skip = true;
                for (int k = 0; k < rg.length; k += 2) {
                    if (c >= rg[k] && c <= rg[k + 1]) {
                        skip = false;
                        break;
                    }
                }
                if (!skip) {
                    longTag.add(gi);
                }
            }
        }
    }

    protected void addFontStream(PdfDictionary fontDescriptor) {
        if (embedded) {
            PdfName fontFileName;
            PdfStream fontStream;
            if (fontProgram instanceof DocTrueTypeFont) {
                fontFileName = ((DocTrueTypeFont) fontProgram).getFontFileName();
                fontStream = ((DocTrueTypeFont) fontProgram).getFontFile();
            } else if (fontProgram.isCff()) {
                fontFileName = PdfName.FontFile3;
                try {
                    byte[] fontStreamBytes = getFontProgram().getFontStreamBytes();
                    fontStream = getPdfFontStream(fontStreamBytes, new int[]{fontStreamBytes.length});
                    fontStream.put(PdfName.Subtype, new PdfName("Type1C"));
                } catch (PdfException e) {
                    Logger logger = LoggerFactory.getLogger(PdfTrueTypeFont.class);
                    logger.error(e.getMessage());
                    fontStream = null;
                }
            } else {
                fontFileName = PdfName.FontFile2;
                HashSet<Integer> glyphs = new HashSet<>();
                for (int k = 0; k < shortTag.length; k++) {
                    if (shortTag[k] != 0) {
                        Integer uni = fontEncoding.getUnicode(k);
                        Glyph glyph = uni != null ? fontProgram.getGlyph(uni) : fontProgram.getGlyphByCode(k);
                        if (glyph != null) {
                            glyphs.add(glyph.getCode());
                        }
                    }
                }
                addRangeUni(glyphs);
                try {
                    byte[] fontStreamBytes;
                    if (subset || getFontProgram().getDirectoryOffset() != 0 || subsetRanges != null) {
                        //clone glyphs due to possible cache issue
                        fontStreamBytes = getFontProgram().getSubset(new HashSet<>(glyphs), subset);
                    } else {
                        fontStreamBytes = getFontProgram().getFontStreamBytes();
                    }
                    fontStream = getPdfFontStream(fontStreamBytes, new int[]{fontStreamBytes.length});
                } catch (PdfException e) {
                    Logger logger = LoggerFactory.getLogger(PdfTrueTypeFont.class);
                    logger.error(e.getMessage());
                    fontStream = null;
                }
            }
            if (fontStream != null) {
                fontDescriptor.put(fontFileName, fontStream);
            }
        }
    }

    @Override
    protected TrueTypeFont initializeTypeFontForCopy(String encodingName) {
        throw new RuntimeException();
    }
}

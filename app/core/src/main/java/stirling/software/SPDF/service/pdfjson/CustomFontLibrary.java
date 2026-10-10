package stirling.software.SPDF.service.pdfjson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.HeaderTable;
import org.apache.fontbox.ttf.NamingTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;

import lombok.extern.slf4j.Slf4j;

/**
 * The fonts an administrator puts in a directory, so that text typed into a PDF whose embedded font
 * is a subset (only the glyphs the document used) is written with the same typeface instead of a
 * generic fallback.
 *
 * <p>The directory is read once, at construction. Only fonts with TrueType outlines are kept:
 * PDFBox cannot embed OpenType fonts with CFF outlines as a Type 0 font.
 */
@Slf4j
public class CustomFontLibrary {

    public static final String ID_PREFIX = "custom-";

    private static final Pattern SUBSET_PREFIX = Pattern.compile("^[A-Z]{6}\\+");
    private static final Pattern NAME_DELIMITERS = Pattern.compile("[-_,+]");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final Pattern BOLD_WEIGHT = Pattern.compile(".*[_-]?[6-9]00(wght)?.*");
    private static final int TYPOGRAPHIC_FAMILY_NAME_ID = 16;
    private static final int WINDOWS_PLATFORM = 3;
    private static final int WINDOWS_UNICODE_BMP = 1;
    private static final int ENGLISH_US = 0x409;

    private final Map<String, IndexedFont> fontsById;

    public CustomFontLibrary(Path directory) {
        this.fontsById = Collections.unmodifiableMap(index(directory));
        if (!fontsById.isEmpty()) {
            log.info(
                    "Loaded {} custom font(s) for the PDF editor from {}",
                    fontsById.size(),
                    directory);
        }
    }

    /**
     * The font to write {@code codePoint} with in place of the PDF font named {@code
     * originalFontName}: the font with the same PostScript name, else the one of the same family,
     * weight and style. Empty when no font of the directory matches or has a glyph for the
     * character.
     */
    public Optional<Entry> find(String originalFontName, int codePoint) {
        if (originalFontName == null || originalFontName.isBlank() || fontsById.isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalize(originalFontName);
        String compactName = compact(normalized);
        String family = compact(NAME_DELIMITERS.split(normalized)[0]);
        boolean bold = isBoldName(normalized);
        boolean italic = isItalicName(normalized);

        IndexedFont sameFace = null;
        for (IndexedFont font : fontsById.values()) {
            if (!font.covers(codePoint)) {
                continue;
            }
            if (font.compactPostScriptName.equals(compactName)) {
                return Optional.of(font.entry);
            }
            if (sameFace == null
                    && font.compactFamily.equals(family)
                    && font.bold == bold
                    && font.italic == italic) {
                sameFace = font;
            }
        }
        return Optional.ofNullable(sameFace).map(IndexedFont::entry);
    }

    public Optional<Entry> byId(String id) {
        return Optional.ofNullable(fontsById.get(id)).map(IndexedFont::entry);
    }

    /** Lowercase, without the subset prefix and spaces: "ABCDEF+Arial Bold" → "arialbold". */
    public static String normalize(String fontName) {
        return SUBSET_PREFIX
                .matcher(fontName)
                .replaceAll("")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    public static boolean isBoldName(String normalizedFontName) {
        return normalizedFontName.contains("bold")
                || normalizedFontName.contains("heavy")
                || normalizedFontName.contains("black")
                || BOLD_WEIGHT.matcher(normalizedFontName).matches();
    }

    public static boolean isItalicName(String normalizedFontName) {
        return normalizedFontName.contains("italic") || normalizedFontName.contains("oblique");
    }

    private static String compact(String name) {
        return NOT_ALPHANUMERIC.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static Map<String, IndexedFont> index(Path directory) {
        Map<String, IndexedFont> fonts = new LinkedHashMap<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return fonts;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        } catch (IOException e) {
            log.warn("Cannot list custom fonts in {}: {}", directory, e.getMessage());
            return fonts;
        }
        for (Path file : files) {
            String format = extension(file);
            if (!"ttf".equals(format) && !"otf".equals(format)) {
                continue;
            }
            try {
                IndexedFont font = read(file, format);
                if (font != null) {
                    fonts.putIfAbsent(font.entry.id(), font);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping custom font {}: {}", file, e.getMessage());
            }
        }
        return fonts;
    }

    private static IndexedFont read(Path file, String format) throws IOException {
        try (RandomAccessReadBufferedFile source = new RandomAccessReadBufferedFile(file.toFile());
                TrueTypeFont font =
                        "otf".equals(format)
                                ? new OTFParser().parse(source)
                                : new TTFParser().parse(source)) {
            if (font instanceof OpenTypeFont openType && openType.isPostScript()) {
                log.warn("Skipping custom font {}: CFF outlines cannot be embedded", file);
                return null;
            }
            String postScriptName = font.getName();
            CmapLookup cmap = font.getUnicodeCmapLookup(false);
            if (postScriptName == null || cmap == null) {
                return null;
            }
            NamingTable naming = font.getNaming();
            String family =
                    naming == null
                            ? null
                            : Optional.ofNullable(
                                            naming.getName(
                                                    TYPOGRAPHIC_FAMILY_NAME_ID,
                                                    WINDOWS_PLATFORM,
                                                    WINDOWS_UNICODE_BMP,
                                                    ENGLISH_US))
                                    .orElse(naming.getFontFamily());
            OS2WindowsMetricsTable os2 = font.getOS2Windows();
            HeaderTable header = font.getHeader();
            int macStyle = header != null ? header.getMacStyle() : 0;
            boolean bold =
                    os2 != null && os2.getWeightClass() > 0
                            ? os2.getWeightClass() >= 600
                            : (macStyle & 1) != 0;
            boolean italic =
                    (macStyle & 2) != 0 || (os2 != null && (os2.getFsSelection() & 1) != 0);

            String compactPostScriptName = compact(postScriptName);
            Entry entry =
                    new Entry(ID_PREFIX + compactPostScriptName, file, postScriptName, format);
            return new IndexedFont(
                    entry,
                    compactPostScriptName,
                    compact(family != null ? family : postScriptName),
                    bold,
                    italic,
                    cmap);
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** A font of the directory, found again by {@link #id()} when the converter embeds it. */
    public record Entry(String id, Path path, String postScriptName, String format) {}

    // The cmap subtable is read into memory when parsed, so it stays usable once the file is
    // closed.
    private record IndexedFont(
            Entry entry,
            String compactPostScriptName,
            String compactFamily,
            boolean bold,
            boolean italic,
            CmapLookup cmap) {
        boolean covers(int codePoint) {
            return cmap.getGlyphId(codePoint) > 0;
        }
    }
}

package stirling.software.SPDF.service.pdfjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class CustomFontLibraryTest {

    @TempDir Path fontsDir;

    private void addBundledFont(String fileName) throws IOException {
        try (InputStream in = new ClassPathResource("static/fonts/" + fileName).getInputStream()) {
            Files.copy(in, fontsDir.resolve(fileName));
        }
    }

    @Test
    @DisplayName("finds a font by the PostScript name of the PDF's font, subset prefix ignored")
    void findsByPostScriptName() throws IOException {
        addBundledFont("NotoSansHebrew-Regular.ttf");
        CustomFontLibrary library = new CustomFontLibrary(fontsDir);

        Optional<CustomFontLibrary.Entry> found = library.find("ABCDEF+NotoSansHebrew", 'א');

        assertTrue(found.isPresent());
        assertEquals("NotoSansHebrew", found.get().postScriptName());
        assertEquals("custom-notosanshebrew", found.get().id());
    }

    @Test
    @DisplayName("matches family and weight when the PDF names the style differently")
    void matchesFamilyAndStyle() throws IOException {
        addBundledFont("NotoSans-Regular.ttf");
        addBundledFont("NotoSans-Bold.ttf");
        CustomFontLibrary library = new CustomFontLibrary(fontsDir);

        Optional<CustomFontLibrary.Entry> found = library.find("NotoSans,SemiBold", 'é');

        assertTrue(found.isPresent());
        assertEquals("NotoSans-Bold", found.get().postScriptName());
    }

    @Test
    @DisplayName("matches the regular face when the PDF's name carries no style")
    void matchesRegularFace() throws IOException {
        addBundledFont("NotoSans-Regular.ttf");
        addBundledFont("NotoSans-Bold.ttf");
        CustomFontLibrary library = new CustomFontLibrary(fontsDir);

        Optional<CustomFontLibrary.Entry> found = library.find("ABCDEF+NotoSans", 'é');

        assertTrue(found.isPresent());
        assertEquals("NotoSans-Regular", found.get().postScriptName());
    }

    @Test
    @DisplayName("skips a font that has no glyph for the character")
    void skipsFontWithoutGlyph() throws IOException {
        addBundledFont("NotoSansHebrew-Regular.ttf");
        CustomFontLibrary library = new CustomFontLibrary(fontsDir);

        assertTrue(library.find("NotoSansHebrew", 'ก').isEmpty());
    }

    @Test
    @DisplayName("an unknown family finds nothing")
    void unknownFamilyFindsNothing() throws IOException {
        addBundledFont("NotoSans-Regular.ttf");
        CustomFontLibrary library = new CustomFontLibrary(fontsDir);

        assertTrue(library.find("Montserrat-Bold", 'é').isEmpty());
    }

    @Test
    @DisplayName("a missing directory is an empty library")
    void missingDirectoryIsEmpty() {
        CustomFontLibrary library = new CustomFontLibrary(fontsDir.resolve("absent"));

        assertTrue(library.find("NotoSans-Regular", 'é').isEmpty());
    }

    @Test
    @DisplayName("unreadable files are ignored and the other fonts still load")
    void ignoresUnreadableFiles() throws IOException {
        Files.writeString(fontsDir.resolve("broken.ttf"), "not a font", StandardCharsets.UTF_8);
        Files.writeString(fontsDir.resolve("readme.txt"), "fonts", StandardCharsets.UTF_8);
        addBundledFont("NotoSans-Regular.ttf");
        CustomFontLibrary library = new CustomFontLibrary(fontsDir);

        assertTrue(library.find("NotoSans-Regular", 'é').isPresent());
    }

    @Test
    @DisplayName("an entry is found again by its id, with the file it came from")
    void findsEntryById() throws IOException {
        addBundledFont("NotoSans-Bold.ttf");
        CustomFontLibrary library = new CustomFontLibrary(fontsDir);

        Optional<CustomFontLibrary.Entry> entry = library.byId("custom-notosansbold");

        assertTrue(entry.isPresent());
        assertEquals(fontsDir.resolve("NotoSans-Bold.ttf"), entry.get().path());
        assertEquals("ttf", entry.get().format());
    }
}

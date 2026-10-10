package stirling.software.SPDF.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.model.json.PdfJsonDocument;
import stirling.software.SPDF.model.json.PdfJsonTextElement;
import stirling.software.SPDF.service.pdfjson.PdfJsonFontService;
import stirling.software.SPDF.service.pdfjson.type3.Type3FontConversionService;
import stirling.software.SPDF.service.pdfjson.type3.Type3GlyphExtractor;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.TaskManager;
import stirling.software.common.util.TempFileManager;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Text edited into a PDF whose embedded font is a subset: the characters the subset lacks are
 * written with the full font of the custom fonts directory, through the real fallback and font
 * services.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class PdfJsonCustomFontRoundTripTest {

    private static final String HEBREW_FONT = "NotoSansHebrew-Regular.ttf";
    private static final String ORIGINAL_TEXT = "שלום";
    private static final String EDITED_TEXT = "שלום תודה";
    // PDFTextStripper returns Hebrew in visual order, so letters are checked, not words.
    private static final String[] LETTERS_ONLY_IN_EDIT = {"ת", "ד", "ה"};

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private EndpointConfiguration endpointConfiguration;
    @Mock private TempFileManager tempFileManager;
    @Mock private TaskManager taskManager;
    @Mock private Type3FontConversionService type3FontConversionService;
    @Mock private Type3GlyphExtractor type3GlyphExtractor;

    @TempDir Path fontsDir;

    private final ObjectMapper objectMapper =
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build();
    private final List<Path> createdTempFiles = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        when(tempFileManager.createTempFile(anyString()))
                .thenAnswer(
                        invocation -> {
                            Path path =
                                    Files.createTempFile(
                                            "pdfjson-custom-font", invocation.getArgument(0));
                            createdTempFiles.add(path);
                            return path.toFile();
                        });
        when(tempFileManager.deleteTempFile(any(File.class)))
                .thenAnswer(
                        invocation -> {
                            File file = invocation.getArgument(0);
                            return file != null && file.delete();
                        });
        when(taskManager.addNote(anyString(), anyString())).thenReturn(true);
        when(pdfDocumentFactory.load(any(Path.class), eq(true)))
                .thenAnswer(inv -> Loader.loadPDF(inv.getArgument(0, Path.class).toFile()));
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path path : createdTempFiles) {
            Files.deleteIfExists(path);
        }
    }

    @Test
    @DisplayName("characters missing from the subset are written with the directory's full font")
    void editedTextUsesTheFullFont() throws Exception {
        copyBundledFont(HEBREW_FONT, fontsDir);
        PdfJsonConversionService service = conversionService(fontsDir);

        byte[] rebuilt = editAndRebuild(service, subsetFontPdf());

        assertThat(fontNames(rebuilt)).contains("NotoSansHebrew");
        assertThat(extractText(rebuilt)).contains(LETTERS_ONLY_IN_EDIT);
    }

    @Test
    @DisplayName("without the font in the directory, the missing characters are lost")
    void withoutTheFontTheCharactersAreLost() throws Exception {
        PdfJsonConversionService service = conversionService(fontsDir);

        byte[] rebuilt = editAndRebuild(service, subsetFontPdf());

        assertThat(fontNames(rebuilt)).doesNotContain("NotoSansHebrew");
        assertThat(extractText(rebuilt)).doesNotContain(LETTERS_ONLY_IN_EDIT);
    }

    private PdfJsonConversionService conversionService(Path customFontsDir) throws Exception {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getPdfEditor().setCustomFontsDirectory(customFontsDir.toString());
        PdfJsonFallbackFontService fallbackFontService =
                new PdfJsonFallbackFontService(new DefaultResourceLoader(), properties);
        ReflectionTestUtils.setField(
                fallbackFontService,
                "legacyFallbackFontLocation",
                PdfJsonFallbackFontService.DEFAULT_FALLBACK_FONT_LOCATION);
        ReflectionTestUtils.invokeMethod(fallbackFontService, "loadConfig");
        return new PdfJsonConversionService(
                pdfDocumentFactory,
                objectMapper,
                endpointConfiguration,
                tempFileManager,
                taskManager,
                new PdfJsonCosMapper(),
                fallbackFontService,
                new PdfJsonFontService(tempFileManager, properties),
                type3FontConversionService,
                type3GlyphExtractor,
                properties);
    }

    /** A page written with a subset of Noto Sans Hebrew: only the glyphs of "שלום". */
    private byte[] subsetFontPdf() throws IOException {
        try (PDDocument document = new PDDocument();
                InputStream fontStream =
                        new ClassPathResource("static/fonts/" + HEBREW_FONT).getInputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType0Font subset = PDType0Font.load(document, fontStream, true);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(subset, 18);
                content.newLineAtOffset(72, 700);
                content.showText(ORIGINAL_TEXT);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] editAndRebuild(PdfJsonConversionService service, byte[] pdf) throws IOException {
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        service.convertPdfToJson(
                new MockMultipartFile("fileInput", "input.pdf", "application/pdf", pdf), json);
        PdfJsonDocument document =
                objectMapper.readValue(json.toByteArray(), PdfJsonDocument.class);
        List<PdfJsonTextElement> elements = document.getPages().get(0).getTextElements();
        assertThat(elements).isNotEmpty();
        elements.get(0).setText(EDITED_TEXT);
        for (int i = 1; i < elements.size(); i++) {
            elements.get(i).setText("");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.convertJsonToPdf(document, out);
        return out.toByteArray();
    }

    private static void copyBundledFont(String fileName, Path directory) throws IOException {
        try (InputStream in = new ClassPathResource("static/fonts/" + fileName).getInputStream()) {
            Files.copy(in, directory.resolve(fileName));
        }
    }

    private static List<String> fontNames(byte[] pdf) throws IOException {
        List<String> names = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDResources resources = document.getPage(0).getResources();
            for (COSName name : resources.getFontNames()) {
                names.add(resources.getFont(name).getName());
            }
        }
        return names;
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}

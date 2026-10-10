package stirling.software.SPDF.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
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
 * Lines of a standard font with WinAnsiEncoding survive the editor's round trip, including the
 * characters WinAnsi codes in 0x80-0x9F, where it differs from Latin-1 (’ “ ” € – — …).
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class PdfJsonWinAnsiRoundTripTest {

    private static final String TYPOGRAPHIC_LINE = "L’entrée coûte 28,00 € – « vraiment » …";
    private static final String PLAIN_LINE = "Une ligne sans guillemets courbes";

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private EndpointConfiguration endpointConfiguration;
    @Mock private TempFileManager tempFileManager;
    @Mock private TaskManager taskManager;
    @Mock private Type3FontConversionService type3FontConversionService;
    @Mock private Type3GlyphExtractor type3GlyphExtractor;

    private final ObjectMapper objectMapper =
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build();
    private final List<Path> createdTempFiles = new ArrayList<>();
    private PdfJsonConversionService service;

    @BeforeEach
    void setUp() throws IOException {
        when(tempFileManager.createTempFile(anyString()))
                .thenAnswer(
                        invocation -> {
                            Path path =
                                    Files.createTempFile(
                                            "pdfjson-winansi", invocation.getArgument(0));
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

        ApplicationProperties properties = new ApplicationProperties();
        PdfJsonFallbackFontService fallbackFontService =
                new PdfJsonFallbackFontService(new DefaultResourceLoader(), properties);
        ReflectionTestUtils.setField(
                fallbackFontService,
                "legacyFallbackFontLocation",
                PdfJsonFallbackFontService.DEFAULT_FALLBACK_FONT_LOCATION);
        ReflectionTestUtils.invokeMethod(fallbackFontService, "loadConfig");
        service =
                new PdfJsonConversionService(
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

    @AfterEach
    void tearDown() throws IOException {
        for (Path path : createdTempFiles) {
            Files.deleteIfExists(path);
        }
    }

    @Test
    @DisplayName("an unedited round trip keeps a line with typographic characters")
    void unchangedRoundTripKeepsTypographicLine() throws IOException {
        byte[] rebuilt = toPdf(toJson(standardFontPdf()));

        String text = extractText(rebuilt);
        assertThat(text).contains(TYPOGRAPHIC_LINE);
        assertThat(text).contains(PLAIN_LINE);
    }

    @Test
    @DisplayName("an edited line keeps its typographic characters")
    void editedLineKeepsTypographicCharacters() throws IOException {
        PdfJsonDocument document = toJson(standardFontPdf());
        for (PdfJsonTextElement element : document.getPages().get(0).getTextElements()) {
            if (PLAIN_LINE.equals(element.getText())) {
                element.setText("Modifiée : “oui” — 5 €");
            }
        }

        String text = extractText(toPdf(document));

        assertThat(text).contains("Modifiée : “oui” — 5 €");
        assertThat(text).contains(TYPOGRAPHIC_LINE);
    }

    private byte[] standardFontPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType1Font times = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(times, 12);
                content.newLineAtOffset(72, 700);
                content.showText(TYPOGRAPHIC_LINE);
                content.newLineAtOffset(0, -20);
                content.showText(PLAIN_LINE);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private PdfJsonDocument toJson(byte[] pdf) throws IOException {
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        service.convertPdfToJson(
                new MockMultipartFile("fileInput", "input.pdf", "application/pdf", pdf), json);
        return objectMapper.readValue(json.toByteArray(), PdfJsonDocument.class);
    }

    private byte[] toPdf(PdfJsonDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.convertJsonToPdf(document, out);
        return out.toByteArray();
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}

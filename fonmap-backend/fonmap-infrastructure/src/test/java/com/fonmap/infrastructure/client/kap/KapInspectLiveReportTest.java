package com.fonmap.infrastructure.client.kap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

public class KapInspectLiveReportTest {

    @Test
    public void testInspectLiveContent() throws Exception {
        File file = new File("C:/Users/Yakup Yılmaz/.gemini/antigravity-ide/brain/6a494126-7171-4bb9-a182-63651620ed27/scratch/test_downloaded.pdf");
        byte[] bytes = Files.readAllBytes(file.toPath());
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            System.out.println("=== LIVE KAP REPORT TEXT (FIRST 800 CHARS) ===");
            System.out.println(text.substring(0, Math.min(800, text.length())));
        }
    }
}

package com.fonmap.infrastructure.client.kap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Disabled("Yerel ham PDF inceleme testi - CI ortamında çalıştırılmaz")
public class KapRawDownloadTest {

    @Test
    public void testRawKapDownloadParsing() throws Exception {
        File file = new File("C:/Users/Yakup Yılmaz/.gemini/antigravity-ide/brain/6a494126-7171-4bb9-a182-63651620ed27/scratch/test_downloaded.pdf");
        if (!file.exists()) {
            System.out.println("File does not exist");
            return;
        }

        byte[] rawBytes = Files.readAllBytes(file.toPath());
        System.out.println("Raw bytes length: " + rawBytes.length);

        // Try directly with raw bytes
        try {
            PDDocument doc = Loader.loadPDF(rawBytes);
            System.out.println("Raw bytes loaded successfully! Pages: " + doc.getNumberOfPages());
            doc.close();
        } catch (Exception e) {
            System.out.println("Direct load failed: " + e.getMessage());
        }

        // Try with unwrapped/stripped bytes if magic is present
        byte[] pdfBytes = rawBytes;
        if (rawBytes.length > 4 && rawBytes[0] == (byte) 0xAC && rawBytes[1] == (byte) 0xED) {
            // Find %PDF-
            for (int i = 0; i < Math.min(rawBytes.length - 4, 1024); i++) {
                if (rawBytes[i] == '%' && rawBytes[i+1] == 'P' && rawBytes[i+2] == 'D' && rawBytes[i+3] == 'F') {
                    pdfBytes = java.util.Arrays.copyOfRange(rawBytes, i, rawBytes.length);
                    System.out.println("Found %PDF at offset " + i + ", stripped " + i + " bytes");
                    break;
                }
            }
        }

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            System.out.println("Unwrapped bytes loaded successfully! Pages: " + doc.getNumberOfPages());
        }
    }
}

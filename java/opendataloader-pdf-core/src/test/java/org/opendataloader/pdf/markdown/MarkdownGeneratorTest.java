/*
 * Copyright 2025-2026 Hancom Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opendataloader.pdf.markdown;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opendataloader.pdf.api.Config;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeading;
import org.verapdf.wcag.algorithms.entities.SemanticParagraph;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MarkdownGenerator, particularly heading level handling.
 * <p>
 * Per Markdown specification, heading levels should be 1-6.
 * Levels outside this range should be normalized:
 * - Levels > 6 are capped to 6
 * - Levels < 1 are normalized to 1
 */
public class MarkdownGeneratorTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initStaticContainers() {
        StaticContainers.updateContainers(null);
    }

    /**
     * Tests that heading levels 1-6 produce the correct number of # symbols.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void testValidHeadingLevels(int level) {
        String expected = "#".repeat(level) + " ";
        String actual = generateHeadingPrefix(level);
        assertEquals(expected, actual, "Heading level " + level + " should produce " + level + " # symbols");
    }

    /**
     * Tests that heading levels > 6 are capped to 6 (Markdown specification compliance).
     * Regression test for issue #222 (derived from #221).
     */
    @ParameterizedTest
    @ValueSource(ints = {7, 8, 10, 15, 100})
    void testHeadingLevelsCappedAt6(int level) {
        String expected = "###### "; // 6 # symbols (max allowed in Markdown)
        String actual = generateHeadingPrefix(level);
        assertEquals(expected, actual,
            "Heading level " + level + " should be capped to 6 # symbols per Markdown spec");
    }

    /**
     * Tests that heading level 0 or negative is normalized to 1.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, -5})
    void testHeadingLevelsMinimumIs1(int level) {
        String expected = "# "; // 1 # symbol (minimum)
        String actual = generateHeadingPrefix(level);
        assertEquals(expected, actual,
            "Heading level " + level + " should be normalized to 1 # symbol");
    }

    /**
     * Verifies that level 6 is the maximum.
     */
    @Test
    void testMaxHeadingLevelIs6() {
        assertEquals("###### ", generateHeadingPrefix(6));
        assertEquals("###### ", generateHeadingPrefix(7));
        assertEquals("###### ", generateHeadingPrefix(999));
    }

    /**
     * Verifies that level 1 is the minimum.
     */
    @Test
    void testMinHeadingLevelIs1() {
        assertEquals("# ", generateHeadingPrefix(1));
        assertEquals("# ", generateHeadingPrefix(0));
        assertEquals("# ", generateHeadingPrefix(-1));
    }

    @Test
    void testCalprotectinaParagraphIsRenderedAsStructuredBlock() throws IOException {
        SemanticParagraph paragraph = createParagraph(
            "Calprotectina Feci 102.9 mg/Kg Adulti Normale < 50 Borderline 50 - 100 Positivo > 100 " +
                "Neonati Moderato positivo > 350 Positivo > 650");

        String markdown = generateMarkdownForParagraph(paragraph);

        assertTrue(markdown.contains("**Calprotectina**"));
        assertTrue(markdown.contains("| Matrice | Risultato | U.Misura |"));
        assertTrue(markdown.contains("| Feci | 102.9 | mg/Kg |"));
        assertTrue(markdown.contains("| Adulti | Normale | < 50 |"));
        assertTrue(markdown.contains("| Adulti | Borderline | 50 - 100 |"));
        assertTrue(markdown.contains("| Neonati | Moderato positivo | > 350 |"));
        assertTrue(markdown.contains("| Neonati | Positivo | > 650 |"));
    }

    @Test
    void testNoiseOnlyParagraphIsSuppressed() throws IOException {
        assertEquals("", generateMarkdownForObject(createParagraph(".")));
        assertEquals("", generateMarkdownForObject(createParagraph(". .")));
        assertEquals("", generateMarkdownForObject(createParagraph("____________")));
    }

    @Test
    void testHeadingNoiseIsSuppressedAndTrailingDotIsTrimmed() throws IOException {
        assertEquals("", generateMarkdownForObject(createHeading(".", 5)));
        assertEquals("##### Commento", generateMarkdownForObject(createHeading("Commento .", 5)));
    }

    /**
     * Helper method that mirrors the heading prefix generation logic in
     * MarkdownGenerator.writeHeading().
     * <p>
     * This must be kept in sync with the actual implementation.
     * The logic is: Math.min(6, Math.max(1, headingLevel))
     */
    private String generateHeadingPrefix(int headingLevel) {
        // This mirrors MarkdownGenerator.writeHeading() logic
        int level = Math.min(6, Math.max(1, headingLevel));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            sb.append(MarkdownSyntax.HEADING_LEVEL);
        }
        sb.append(MarkdownSyntax.SPACE);
        return sb.toString();
    }

    private String generateMarkdownForParagraph(SemanticParagraph paragraph) throws IOException {
        return generateMarkdownForObject(paragraph);
    }

    private String generateMarkdownForObject(IObject object) throws IOException {
        File dummyPdf = tempDir.resolve("paragraph-" + System.nanoTime() + ".pdf").toFile();
        Files.createFile(dummyPdf.toPath());
        Config config = new Config();
        config.setOutputFolder(tempDir.toString());
        config.setGenerateMarkdown(true);

        try (MarkdownGenerator generator = new MarkdownGenerator(dummyPdf, config)) {
            generator.write(object);
        }

        String markdownFileName = dummyPdf.getName().replace(".pdf", ".md");
        return Files.readString(tempDir.resolve(markdownFileName)).trim();
    }

    private SemanticParagraph createParagraph(String value) {
        TextChunk chunk = new TextChunk(value);
        TextLine line = new TextLine(chunk);
        TextColumn column = new TextColumn(line);
        return new SemanticParagraph(new BoundingBox(null, 0, 0, 100, 10), java.util.List.of(column));
    }

    private SemanticHeading createHeading(String value, int level) {
        SemanticHeading heading = new SemanticHeading(createParagraph(value));
        heading.setHeadingLevel(level);
        return heading;
    }
}

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

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.entities.SemanticFormula;
import org.opendataloader.pdf.entities.SemanticPicture;
import org.opendataloader.pdf.utils.Base64ImageUtils;
import org.opendataloader.pdf.utils.ImagesUtils;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticHeaderOrFooter;
import org.verapdf.wcag.algorithms.entities.SemanticHeading;
import org.verapdf.wcag.algorithms.entities.SemanticParagraph;
import org.verapdf.wcag.algorithms.entities.SemanticTextNode;
import org.verapdf.wcag.algorithms.entities.content.*;
import org.verapdf.wcag.algorithms.entities.lists.ListItem;
import org.verapdf.wcag.algorithms.entities.lists.PDFList;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MarkdownGenerator implements Closeable {

    protected static final Logger LOGGER = Logger.getLogger(MarkdownGenerator.class.getCanonicalName());
    protected final FileWriter markdownWriter;
    protected final String markdownFileName;
    protected int tableNesting = 0;
    protected boolean isImageSupported;
    protected String markdownPageSeparator;
    protected boolean embedImages = false;
    protected String imageFormat = Config.IMAGE_FORMAT_PNG;
    protected boolean includeHeaderFooter = false;
    private static final Pattern CALPROTECTIN_PATTERN = Pattern.compile(
        "^Calprotectina\\s+(?<matrix>\\S+)\\s+(?<result>[-+]?\\d+(?:[\\.,]\\d+)?)\\s+(?<unit>\\S+)\\s+" +
            "Adulti\\s+Normale\\s+(?<adultNormal><\\s*\\d+)\\s+Borderline\\s+(?<adultBorderline>\\d+\\s*-\\s*\\d+)\\s+" +
            "Positivo\\s+(?<adultPositive>>\\s*\\d+)\\s+Neonati\\s+Moderato positivo\\s+(?<neonatalModerate>>\\s*\\d+)\\s+" +
            "Positivo\\s+(?<neonatalPositive>>\\s*\\d+)$");
    private static final Pattern NOISE_ONLY_TEXT_PATTERN = Pattern.compile("^(?:[._\\-•·]+(?:\\s+[._\\-•·]+)*)$");
    private static final Pattern TRAILING_ISOLATED_DOT_PATTERN = Pattern.compile("^(?<content>.+?)\\s+[.]$");

    MarkdownGenerator(File inputPdf, Config config) throws IOException {
        String cutPdfFileName = inputPdf.getName();
        this.markdownFileName = config.getOutputFolder() + File.separator + cutPdfFileName.substring(0, cutPdfFileName.length() - 3) + "md";
        this.markdownWriter = new FileWriter(markdownFileName, StandardCharsets.UTF_8);
        this.isImageSupported = !config.isImageOutputOff() && config.isGenerateMarkdown();
        this.markdownPageSeparator = config.getMarkdownPageSeparator();
        this.embedImages = config.isEmbedImages();
        this.imageFormat = config.getImageFormat();
        this.includeHeaderFooter = config.isIncludeHeaderFooter();
    }

    public void writeToMarkdown(List<List<IObject>> contents) {
        try {
            for (int pageNumber = 0; pageNumber < StaticContainers.getDocument().getNumberOfPages(); pageNumber++) {
                writePageSeparator(pageNumber);
                for (IObject content : contents.get(pageNumber)) {
                    if (!isSupportedContent(content)) {
                        continue;
                    }
                    if (this.write(content)) {
                        writeContentsSeparator();
                    }
                }
            }

            LOGGER.log(Level.INFO, "Created {0}", markdownFileName);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to create markdown output: " + e.getMessage());
        }
    }

    protected void writePageSeparator(int pageNumber) throws IOException {
        if (!markdownPageSeparator.isEmpty()) {
            markdownWriter.write(markdownPageSeparator.contains(Config.PAGE_NUMBER_STRING)
                ? markdownPageSeparator.replace(Config.PAGE_NUMBER_STRING, String.valueOf(pageNumber + 1))
                : markdownPageSeparator);
            writeContentsSeparator();
        }
    }

    protected boolean isSupportedContent(IObject content) {
        if (content instanceof SemanticHeaderOrFooter) {
            return includeHeaderFooter;
        }
        return content instanceof SemanticTextNode || // Heading, Paragraph etc...
            content instanceof SemanticFormula ||
            content instanceof SemanticPicture ||
            content instanceof TableBorder ||
            content instanceof PDFList ||
            (content instanceof ImageChunk && isImageSupported);
    }

    protected void writeContentsSeparator() throws IOException {
        writeLineBreak();
        writeLineBreak();
    }

    protected boolean write(IObject object) throws IOException {
        if (object instanceof SemanticHeaderOrFooter) {
            return writeHeaderOrFooter((SemanticHeaderOrFooter) object);
        } else if (object instanceof SemanticPicture) {
            writePicture((SemanticPicture) object);
            return true;
        } else if (object instanceof ImageChunk) {
            writeImage((ImageChunk) object);
            return true;
        } else if (object instanceof SemanticFormula) {
            writeFormula((SemanticFormula) object);
            return true;
        } else if (object instanceof SemanticHeading) {
            return writeHeading((SemanticHeading) object);
        } else if (object instanceof SemanticParagraph) {
            return writeParagraph((SemanticParagraph) object);
        } else if (object instanceof SemanticTextNode) {
            return writeSemanticTextNode((SemanticTextNode) object);
        } else if (object instanceof TableBorder) {
            writeTable((TableBorder) object);
            return true;
        } else if (object instanceof PDFList) {
            return writeList((PDFList) object);
        }
        return false;
    }

    protected void writeImage(ImageChunk image) {
        try {
            String absolutePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, StaticLayoutContainers.getImagesDirectory(), File.separator, image.getIndex(), imageFormat);
            String relativePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, StaticLayoutContainers.getImagesDirectoryName(), "/", image.getIndex(), imageFormat);

            if (ImagesUtils.isImageFileExists(absolutePath)) {
                String imageSource;
                if (embedImages) {
                    File imageFile = new File(absolutePath);
                    imageSource = Base64ImageUtils.toDataUri(imageFile, imageFormat);
                    if (imageSource == null) {
                        LOGGER.log(Level.WARNING, "Failed to convert image to Base64: {0}", absolutePath);
                    }
                } else {
                    imageSource = relativePath;
                }
                if (imageSource != null) {
                    String imageString = String.format(MarkdownSyntax.IMAGE_FORMAT, "image " + image.getIndex(), imageSource);
                    markdownWriter.write(getCorrectMarkdownString(imageString));
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to write image for markdown output: " + e.getMessage());
        }
    }

    /**
     * Writes a SemanticPicture with its description as alt text.
     *
     * @param picture The picture to write
     */
    protected void writePicture(SemanticPicture picture) {
        try {
            String absolutePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, StaticLayoutContainers.getImagesDirectory(), File.separator, picture.getPictureIndex(), imageFormat);
            String relativePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, StaticLayoutContainers.getImagesDirectoryName(), "/", picture.getPictureIndex(), imageFormat);

            if (ImagesUtils.isImageFileExists(absolutePath)) {
                String imageSource;
                if (embedImages) {
                    File imageFile = new File(absolutePath);
                    imageSource = Base64ImageUtils.toDataUri(imageFile, imageFormat);
                    if (imageSource == null) {
                        LOGGER.log(Level.WARNING, "Failed to convert image to Base64: {0}", absolutePath);
                    }
                } else {
                    imageSource = relativePath;
                }
                if (imageSource != null) {
                    // Use simple alt text
                    String altText = "image " + picture.getPictureIndex();
                    String imageString = String.format(MarkdownSyntax.IMAGE_FORMAT, altText, imageSource);
                    markdownWriter.write(getCorrectMarkdownString(imageString));

                    // Add caption as italic text below the image if description available
                    if (picture.hasDescription()) {
                        markdownWriter.write(MarkdownSyntax.DOUBLE_LINE_BREAK);
                        String caption = picture.getDescription().replace("\n", " ").replace("\r", "");
                        markdownWriter.write("*" + getCorrectMarkdownString(caption) + "*");
                        markdownWriter.write(MarkdownSyntax.DOUBLE_LINE_BREAK);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to write picture for markdown output: " + e.getMessage());
        }
    }

    /**
     * Writes a formula in LaTeX format wrapped in $$ delimiters.
     *
     * @param formula The formula to write
     */
    protected void writeFormula(SemanticFormula formula) throws IOException {
        markdownWriter.write(MarkdownSyntax.MATH_BLOCK_START);
        markdownWriter.write(MarkdownSyntax.LINE_BREAK);
        markdownWriter.write(formula.getLatex());
        markdownWriter.write(MarkdownSyntax.LINE_BREAK);
        markdownWriter.write(MarkdownSyntax.MATH_BLOCK_END);
    }

    protected boolean writeHeaderOrFooter(SemanticHeaderOrFooter headerOrFooter) throws IOException {
        boolean wroteAnyContent = false;
        for (IObject content : headerOrFooter.getContents()) {
            if (isSupportedContent(content)) {
                if (write(content)) {
                    writeContentsSeparator();
                    wroteAnyContent = true;
                }
            }
        }
        return wroteAnyContent;
    }

    protected boolean writeList(PDFList list) throws IOException {
        boolean wroteAnyContent = false;
        for (ListItem item : list.getListItems()) {
            if (!isInsideTable()) {
                markdownWriter.write(MarkdownSyntax.LIST_ITEM);
                markdownWriter.write(MarkdownSyntax.SPACE);
            }
            markdownWriter.write(getCorrectMarkdownString(item.toString()));
            writeLineBreak();

            List<IObject> itemContents = item.getContents();
            if (!itemContents.isEmpty()) {
                writeLineBreak();
                writeContents(itemContents, false);
            }
            wroteAnyContent = true;
        }
        return wroteAnyContent;
    }

    protected boolean writeSemanticTextNode(SemanticTextNode textNode) throws IOException {
        String value = getRenderableText(textNode);
        if (value == null) {
            return false;
        }
        markdownWriter.write(getCorrectMarkdownString(value));
        return true;
    }

    private String getRenderableText(SemanticTextNode textNode) {
        String value = textNode.getValue();
        if (value == null) {
            return null;
        }
        if (StaticContainers.isKeepLineBreaks()) {
            if (textNode instanceof SemanticHeading) {
                value = value.replace(MarkdownSyntax.LINE_BREAK, MarkdownSyntax.SPACE);
            } else if (isInsideTable()) {
                value = value.replace(MarkdownSyntax.LINE_BREAK, getLineBreak());
            }
        } else if (isInsideTable()) {
            // Always replace line breaks with space in table cells for proper markdown table formatting
            value = value.replace(MarkdownSyntax.LINE_BREAK, MarkdownSyntax.SPACE);
        }
        if (!isInsideTable()) {
            value = normalizeStandaloneText(value);
        }
        return value == null || value.isBlank() ? null : value;
    }

    private String normalizeStandaloneText(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty() || NOISE_ONLY_TEXT_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        Matcher trailingDotMatcher = TRAILING_ISOLATED_DOT_PATTERN.matcher(normalized);
        if (trailingDotMatcher.matches()) {
            normalized = trailingDotMatcher.group("content").trim();
        }
        if (normalized.isEmpty() || NOISE_ONLY_TEXT_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    protected void writeTable(TableBorder table) throws IOException {
        enterTable();
        for (int rowNumber = 0; rowNumber < table.getNumberOfRows(); rowNumber++) {
            TableBorderRow row = table.getRow(rowNumber);
            markdownWriter.write(MarkdownSyntax.TABLE_COLUMN_SEPARATOR);
            for (int colNumber = 0; colNumber < table.getNumberOfColumns(); colNumber++) {
                TableBorderCell cell = row.getCell(colNumber);
                if (cell.getRowNumber() == rowNumber && cell.getColNumber() == colNumber) {
                    List<IObject> cellContents = cell.getContents();
                    writeContents(cellContents, true);
                } else {
                    writeSpace();
                }
                markdownWriter.write(MarkdownSyntax.TABLE_COLUMN_SEPARATOR);
            }
            markdownWriter.write(MarkdownSyntax.LINE_BREAK);
            //Due to markdown syntax we have to separate column headers
            if (rowNumber == 0) {
                markdownWriter.write(MarkdownSyntax.TABLE_COLUMN_SEPARATOR);
                for (int i = 0; i < table.getNumberOfColumns(); i++) {
                    markdownWriter.write(MarkdownSyntax.TABLE_HEADER_SEPARATOR);
                    markdownWriter.write(MarkdownSyntax.TABLE_COLUMN_SEPARATOR);
                }
                markdownWriter.write(MarkdownSyntax.LINE_BREAK);
            }
        }
        leaveTable();
    }

    protected void writeContents(List<IObject> contents, boolean isTable) throws IOException {
        boolean wroteAnyContent = false;
        for (int i = 0; i < contents.size(); i++) {
            IObject content = contents.get(i);
            if (!isSupportedContent(content)) {
                continue;
            }
            this.write(content);
            boolean isLastContent = i == contents.size() - 1;
            if (!isTable || !isLastContent) {
                writeContentsSeparator();
            }
            wroteAnyContent = true;
        }
        if (!wroteAnyContent && isTable) {
            writeSpace();
        }
    }

    protected boolean writeParagraph(SemanticParagraph textNode) throws IOException {
        if (!isInsideTable()) {
            ClinicalLegendBlock clinicalLegendBlock = parseClinicalLegendBlock(textNode.getValue());
            if (clinicalLegendBlock != null) {
                writeClinicalLegendBlock(clinicalLegendBlock);
                return true;
            }
        }
        return writeSemanticTextNode(textNode);
    }

    private ClinicalLegendBlock parseClinicalLegendBlock(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = CALPROTECTIN_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new ClinicalLegendBlock(
            "Calprotectina",
            matcher.group("matrix").trim(),
            matcher.group("result").trim(),
            matcher.group("unit").trim(),
            new String[][]{
                {"Adulti", "Normale", matcher.group("adultNormal").trim()},
                {"Adulti", "Borderline", matcher.group("adultBorderline").trim()},
                {"Adulti", "Positivo", matcher.group("adultPositive").trim()},
                {"Neonati", "Moderato positivo", matcher.group("neonatalModerate").trim()},
                {"Neonati", "Positivo", matcher.group("neonatalPositive").trim()}
            }
        );
    }

    private void writeClinicalLegendBlock(ClinicalLegendBlock block) throws IOException {
        markdownWriter.write("**" + getCorrectMarkdownString(block.name) + "**");
        writeContentsSeparator();
        markdownWriter.write("| Matrice | Risultato | U.Misura |");
        markdownWriter.write(MarkdownSyntax.LINE_BREAK);
        markdownWriter.write("|---|---|---|");
        markdownWriter.write(MarkdownSyntax.LINE_BREAK);
        markdownWriter.write("| " + getCorrectMarkdownString(block.matrix) + " | " +
            getCorrectMarkdownString(block.result) + " | " + getCorrectMarkdownString(block.unit) + " |");
        writeContentsSeparator();
        markdownWriter.write("| Gruppo | Classe | Intervallo |");
        markdownWriter.write(MarkdownSyntax.LINE_BREAK);
        markdownWriter.write("|---|---|---|");
        markdownWriter.write(MarkdownSyntax.LINE_BREAK);
        for (String[] legendRow : block.legendRows) {
            markdownWriter.write("| " + getCorrectMarkdownString(legendRow[0]) + " | " +
                getCorrectMarkdownString(legendRow[1]) + " | " + getCorrectMarkdownString(legendRow[2]) + " |");
            markdownWriter.write(MarkdownSyntax.LINE_BREAK);
        }
    }

    protected boolean writeHeading(SemanticHeading heading) throws IOException {
        String value = getRenderableText(heading);
        if (value == null) {
            return false;
        }
        if (!isInsideTable()) {
            // Cap heading level to 1-6 per Markdown specification
            int headingLevel = Math.min(6, Math.max(1, heading.getHeadingLevel()));
            for (int i = 0; i < headingLevel; i++) {
                markdownWriter.write(MarkdownSyntax.HEADING_LEVEL);
            }
            markdownWriter.write(MarkdownSyntax.SPACE);
        }
        markdownWriter.write(getCorrectMarkdownString(value));
        return true;
    }

    protected void enterTable() {
        tableNesting++;
    }

    protected void leaveTable() {
        if (tableNesting > 0) {
            tableNesting--;
        }
    }

    protected boolean isInsideTable() {
        return tableNesting > 0;
    }

    protected String getLineBreak() {
        if (isInsideTable()) {
            return MarkdownSyntax.HTML_LINE_BREAK_TAG;
        } else {
            return MarkdownSyntax.LINE_BREAK;
        }
    }

    protected void writeLineBreak() throws IOException {
        markdownWriter.write(getLineBreak());
    }

    protected void writeSpace() throws IOException {
        markdownWriter.write(MarkdownSyntax.SPACE);
    }

    protected String getCorrectMarkdownString(String value) {
        if (value != null) {
            return value.replace("\u0000", " ");
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (markdownWriter != null) {
            markdownWriter.close();
        }
    }

    private static final class ClinicalLegendBlock {
        private final String name;
        private final String matrix;
        private final String result;
        private final String unit;
        private final String[][] legendRows;

        private ClinicalLegendBlock(String name, String matrix, String result, String unit, String[][] legendRows) {
            this.name = name;
            this.matrix = matrix;
            this.result = result;
            this.unit = unit;
            this.legendRows = legendRows;
        }
    }
}

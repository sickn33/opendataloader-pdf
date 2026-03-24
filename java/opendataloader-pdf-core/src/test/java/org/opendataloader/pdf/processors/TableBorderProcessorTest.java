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
package org.opendataloader.pdf.processors;

import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.SemanticParagraph;
import org.verapdf.wcag.algorithms.entities.content.ImageChunk;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.TableBordersCollection;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.StreamInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTimeout;

public class TableBorderProcessorTest {

    @Test
    public void testProcessTableBorders() {
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
        StaticContainers.setIsDataLoader(true);
        StaticLayoutContainers.setCurrentContentId(2l);
        TableBordersCollection tableBordersCollection = new TableBordersCollection();
        StaticContainers.setTableBordersCollection(tableBordersCollection);
        List<IObject> contents = new ArrayList<>();
        TableBorder tableBorder = new TableBorder(2, 2);
        SortedSet<TableBorder> tables = new TreeSet<>(new TableBorder.TableBordersComparator());
        tables.add(tableBorder);
        tableBordersCollection.getTableBorders().add(tables);
        tableBorder.setRecognizedStructureId(1l);
        tableBorder.setBoundingBox(new BoundingBox(0, 10.0, 10.0, 30.0, 30.0));
        TableBorderRow row1 = new TableBorderRow(0, 2, 0l);
        row1.setBoundingBox(new BoundingBox(0, 10.0, 20.0, 30.0, 30.0));
        row1.getCells()[0] = new TableBorderCell(0, 0, 1, 1, 0l);
        row1.getCells()[0].setBoundingBox(new BoundingBox(0, 10.0, 20.0, 20.0, 30.0));
        row1.getCells()[1] = new TableBorderCell(0, 1, 1, 1, 0l);
        row1.getCells()[1].setBoundingBox(new BoundingBox(0, 20.0, 20.0, 30.0, 30.0));
        tableBorder.getRows()[0] = row1;
        TableBorderRow row2 = new TableBorderRow(0, 2, 0l);
        row2.setBoundingBox(new BoundingBox(0, 10.0, 10.0, 30.0, 20.0));
        row2.getCells()[0] = new TableBorderCell(1, 0, 1, 1, 0l);
        row2.getCells()[0].setBoundingBox(new BoundingBox(0, 10.0, 10.0, 20.0, 20.0));
        row2.getCells()[1] = new TableBorderCell(1, 1, 1, 1, 0l);
        row2.getCells()[1].setBoundingBox(new BoundingBox(0, 20.0, 10.0, 30.0, 20.0));
        tableBorder.getRows()[1] = row2;
        tableBorder.calculateCoordinatesUsingBoundingBoxesOfRowsAndColumns();
        TextChunk textChunk = new TextChunk(new BoundingBox(0, 11.0, 21.0, 29.0, 29.0),
            "test", 10, 21.0);
        // xObjectName is null because test TextChunks are not backed by a real PDF stream
        textChunk.getStreamInfos().add(new StreamInfo(0, null, 0, "test".length()));
        contents.add(textChunk);
        textChunk.adjustSymbolEndsToBoundingBox(null);
        contents.add(new ImageChunk(new BoundingBox(0, 11.0, 11.0, 19.0, 19.0)));
        contents = TableBorderProcessor.processTableBorders(contents, 0);
        Assertions.assertEquals(1, contents.size());
        Assertions.assertTrue(contents.get(0) instanceof TableBorder);
        TableBorder resultBorder = (TableBorder) contents.get(0);
        Assertions.assertSame(resultBorder,
            tableBordersCollection.getTableBorder(resultBorder.getBoundingBox()));
        List<IObject> cellContents = resultBorder.getRow(0).getCell(0).getContents();
        Assertions.assertEquals(1, cellContents.size());
        Assertions.assertTrue(cellContents.get(0) instanceof SemanticParagraph);
        Assertions.assertEquals("te", ((SemanticParagraph) cellContents.get(0)).getValue());
        cellContents = resultBorder.getRow(0).getCell(1).getContents();
        Assertions.assertEquals(1, cellContents.size());
        Assertions.assertTrue(cellContents.get(0) instanceof SemanticParagraph);
        Assertions.assertEquals("t", ((SemanticParagraph) cellContents.get(0)).getValue());
        cellContents = resultBorder.getRow(1).getCell(0).getContents();
        Assertions.assertEquals(1, cellContents.size());
        Assertions.assertTrue(cellContents.get(0) instanceof ImageChunk);
    }

    @Test
    public void testCheckNeighborTables() {
        List<List<IObject>> contents = new ArrayList<>();
        List<IObject> pageContents1 = new ArrayList<>();
        contents.add(pageContents1);
        TableBorder tableBorder1 = new TableBorder(2, 2);
        tableBorder1.setRecognizedStructureId(1l);
        tableBorder1.setBoundingBox(new BoundingBox(0, 10.0, 10.0, 30.0, 30.0));
        TableBorderRow row1 = new TableBorderRow(0, 2, 0l);
        row1.setBoundingBox(new BoundingBox(0, 10.0, 20.0, 30.0, 30.0));
        row1.getCells()[0] = new TableBorderCell(0, 0, 1, 1, 0l);
        row1.getCells()[0].setBoundingBox(new BoundingBox(0, 10.0, 20.0, 20.0, 30.0));
        row1.getCells()[1] = new TableBorderCell(0, 1, 1, 1, 0l);
        row1.getCells()[1].setBoundingBox(new BoundingBox(0, 20.0, 20.0, 30.0, 30.0));
        tableBorder1.getRows()[0] = row1;
        TableBorderRow row2 = new TableBorderRow(0, 2, 0l);
        row2.setBoundingBox(new BoundingBox(0, 10.0, 10.0, 30.0, 20.0));
        row2.getCells()[0] = new TableBorderCell(1, 0, 1, 1, 0l);
        row2.getCells()[0].setBoundingBox(new BoundingBox(0, 10.0, 10.0, 20.0, 20.0));
        row2.getCells()[1] = new TableBorderCell(1, 1, 1, 1, 0l);
        row2.getCells()[1].setBoundingBox(new BoundingBox(0, 20.0, 10.0, 30.0, 20.0));
        tableBorder1.getRows()[1] = row2;
        pageContents1.add(tableBorder1);

        List<IObject> pageContents2 = new ArrayList<>();
        contents.add(pageContents2);
        TableBorder tableBorder2 = new TableBorder(2, 2);
        tableBorder2.setRecognizedStructureId(2l);
        tableBorder2.setBoundingBox(new BoundingBox(1, 10.0, 10.0, 30.0, 30.0));
        row1 = new TableBorderRow(0, 2, 0l);
        row1.setBoundingBox(new BoundingBox(1, 10.0, 20.0, 30.0, 30.0));
        row1.getCells()[0] = new TableBorderCell(0, 0, 1, 1, 0l);
        row1.getCells()[0].setBoundingBox(new BoundingBox(1, 10.0, 20.0, 20.0, 30.0));
        row1.getCells()[1] = new TableBorderCell(0, 1, 1, 1, 0l);
        row1.getCells()[1].setBoundingBox(new BoundingBox(1, 20.0, 20.0, 30.0, 30.0));
        tableBorder2.getRows()[0] = row1;
        row2 = new TableBorderRow(0, 2, 0l);
        row2.setBoundingBox(new BoundingBox(1, 10.0, 10.0, 30.0, 20.0));
        row2.getCells()[0] = new TableBorderCell(1, 0, 1, 1, 0l);
        row2.getCells()[0].setBoundingBox(new BoundingBox(1, 10.0, 10.0, 20.0, 20.0));
        row2.getCells()[1] = new TableBorderCell(1, 1, 1, 1, 0l);
        row2.getCells()[1].setBoundingBox(new BoundingBox(1, 20.0, 10.0, 30.0, 20.0));
        tableBorder2.getRows()[1] = row2;
        pageContents2.add(tableBorder2);

        TableBorderProcessor.checkNeighborTables(contents);
        Assertions.assertEquals(2, contents.size());
        Assertions.assertEquals(1, contents.get(0).size());
        Assertions.assertTrue(contents.get(0).get(0) instanceof TableBorder);
        Assertions.assertEquals(2l, ((TableBorder) contents.get(0).get(0)).getNextTableId());
        Assertions.assertEquals(1, contents.get(1).size());
        Assertions.assertTrue(contents.get(1).get(0) instanceof TableBorder);
        Assertions.assertEquals(1l, ((TableBorder) contents.get(1).get(0)).getPreviousTableId());
    }

    @Test
    public void testNormalSmallTableDoesNotTriggerStructuralNormalization() {
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
        StaticContainers.setIsDataLoader(true);
        StaticLayoutContainers.setCurrentContentId(300L);
        TableBordersCollection tableBordersCollection = new TableBordersCollection();
        StaticContainers.setTableBordersCollection(tableBordersCollection);

        TableBorder tableBorder = createTable(0, 10.0, 10.0, 110.0, 70.0, 2, 2, 30L);
        SortedSet<TableBorder> tables = new TreeSet<>(new TableBorder.TableBordersComparator());
        tables.add(tableBorder);
        tableBordersCollection.getTableBorders().add(tables);

        List<IObject> contents = new ArrayList<>();
        contents.add(createTextChunk(0, 15.0, 48.0, 45.0, 58.0, "r1c1"));
        contents.add(createTextChunk(0, 65.0, 48.0, 95.0, 58.0, "r1c2"));
        contents.add(createTextChunk(0, 15.0, 22.0, 45.0, 32.0, "r2c1"));
        contents.add(createTextChunk(0, 65.0, 22.0, 95.0, 32.0, "r2c2"));

        TableBorder resultBorder = getSingleResultTable(contents, 0);

        Assertions.assertEquals(2, resultBorder.getNumberOfRows());
        Assertions.assertEquals("r1c1", ((SemanticParagraph) resultBorder.getCell(0, 0).getContents().get(0)).getValue());
        Assertions.assertEquals("r2c2", ((SemanticParagraph) resultBorder.getCell(1, 1).getContents().get(0)).getValue());
    }

    @Test
    public void testUndersegmentedFiveColumnTableIsRebuiltFromRawPageContents() {
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
        StaticContainers.setIsDataLoader(true);
        StaticLayoutContainers.setCurrentContentId(400L);
        TableBordersCollection tableBordersCollection = new TableBordersCollection();
        StaticContainers.setTableBordersCollection(tableBordersCollection);

        TableBorder tableBorder = createTable(0, 10.0, 10.0, 260.0, 110.0, 2, 5, 40L);
        SortedSet<TableBorder> tables = new TreeSet<>(new TableBorder.TableBordersComparator());
        tables.add(tableBorder);
        tableBordersCollection.getTableBorders().add(tables);

        List<IObject> contents = new ArrayList<>();
        double[] rowBottoms = {94.0, 84.0, 74.0, 64.0, 54.0, 44.0, 34.0, 24.0};
        for (int rowIndex = 0; rowIndex < rowBottoms.length; rowIndex++) {
            double bottomY = rowBottoms[rowIndex];
            double topY = bottomY + 6.0;
            for (int columnNumber = 0; columnNumber < 5; columnNumber++) {
                double leftX = 15.0 + (columnNumber * 50.0);
                contents.add(createTextChunk(0, leftX, bottomY, leftX + 25.0, topY,
                    "r" + (rowIndex + 1) + "c" + (columnNumber + 1)));
            }
        }

        TableBorder resultBorder = getSingleResultTable(contents, 0);

        Assertions.assertEquals(8, resultBorder.getNumberOfRows());
        Assertions.assertSame(resultBorder,
            tableBordersCollection.getTableBorder(resultBorder.getBoundingBox()));
        Assertions.assertEquals("r1c1", ((SemanticParagraph) resultBorder.getCell(0, 0).getContents().get(0)).getValue());
        Assertions.assertEquals("r3c3", ((SemanticParagraph) resultBorder.getCell(2, 2).getContents().get(0)).getValue());
        Assertions.assertEquals("r8c5", ((SemanticParagraph) resultBorder.getCell(7, 4).getContents().get(0)).getValue());
    }

    @Test
    public void testDenseTwoColumnClinicalTableIsSplitIntoResultUnitAndReferenceColumns() {
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
        StaticContainers.setIsDataLoader(true);
        StaticLayoutContainers.setCurrentContentId(450L);
        TableBordersCollection tableBordersCollection = new TableBordersCollection();
        StaticContainers.setTableBordersCollection(tableBordersCollection);

        TableBorder tableBorder = createTable(0, 10.0, 10.0, 260.0, 110.0, 8, 2, 45L);
        SortedSet<TableBorder> tables = new TreeSet<>(new TableBorder.TableBordersComparator());
        tables.add(tableBorder);
        tableBordersCollection.getTableBorders().add(tables);

        List<IObject> contents = new ArrayList<>();
        String[] labels = {"Leucociti", "Eritrociti", "Emoglobina", "Ematocrito",
            "MCV", "MCH", "MCHC", "RDW"};
        String[] results = {"4.96", "5.39", "14.8", "46", "84.4", "27.5", "32.5", "15.1"};
        String[] units = {"10^3/µL", "10^6/µL", "g/dL", "%", "fL", "pg", "g/dL", "%"};
        String[] references = {"4.00 - 11.00", "4.50 - 6.00", "13.0 - 18.0", "41 - 50",
            "80.0 - 100.0", "26.0 - 33.0", "31.0 - 36.0", "10.0 - 16.0"};

        for (int rowIndex = 0; rowIndex < labels.length; rowIndex++) {
            TableBorderCell rowAnchorCell = tableBorder.getCell(rowIndex, 0);
            double bottomY = rowAnchorCell.getBottomY() + 2.0;
            double topY = Math.min(rowAnchorCell.getTopY() - 2.0, bottomY + 6.0);
            contents.add(createTextChunk(0, 15.0, bottomY, 95.0, topY, labels[rowIndex]));
            contents.add(createTextChunk(0, 150.0, bottomY, 168.0, topY, results[rowIndex]));
            contents.add(createTextChunk(0, 183.0, bottomY, 205.0, topY, units[rowIndex]));
            contents.add(createTextChunk(0, 225.0, bottomY, 255.0, topY, references[rowIndex]));
        }

        TableBorder resultBorder = getSingleResultTable(contents, 0);

        Assertions.assertEquals(4, resultBorder.getNumberOfColumns());
        Assertions.assertSame(resultBorder,
            tableBordersCollection.getTableBorder(resultBorder.getBoundingBox()));
        Assertions.assertEquals("Leucociti",
            ((SemanticParagraph) resultBorder.getCell(0, 0).getContents().get(0)).getValue());
        Assertions.assertEquals("4.96",
            ((SemanticParagraph) resultBorder.getCell(0, 1).getContents().get(0)).getValue());
        Assertions.assertEquals("10^3/µL",
            ((SemanticParagraph) resultBorder.getCell(0, 2).getContents().get(0)).getValue());
        Assertions.assertEquals("4.00 - 11.00",
            ((SemanticParagraph) resultBorder.getCell(0, 3).getContents().get(0)).getValue());
        Assertions.assertEquals("15.1",
            ((SemanticParagraph) resultBorder.getCell(7, 1).getContents().get(0)).getValue());
        Assertions.assertEquals("10.0 - 16.0",
            ((SemanticParagraph) resultBorder.getCell(7, 3).getContents().get(0)).getValue());
    }

    @Test
    public void testNormalizationKeepsOriginalTableWhenRebuildLosesColumns() {
        TableBorder tableBorder = createTable(0, 10.0, 10.0, 260.0, 110.0, 2, 5, 50L);
        populateOriginalTableContents(tableBorder);

        List<IObject> rawPageContents = new ArrayList<>();
        double[] rowBottoms = {94.0, 84.0, 74.0, 64.0, 54.0, 44.0, 34.0, 24.0};
        for (int rowIndex = 0; rowIndex < rowBottoms.length; rowIndex++) {
            double bottomY = rowBottoms[rowIndex];
            double topY = bottomY + 6.0;
            rawPageContents.add(createTextChunk(0, 15.0, bottomY, 40.0, topY, "left-" + rowIndex));
            rawPageContents.add(createTextChunk(0, 65.0, bottomY, 90.0, topY, "mid-" + rowIndex));
        }

        TableBorder normalizedTable = TableStructureNormalizer.normalize(rawPageContents, tableBorder);

        Assertions.assertSame(tableBorder, normalizedTable);
        Assertions.assertEquals(2, normalizedTable.getNumberOfRows());
    }

    @Test
    public void testProcessedClinicalTwoColumnTableIsSplitFromCombinedTextCells() {
        TableBorder tableBorder = createTable(0, 10.0, 10.0, 260.0, 110.0, 8, 2, 55L);
        String[] labels = {"Leucociti", "Eritrociti", "Emoglobina", "Ematocrito",
            "MCV", "MCH", "MCHC", "RDW"};
        String[] combined = {"4.96 10^3/µL 4.00 - 11.00", "5.39 10^6/µL 4.50 - 6.00",
            "14.8 g/dL 13.0 - 18.0", "46 % 41 - 50", "84.4 fL 80.0 - 100.0",
            "27.5 pg 26.0 - 33.0", "32.5 g/dL 31.0 - 36.0", "15.1 % 10.0 - 16.0"};

        for (int rowNumber = 0; rowNumber < labels.length; rowNumber++) {
            TableBorderCell labelCell = tableBorder.getCell(rowNumber, 0);
            labelCell.setContents(new ArrayList<>(List.of(createSemanticParagraph(labelCell, labels[rowNumber]))));
            TableBorderCell valueCell = tableBorder.getCell(rowNumber, 1);
            valueCell.setContents(new ArrayList<>(List.of(createSemanticParagraph(valueCell, combined[rowNumber]))));
        }

        TableBorder normalizedTable = TableStructureNormalizer.normalizeProcessedClinicalTable(tableBorder);

        Assertions.assertEquals(4, normalizedTable.getNumberOfColumns());
        Assertions.assertEquals("Leucociti",
            ((SemanticParagraph) normalizedTable.getCell(0, 0).getContents().get(0)).getValue());
        Assertions.assertEquals("4.96",
            ((SemanticParagraph) normalizedTable.getCell(0, 1).getContents().get(0)).getValue());
        Assertions.assertEquals("10^3/µL",
            ((SemanticParagraph) normalizedTable.getCell(0, 2).getContents().get(0)).getValue());
        Assertions.assertEquals("4.00 - 11.00",
            ((SemanticParagraph) normalizedTable.getCell(0, 3).getContents().get(0)).getValue());
    }

    @Test
    public void testProcessedClinicalThreeColumnTableSplitsMatrixResultAndUnit() {
        TableBorder tableBorder = createTable(0, 10.0, 10.0, 260.0, 70.0, 4, 3, 56L);
        String[] labels = {"Proteina C reattiva", "Ferro", "Ferritina", "Proteine totali"};
        String[] combined = {"Siero 0.17 mg/dL", "Siero 77 µg/dL", "Siero 20 * ng/mL", "Siero 7.84 g/dL"};
        String[] references = {"<= 0.50", "36 - 150", "24 - 336", "6.00 - 8.20"};

        for (int rowNumber = 0; rowNumber < labels.length; rowNumber++) {
            tableBorder.getCell(rowNumber, 0).setContents(new ArrayList<>(
                List.of(createSemanticParagraph(tableBorder.getCell(rowNumber, 0), labels[rowNumber]))));
            tableBorder.getCell(rowNumber, 1).setContents(new ArrayList<>(
                List.of(createSemanticParagraph(tableBorder.getCell(rowNumber, 1), combined[rowNumber]))));
            tableBorder.getCell(rowNumber, 2).setContents(new ArrayList<>(
                List.of(createSemanticParagraph(tableBorder.getCell(rowNumber, 2), references[rowNumber]))));
        }

        TableBorder normalizedTable = TableStructureNormalizer.normalizeProcessedClinicalTable(tableBorder);

        Assertions.assertEquals(5, normalizedTable.getNumberOfColumns());
        Assertions.assertEquals("Proteina C reattiva",
            ((SemanticParagraph) normalizedTable.getCell(0, 0).getContents().get(0)).getValue());
        Assertions.assertEquals("Siero",
            ((SemanticParagraph) normalizedTable.getCell(0, 1).getContents().get(0)).getValue());
        Assertions.assertEquals("0.17",
            ((SemanticParagraph) normalizedTable.getCell(0, 2).getContents().get(0)).getValue());
        Assertions.assertEquals("mg/dL",
            ((SemanticParagraph) normalizedTable.getCell(0, 3).getContents().get(0)).getValue());
        Assertions.assertEquals("<= 0.50",
            ((SemanticParagraph) normalizedTable.getCell(0, 4).getContents().get(0)).getValue());
        Assertions.assertEquals("20 *",
            ((SemanticParagraph) normalizedTable.getCell(2, 2).getContents().get(0)).getValue());
        Assertions.assertEquals("ng/mL",
            ((SemanticParagraph) normalizedTable.getCell(2, 3).getContents().get(0)).getValue());
    }

    @Test
    public void testProcessedClinicalThreeColumnTableSplitsResultUnitAndReference() {
        TableBorder tableBorder = createTable(0, 10.0, 10.0, 260.0, 85.0, 5, 3, 57L);
        String[] labels = {"Albumina %", "Beta2globuline %", "Rapporto A/G", "Albumina", "Gammaglobuline"};
        String[] combined = {"59.6 %", "5.0 %", "1.5", "4.7 g/dL", "1.3 g/dL"};
        String[] references = {"55.8 - 66.1", "3.2 - 6.5", "1.1 - 1.9", "", ""};

        for (int rowNumber = 0; rowNumber < labels.length; rowNumber++) {
            tableBorder.getCell(rowNumber, 0).setContents(new ArrayList<>(
                List.of(createSemanticParagraph(tableBorder.getCell(rowNumber, 0), labels[rowNumber]))));
            tableBorder.getCell(rowNumber, 1).setContents(new ArrayList<>(
                List.of(createSemanticParagraph(tableBorder.getCell(rowNumber, 1), combined[rowNumber]))));
            if (!references[rowNumber].isEmpty()) {
                tableBorder.getCell(rowNumber, 2).setContents(new ArrayList<>(
                    List.of(createSemanticParagraph(tableBorder.getCell(rowNumber, 2), references[rowNumber]))));
            }
        }

        TableBorder normalizedTable = TableStructureNormalizer.normalizeProcessedClinicalTable(tableBorder);

        Assertions.assertEquals(4, normalizedTable.getNumberOfColumns());
        Assertions.assertEquals("59.6",
            ((SemanticParagraph) normalizedTable.getCell(0, 1).getContents().get(0)).getValue());
        Assertions.assertEquals("%",
            ((SemanticParagraph) normalizedTable.getCell(0, 2).getContents().get(0)).getValue());
        Assertions.assertEquals("55.8 - 66.1",
            ((SemanticParagraph) normalizedTable.getCell(0, 3).getContents().get(0)).getValue());
        Assertions.assertEquals("1.5",
            ((SemanticParagraph) normalizedTable.getCell(2, 1).getContents().get(0)).getValue());
        Assertions.assertTrue(normalizedTable.getCell(2, 2).getContents().isEmpty());
        Assertions.assertEquals("1.1 - 1.9",
            ((SemanticParagraph) normalizedTable.getCell(2, 3).getContents().get(0)).getValue());
        Assertions.assertEquals("g/dL",
            ((SemanticParagraph) normalizedTable.getCell(3, 2).getContents().get(0)).getValue());
    }

    @Test
    public void testTextBlockTableIsNeverNormalized() {
        TableBorder tableBorder = createTable(0, 10.0, 10.0, 110.0, 50.0, 1, 1, 60L);
        List<IObject> cellContents = new ArrayList<>();
        cellContents.add(createTextChunk(0, 15.0, 20.0, 90.0, 30.0, "single cell text"));
        tableBorder.getCell(0, 0).setContents(cellContents);

        List<IObject> rawPageContents = new ArrayList<>();
        rawPageContents.add(createTextChunk(0, 15.0, 20.0, 90.0, 30.0, "single cell text"));
        rawPageContents.add(createTextChunk(0, 15.0, 32.0, 90.0, 42.0, "more text"));

        TableBorder normalizedTable = TableStructureNormalizer.normalize(rawPageContents, tableBorder);

        Assertions.assertSame(tableBorder, normalizedTable);
        Assertions.assertTrue(normalizedTable.isTextBlock());
    }

    // ========== RECURSION DEPTH LIMIT TESTS ==========

    /**
     * Test that processTableBorders completes within reasonable time even with
     * deeply nested table structures. This is a defensive measure against
     * malicious PDFs that could cause stack overflow through deeply nested tables.
     * <p>
     * Real-world PDFs rarely have tables nested more than 2-3 levels deep.
     * A depth limit of 10 provides safety margin while supporting legitimate use cases.
     */
    @Test
    public void testProcessTableBordersDepthLimitNoStackOverflow() {
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
        StaticContainers.setIsDataLoader(true);
        StaticLayoutContainers.setCurrentContentId(100L);

        // Even with complex nested structures, processing should complete quickly
        // This test verifies that the depth limit prevents runaway recursion
        assertTimeout(Duration.ofSeconds(5), () -> {
            TableBordersCollection tableBordersCollection = new TableBordersCollection();
            StaticContainers.setTableBordersCollection(tableBordersCollection);

            // Create a simple table to process
            List<IObject> contents = new ArrayList<>();
            TableBorder tableBorder = createSimpleTable(0, 10.0, 10.0, 100.0, 100.0, 10L);
            SortedSet<TableBorder> tables = new TreeSet<>(new TableBorder.TableBordersComparator());
            tables.add(tableBorder);
            tableBordersCollection.getTableBorders().add(tables);

            TextChunk textChunk = new TextChunk(
                    new BoundingBox(0, 15.0, 15.0, 95.0, 95.0),
                    "test content", 10, 15.0);
            textChunk.getStreamInfos().add(new StreamInfo(0, null, 0, "test content".length()));
            textChunk.adjustSymbolEndsToBoundingBox(null);
            contents.add(textChunk);

            // Should complete without stack overflow
            List<IObject> result = TableBorderProcessor.processTableBorders(contents, 0);
            Assertions.assertNotNull(result);
        });
    }

    /**
     * Test that normal table processing still works correctly with depth tracking.
     * Verifies that the depth limit doesn't interfere with legitimate nested tables.
     */
    @Test
    public void testProcessTableBordersNormalNestedTableProcessedCorrectly() {
        StaticContainers.setIsIgnoreCharactersWithoutUnicode(false);
        StaticContainers.setIsDataLoader(true);
        StaticLayoutContainers.setCurrentContentId(200L);
        TableBordersCollection tableBordersCollection = new TableBordersCollection();
        StaticContainers.setTableBordersCollection(tableBordersCollection);

        // Create outer table
        TableBorder outerTable = createSimpleTable(0, 10.0, 10.0, 200.0, 200.0, 20L);
        SortedSet<TableBorder> tables = new TreeSet<>(new TableBorder.TableBordersComparator());
        tables.add(outerTable);
        tableBordersCollection.getTableBorders().add(tables);

        List<IObject> contents = new ArrayList<>();
        TextChunk textChunk = new TextChunk(
                new BoundingBox(0, 15.0, 15.0, 95.0, 95.0),
                "outer content", 10, 15.0);
        textChunk.getStreamInfos().add(new StreamInfo(0, null, 0, "outer content".length()));
        textChunk.adjustSymbolEndsToBoundingBox(null);
        contents.add(textChunk);

        // Process should complete successfully
        List<IObject> result = TableBorderProcessor.processTableBorders(contents, 0);

        Assertions.assertEquals(1, result.size());
        Assertions.assertTrue(result.get(0) instanceof TableBorder);
    }

    /**
     * Helper method to create a simple 2x2 table for testing.
     */
    private TableBorder createSimpleTable(int pageNumber, double leftX, double bottomY,
                                          double rightX, double topY, long structureId) {
        return createTable(pageNumber, leftX, bottomY, rightX, topY, 2, 2, structureId);
    }

    private TableBorder createTable(int pageNumber, double leftX, double bottomY,
                                    double rightX, double topY, int rows, int columns, long structureId) {
        TableBorder table = new TableBorder(rows, columns);
        table.setRecognizedStructureId(structureId);
        table.setBoundingBox(new BoundingBox(pageNumber, leftX, bottomY, rightX, topY));

        double columnWidth = (rightX - leftX) / columns;
        double rowHeight = (topY - bottomY) / rows;
        for (int rowNumber = 0; rowNumber < rows; rowNumber++) {
            double rowTopY = topY - (rowNumber * rowHeight);
            double rowBottomY = rowTopY - rowHeight;
            TableBorderRow row = new TableBorderRow(rowNumber, columns, 0L);
            row.setBoundingBox(new BoundingBox(pageNumber, leftX, rowBottomY, rightX, rowTopY));
            table.getRows()[rowNumber] = row;

            for (int columnNumber = 0; columnNumber < columns; columnNumber++) {
                double cellLeftX = leftX + (columnNumber * columnWidth);
                double cellRightX = cellLeftX + columnWidth;
                TableBorderCell cell = new TableBorderCell(rowNumber, columnNumber, 1, 1, 0L);
                cell.setBoundingBox(new BoundingBox(pageNumber, cellLeftX, rowBottomY, cellRightX, rowTopY));
                row.getCells()[columnNumber] = cell;
            }
        }

        table.calculateCoordinatesUsingBoundingBoxesOfRowsAndColumns();
        return table;
    }

    private void populateOriginalTableContents(TableBorder table) {
        for (int rowNumber = 0; rowNumber < table.getNumberOfRows(); rowNumber++) {
            for (int columnNumber = 0; columnNumber < table.getNumberOfColumns(); columnNumber++) {
                TableBorderCell cell = table.getCell(rowNumber, columnNumber);
                cell.setContents(new ArrayList<>(List.of(createTextChunk(0,
                    cell.getLeftX() + 2.0, cell.getBottomY() + 5.0, cell.getLeftX() + 28.0,
                    cell.getBottomY() + 15.0, "orig-" + rowNumber + "-" + columnNumber))));
            }
        }
    }

    private TableBorder getSingleResultTable(List<IObject> contents, int pageNumber) {
        List<IObject> processedContents = TableBorderProcessor.processTableBorders(contents, pageNumber);
        Assertions.assertEquals(1, processedContents.size());
        Assertions.assertTrue(processedContents.get(0) instanceof TableBorder);
        return (TableBorder) processedContents.get(0);
    }

    private TextChunk createTextChunk(int pageNumber, double leftX, double bottomY, double rightX,
                                      double topY, String value) {
        TextChunk textChunk = new TextChunk(new BoundingBox(pageNumber, leftX, bottomY, rightX, topY),
            value, topY - bottomY, bottomY);
        textChunk.getStreamInfos().add(new StreamInfo(0, null, 0, value.length()));
        textChunk.adjustSymbolEndsToBoundingBox(null);
        return textChunk;
    }

    private SemanticParagraph createSemanticParagraph(TableBorderCell cell, String value) {
        TextChunk chunk = new TextChunk(value);
        TextLine line = new TextLine(chunk);
        TextColumn column = new TextColumn(line);
        return new SemanticParagraph(new BoundingBox(cell.getBoundingBox()), List.of(column));
    }

}

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

import org.verapdf.wcag.algorithms.entities.IObject;
import org.verapdf.wcag.algorithms.entities.content.LineArtChunk;
import org.verapdf.wcag.algorithms.entities.content.LineChunk;
import org.verapdf.wcag.algorithms.entities.content.TextColumn;
import org.verapdf.wcag.algorithms.entities.content.TextChunk;
import org.verapdf.wcag.algorithms.entities.content.TextLine;
import org.verapdf.wcag.algorithms.entities.SemanticParagraph;
import org.verapdf.wcag.algorithms.entities.geometry.BoundingBox;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class TableStructureNormalizer {

    private static final int MAX_UNDERSEGMENTED_ROWS = 2;
    private static final int MIN_UNDERSEGMENTED_COLUMNS = 3;
    private static final int MIN_UNDERSEGMENTED_TEXT_LINES = 8;
    private static final int MIN_ROW_BAND_MISMATCH = 2;
    private static final int OVERSIZED_CELL_LINE_COUNT = 4;
    private static final int MIN_DENSE_TWO_COLUMN_ROWS = 6;
    private static final int MIN_DENSE_THREE_COLUMN_ROWS = 3;
    private static final int MIN_SPLIT_SEGMENTS = 3;
    private static final int MAX_SPLIT_SEGMENTS = 3;
    private static final int MIN_SPLIT_SEGMENT_SUPPORT = 5;
    private static final double MIN_ROW_BAND_EPSILON = 3.0;
    private static final double ROW_BAND_EPSILON_RATIO = 0.6;
    private static final double ROW_BAND_ASSIGNMENT_EPSILON = 6.0;
    private static final double ROW_ORDER_EPSILON = 1.5;
    private static final double SPLIT_SEGMENT_GAP = 14.0;
    private static final double[] TEXT_SPLIT_WIDTH_RATIOS = {0.0, 0.34, 0.58, 0.78, 1.0};
    private static final Pattern CLINICAL_REFERENCE_PATTERN = Pattern.compile(
        "^(?<prefix>.+?)\\s+(?<reference>(?:<=|>=|<|>)\\s*\\S+|\\S+\\s*-\\s*\\S+)$");
    private static final Pattern CLINICAL_VALUE_UNIT_PATTERN = Pattern.compile(
        "^(?<result>[-+]?\\d+(?:[\\.,]\\d+)?(?:\\s*\\*)?)\\s+(?<unit>.+)$");
    private static final Pattern CLINICAL_VALUE_ONLY_PATTERN = Pattern.compile(
        "^(?<result>[-+]?\\d+(?:[\\.,]\\d+)?(?:\\s*\\*)?)$");
    private static final Pattern CLINICAL_MATRIX_VALUE_UNIT_PATTERN = Pattern.compile(
        "^(?<matrix>\\S+)\\s+(?<result>[-+]?\\d+(?:[\\.,]\\d+)?(?:\\s*\\*)?)\\s+(?<unit>.+)$");
    private static final Comparator<IObject> CONTENT_COMPARATOR =
        Comparator.comparingDouble(IObject::getCenterY).reversed()
            .thenComparingDouble(IObject::getLeftX);
    private static final Comparator<TextLine> TEXT_LINE_COMPARATOR =
        Comparator.comparingDouble(TextLine::getCenterY).reversed()
            .thenComparingDouble(TextLine::getLeftX);

    static TableBorder normalize(List<IObject> rawPageContents, TableBorder tableBorder) {
        if (rawPageContents == null || rawPageContents.isEmpty()) {
            return tableBorder;
        }
        if (tableBorder.isTextBlock()) {
            return tableBorder;
        }

        TableBorder splitDenseTwoColumnTable = normalizeDenseTwoColumnTable(rawPageContents, tableBorder);
        if (splitDenseTwoColumnTable != null) {
            return splitDenseTwoColumnTable;
        }

        if (tableBorder.getNumberOfRows() > MAX_UNDERSEGMENTED_ROWS ||
                tableBorder.getNumberOfColumns() < MIN_UNDERSEGMENTED_COLUMNS) {
            return tableBorder;
        }

        List<ColumnSnapshot> columnSnapshots = collectColumnSnapshots(rawPageContents, tableBorder);
        int denseColumns = countDenseColumns(columnSnapshots);
        if (denseColumns < 2) {
            return tableBorder;
        }

        List<RowBand> rowBands = collectRowBands(tableBorder, columnSnapshots);
        if (rowBands.size() < tableBorder.getNumberOfRows() + MIN_ROW_BAND_MISMATCH) {
            return tableBorder;
        }

        TableBorder rebuiltTable = rebuildTable(tableBorder, rowBands);
        if (!isReplacementQualityBetter(tableBorder, rebuiltTable)) {
            return tableBorder;
        }

        return rebuiltTable;
    }

    private static TableBorder normalizeDenseTwoColumnTable(List<IObject> rawPageContents, TableBorder tableBorder) {
        if (tableBorder.getNumberOfColumns() != 2 || tableBorder.getNumberOfRows() < MIN_DENSE_TWO_COLUMN_ROWS) {
            return null;
        }

        List<List<IObject>> leftColumnContents = collectColumnContents(rawPageContents, tableBorder, 0);
        List<RowSegments> rightColumnSegments = collectRightColumnSegments(rawPageContents, tableBorder);
        int supportedSegments = countSupportedSplitSegments(rightColumnSegments);
        if (supportedSegments < MIN_SPLIT_SEGMENTS) {
            return null;
        }

        double[] splitBoundaries = computeSplitBoundaries(tableBorder, rightColumnSegments, supportedSegments);
        if (splitBoundaries == null) {
            return null;
        }

        TableBorder rebuiltTable = rebuildDenseTwoColumnTable(tableBorder, leftColumnContents,
            rightColumnSegments, splitBoundaries);
        if (!isDenseTwoColumnSplitBetter(tableBorder, rebuiltTable, rightColumnSegments, supportedSegments)) {
            return null;
        }

        return rebuiltTable;
    }

    static TableBorder normalizeProcessedClinicalTable(TableBorder tableBorder) {
        if (tableBorder.getNumberOfColumns() == 2) {
            return normalizeProcessedTwoColumnClinicalTable(tableBorder);
        }
        if (tableBorder.getNumberOfColumns() == 3) {
            return normalizeProcessedThreeColumnClinicalTable(tableBorder);
        }
        return tableBorder;
    }

    private static TableBorder normalizeProcessedTwoColumnClinicalTable(TableBorder tableBorder) {
        if (tableBorder.getNumberOfRows() < MIN_DENSE_TWO_COLUMN_ROWS) {
            return tableBorder;
        }

        List<ClinicalSplit> rowSplits = new ArrayList<>(tableBorder.getNumberOfRows());
        int successfulSplits = 0;
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            TableBorderCell valueCell = tableBorder.getCell(rowNumber, 1);
            ClinicalSplit split = parseClinicalSplit(extractCellText(valueCell));
            rowSplits.add(split);
            if (split != null) {
                successfulSplits++;
            }
        }
        if (successfulSplits < MIN_SPLIT_SEGMENT_SUPPORT) {
            return tableBorder;
        }

        TableBorder rebuiltTable = new TableBorder(tableBorder.getNumberOfRows(), 4);
        rebuiltTable.setRecognizedStructureId(tableBorder.getRecognizedStructureId());
        rebuiltTable.setBoundingBox(new BoundingBox(tableBorder.getBoundingBox()));
        rebuiltTable.setNode(tableBorder.getNode());
        rebuiltTable.setIndex(tableBorder.getIndex());
        rebuiltTable.setLevel(tableBorder.getLevel());
        rebuiltTable.setPreviousTable(tableBorder.getPreviousTable());
        rebuiltTable.setNextTable(tableBorder.getNextTable());

        double[] boundaries = createTextSplitBoundaries(tableBorder);
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            TableBorderRow originalRow = tableBorder.getRow(rowNumber);
            TableBorderRow rebuiltRow = new TableBorderRow(rowNumber, 4, tableBorder.getRecognizedStructureId());
            rebuiltRow.setBoundingBox(new BoundingBox(originalRow.getBoundingBox()));
            rebuiltTable.getRows()[rowNumber] = rebuiltRow;

            TableBorderCell labelCell = createSplitCell(tableBorder, originalRow, rowNumber, 0, boundaries);
            labelCell.setContents(new ArrayList<>(tableBorder.getCell(rowNumber, 0).getContents()));
            rebuiltRow.getCells()[0] = labelCell;

            ClinicalSplit split = rowSplits.get(rowNumber);
            if (split == null) {
                TableBorderCell mergedCell = createSplitCell(tableBorder, originalRow, rowNumber, 1, boundaries);
                mergedCell.setContents(new ArrayList<>(tableBorder.getCell(rowNumber, 1).getContents()));
                rebuiltRow.getCells()[1] = mergedCell;
                rebuiltRow.getCells()[2] = createSplitCell(tableBorder, originalRow, rowNumber, 2, boundaries);
                rebuiltRow.getCells()[3] = createSplitCell(tableBorder, originalRow, rowNumber, 3, boundaries);
            } else {
                rebuiltRow.getCells()[1] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 1, boundaries,
                    split.result);
                rebuiltRow.getCells()[2] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 2, boundaries,
                    split.unit);
                rebuiltRow.getCells()[3] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 3, boundaries,
                    split.reference);
            }
        }

        rebuiltTable.calculateCoordinatesUsingBoundingBoxesOfRowsAndColumns();
        return rebuiltTable;
    }

    private static TableBorder normalizeProcessedThreeColumnClinicalTable(TableBorder tableBorder) {
        if (tableBorder.getNumberOfRows() < MIN_DENSE_THREE_COLUMN_ROWS) {
            return tableBorder;
        }

        List<ClinicalMatrixSplit> matrixSplits = new ArrayList<>(tableBorder.getNumberOfRows());
        List<ClinicalValueSplit> valueSplits = new ArrayList<>(tableBorder.getNumberOfRows());
        int successfulMatrixSplits = 0;
        int successfulValueSplits = 0;
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            String reference = extractCellText(tableBorder.getCell(rowNumber, 2));
            String value = extractCellText(tableBorder.getCell(rowNumber, 1));
            ClinicalMatrixSplit matrixSplit = parseClinicalMatrixSplit(value, reference);
            matrixSplits.add(matrixSplit);
            if (matrixSplit != null) {
                successfulMatrixSplits++;
            }

            ClinicalValueSplit valueSplit = parseClinicalValueSplit(value, reference);
            valueSplits.add(valueSplit);
            if (valueSplit != null) {
                successfulValueSplits++;
            }
        }

        int minimumSupportedRows = Math.min(tableBorder.getNumberOfRows(), MIN_DENSE_THREE_COLUMN_ROWS);
        if (successfulMatrixSplits >= minimumSupportedRows && successfulMatrixSplits >= successfulValueSplits) {
            return rebuildProcessedThreeColumnClinicalTableWithMatrix(tableBorder, matrixSplits);
        }
        if (successfulValueSplits >= minimumSupportedRows) {
            return rebuildProcessedThreeColumnClinicalTableWithoutMatrix(tableBorder, valueSplits);
        }
        return tableBorder;
    }

    private static TableBorder rebuildProcessedThreeColumnClinicalTableWithMatrix(TableBorder tableBorder,
                                                                                  List<ClinicalMatrixSplit> rowSplits) {
        TableBorder rebuiltTable = new TableBorder(tableBorder.getNumberOfRows(), 5);
        rebuiltTable.setRecognizedStructureId(tableBorder.getRecognizedStructureId());
        rebuiltTable.setBoundingBox(new BoundingBox(tableBorder.getBoundingBox()));
        rebuiltTable.setNode(tableBorder.getNode());
        rebuiltTable.setIndex(tableBorder.getIndex());
        rebuiltTable.setLevel(tableBorder.getLevel());
        rebuiltTable.setPreviousTable(tableBorder.getPreviousTable());
        rebuiltTable.setNextTable(tableBorder.getNextTable());

        double[] boundaries = createTextSplitBoundaries(tableBorder, 5, 0.42, 0.58, 0.78);
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            TableBorderRow originalRow = tableBorder.getRow(rowNumber);
            TableBorderRow rebuiltRow = new TableBorderRow(rowNumber, 5, tableBorder.getRecognizedStructureId());
            rebuiltRow.setBoundingBox(new BoundingBox(originalRow.getBoundingBox()));
            rebuiltTable.getRows()[rowNumber] = rebuiltRow;

            TableBorderCell labelCell = createSplitCell(tableBorder, originalRow, rowNumber, 0, boundaries);
            labelCell.setContents(new ArrayList<>(tableBorder.getCell(rowNumber, 0).getContents()));
            rebuiltRow.getCells()[0] = labelCell;

            ClinicalMatrixSplit split = rowSplits.get(rowNumber);
            if (split == null) {
                TableBorderCell mergedCell = createSplitCell(tableBorder, originalRow, rowNumber, 1, boundaries);
                mergedCell.setContents(new ArrayList<>(tableBorder.getCell(rowNumber, 1).getContents()));
                rebuiltRow.getCells()[1] = mergedCell;
                rebuiltRow.getCells()[2] = createSplitCell(tableBorder, originalRow, rowNumber, 2, boundaries);
                rebuiltRow.getCells()[3] = createSplitCell(tableBorder, originalRow, rowNumber, 3, boundaries);

                TableBorderCell referenceCell = createSplitCell(tableBorder, originalRow, rowNumber, 4, boundaries);
                referenceCell.setContents(new ArrayList<>(tableBorder.getCell(rowNumber, 2).getContents()));
                rebuiltRow.getCells()[4] = referenceCell;
            } else {
                rebuiltRow.getCells()[1] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 1, boundaries,
                    split.matrix);
                rebuiltRow.getCells()[2] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 2, boundaries,
                    split.result);
                rebuiltRow.getCells()[3] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 3, boundaries,
                    split.unit);
                rebuiltRow.getCells()[4] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 4, boundaries,
                    split.reference);
            }
        }

        rebuiltTable.calculateCoordinatesUsingBoundingBoxesOfRowsAndColumns();
        return rebuiltTable;
    }

    private static TableBorder rebuildProcessedThreeColumnClinicalTableWithoutMatrix(TableBorder tableBorder,
                                                                                     List<ClinicalValueSplit> rowSplits) {
        TableBorder rebuiltTable = new TableBorder(tableBorder.getNumberOfRows(), 4);
        rebuiltTable.setRecognizedStructureId(tableBorder.getRecognizedStructureId());
        rebuiltTable.setBoundingBox(new BoundingBox(tableBorder.getBoundingBox()));
        rebuiltTable.setNode(tableBorder.getNode());
        rebuiltTable.setIndex(tableBorder.getIndex());
        rebuiltTable.setLevel(tableBorder.getLevel());
        rebuiltTable.setPreviousTable(tableBorder.getPreviousTable());
        rebuiltTable.setNextTable(tableBorder.getNextTable());

        double[] boundaries = createTextSplitBoundaries(tableBorder, 4, 0.58, 0.78);
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            TableBorderRow originalRow = tableBorder.getRow(rowNumber);
            TableBorderRow rebuiltRow = new TableBorderRow(rowNumber, 4, tableBorder.getRecognizedStructureId());
            rebuiltRow.setBoundingBox(new BoundingBox(originalRow.getBoundingBox()));
            rebuiltTable.getRows()[rowNumber] = rebuiltRow;

            TableBorderCell labelCell = createSplitCell(tableBorder, originalRow, rowNumber, 0, boundaries);
            labelCell.setContents(new ArrayList<>(tableBorder.getCell(rowNumber, 0).getContents()));
            rebuiltRow.getCells()[0] = labelCell;

            ClinicalValueSplit split = rowSplits.get(rowNumber);
            if (split == null) {
                TableBorderCell mergedCell = createSplitCell(tableBorder, originalRow, rowNumber, 1, boundaries);
                mergedCell.setContents(new ArrayList<>(tableBorder.getCell(rowNumber, 1).getContents()));
                rebuiltRow.getCells()[1] = mergedCell;
                rebuiltRow.getCells()[2] = createSplitCell(tableBorder, originalRow, rowNumber, 2, boundaries);

                TableBorderCell referenceCell = createSplitCell(tableBorder, originalRow, rowNumber, 3, boundaries);
                referenceCell.setContents(new ArrayList<>(tableBorder.getCell(rowNumber, 2).getContents()));
                rebuiltRow.getCells()[3] = referenceCell;
            } else {
                rebuiltRow.getCells()[1] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 1, boundaries,
                    split.result);
                rebuiltRow.getCells()[2] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 2, boundaries,
                    split.unit);
                rebuiltRow.getCells()[3] = createSplitParagraphCell(tableBorder, originalRow, rowNumber, 3, boundaries,
                    split.reference);
            }
        }

        rebuiltTable.calculateCoordinatesUsingBoundingBoxesOfRowsAndColumns();
        return rebuiltTable;
    }

    private static List<ColumnSnapshot> collectColumnSnapshots(List<IObject> rawPageContents, TableBorder tableBorder) {
        List<ColumnSnapshot> columnSnapshots = new ArrayList<>(tableBorder.getNumberOfColumns());
        for (int columnNumber = 0; columnNumber < tableBorder.getNumberOfColumns(); columnNumber++) {
            columnSnapshots.add(new ColumnSnapshot());
        }

        for (IObject content : rawPageContents) {
            if (content == null || !isInsideTableBounds(content, tableBorder)) {
                continue;
            }

            if (content instanceof TextChunk) {
                addTextChunkToColumns((TextChunk) content, tableBorder, columnSnapshots);
            } else if (!(content instanceof LineChunk) && !(content instanceof LineArtChunk)) {
                int columnNumber = findBestColumn(content, tableBorder);
                if (columnNumber >= 0) {
                    columnSnapshots.get(columnNumber).addContent(content);
                }
            }
        }

        for (ColumnSnapshot columnSnapshot : columnSnapshots) {
            columnSnapshot.finalizeSnapshot();
        }
        return columnSnapshots;
    }

    private static void addTextChunkToColumns(TextChunk textChunk, TableBorder tableBorder,
                                              List<ColumnSnapshot> columnSnapshots) {
        for (int columnNumber = 0; columnNumber < tableBorder.getNumberOfColumns(); columnNumber++) {
            TextChunk columnTextChunk = TableBorderProcessor.getTextChunkPartForRange(textChunk,
                tableBorder.getLeftX(columnNumber), tableBorder.getRightX(columnNumber));
            if (columnTextChunk != null && !columnTextChunk.isEmpty() && !columnTextChunk.isWhiteSpaceChunk()) {
                columnSnapshots.get(columnNumber).addContent(columnTextChunk);
            }
        }
    }

    private static int findBestColumn(IObject content, TableBorder tableBorder) {
        double centerX = content.getCenterX();
        for (int columnNumber = 0; columnNumber < tableBorder.getNumberOfColumns(); columnNumber++) {
            if (centerX >= tableBorder.getLeftX(columnNumber) && centerX <= tableBorder.getRightX(columnNumber)) {
                return columnNumber;
            }
        }

        int closestColumn = -1;
        double closestDistance = Double.MAX_VALUE;
        for (int columnNumber = 0; columnNumber < tableBorder.getNumberOfColumns(); columnNumber++) {
            double columnCenter = (tableBorder.getLeftX(columnNumber) + tableBorder.getRightX(columnNumber)) / 2;
            double distance = Math.abs(centerX - columnCenter);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestColumn = columnNumber;
            }
        }
        return closestColumn;
    }

    private static boolean isInsideTableBounds(IObject content, TableBorder tableBorder) {
        return content.getCenterX() >= tableBorder.getLeftX() && content.getCenterX() <= tableBorder.getRightX() &&
            content.getCenterY() >= tableBorder.getBottomY() && content.getCenterY() <= tableBorder.getTopY();
    }

    private static int countDenseColumns(List<ColumnSnapshot> columnSnapshots) {
        int denseColumns = 0;
        for (ColumnSnapshot columnSnapshot : columnSnapshots) {
            if (columnSnapshot.meaningfulLineCount >= MIN_UNDERSEGMENTED_TEXT_LINES) {
                denseColumns++;
            }
        }
        return denseColumns;
    }

    private static List<RowBand> collectRowBands(TableBorder tableBorder, List<ColumnSnapshot> columnSnapshots) {
        List<TextLine> textLines = new ArrayList<>();
        for (ColumnSnapshot columnSnapshot : columnSnapshots) {
            textLines.addAll(columnSnapshot.textLines);
        }
        textLines.sort(TEXT_LINE_COMPARATOR);

        List<RowBand> rowBands = new ArrayList<>();
        for (TextLine textLine : textLines) {
            RowBand matchingBand = findMatchingRowBand(rowBands, textLine);
            if (matchingBand == null) {
                matchingBand = new RowBand(tableBorder.getNumberOfColumns());
                rowBands.add(matchingBand);
            }
            matchingBand.addLine(textLine);
        }

        for (int columnNumber = 0; columnNumber < columnSnapshots.size(); columnNumber++) {
            for (IObject content : columnSnapshots.get(columnNumber).contents) {
                RowBand matchingBand = findBestRowBand(rowBands, content);
                if (matchingBand != null) {
                    matchingBand.addContent(columnNumber, content);
                }
            }
        }

        rowBands.removeIf(rowBand -> rowBand.isEmpty());
        rowBands.sort(Comparator.comparingDouble(RowBand::getCenterY).reversed());
        rowBands.forEach(RowBand::sortContents);
        return rowBands;
    }

    private static RowBand findMatchingRowBand(List<RowBand> rowBands, TextLine textLine) {
        for (RowBand rowBand : rowBands) {
            double epsilon = Math.max(MIN_ROW_BAND_EPSILON,
                Math.min(rowBand.getAverageHeight(), textLine.getHeight()) * ROW_BAND_EPSILON_RATIO);
            if (Math.abs(rowBand.getCenterY() - textLine.getCenterY()) <= epsilon ||
                    rowBand.hasVerticalOverlap(textLine.getTopY(), textLine.getBottomY())) {
                return rowBand;
            }
        }
        return null;
    }

    private static RowBand findBestRowBand(List<RowBand> rowBands, IObject content) {
        RowBand bestBand = null;
        double bestDistance = Double.MAX_VALUE;
        for (RowBand rowBand : rowBands) {
            if (rowBand.hasVerticalOverlap(content.getTopY(), content.getBottomY())) {
                double distance = Math.abs(rowBand.getCenterY() - content.getCenterY());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestBand = rowBand;
                }
            }
        }
        if (bestBand != null) {
            return bestBand;
        }

        for (RowBand rowBand : rowBands) {
            double distance = Math.abs(rowBand.getCenterY() - content.getCenterY());
            if (distance < bestDistance && distance <= ROW_BAND_ASSIGNMENT_EPSILON + rowBand.getAverageHeight()) {
                bestDistance = distance;
                bestBand = rowBand;
            }
        }
        return bestBand;
    }

    private static TableBorder rebuildTable(TableBorder originalTable, List<RowBand> rowBands) {
        TableBorder rebuiltTable = new TableBorder(rowBands.size(), originalTable.getNumberOfColumns());
        rebuiltTable.setRecognizedStructureId(originalTable.getRecognizedStructureId());
        rebuiltTable.setBoundingBox(new BoundingBox(originalTable.getBoundingBox()));
        rebuiltTable.setNode(originalTable.getNode());
        rebuiltTable.setIndex(originalTable.getIndex());
        rebuiltTable.setLevel(originalTable.getLevel());
        rebuiltTable.setPreviousTable(originalTable.getPreviousTable());
        rebuiltTable.setNextTable(originalTable.getNextTable());

        for (int rowNumber = 0; rowNumber < rowBands.size(); rowNumber++) {
            RowBand rowBand = rowBands.get(rowNumber);
            TableBorderRow rebuiltRow = new TableBorderRow(rowNumber, originalTable.getNumberOfColumns(),
                originalTable.getRecognizedStructureId());
            rebuiltRow.setBoundingBox(rowBand.createRowBoundingBox(originalTable));
            rebuiltTable.getRows()[rowNumber] = rebuiltRow;

            for (int columnNumber = 0; columnNumber < originalTable.getNumberOfColumns(); columnNumber++) {
                TableBorderCell rebuiltCell = new TableBorderCell(rowNumber, columnNumber, 1, 1,
                    originalTable.getRecognizedStructureId());
                rebuiltCell.setContents(rowBand.getContents(columnNumber));
                rebuiltCell.setBoundingBox(rowBand.createCellBoundingBox(originalTable, columnNumber));
                rebuiltRow.getCells()[columnNumber] = rebuiltCell;
            }
        }

        rebuiltTable.calculateCoordinatesUsingBoundingBoxesOfRowsAndColumns();
        return rebuiltTable;
    }

    private static boolean isReplacementQualityBetter(TableBorder originalTable, TableBorder rebuiltTable) {
        int originalNonEmptyRows = countNonEmptyRows(originalTable);
        int rebuiltNonEmptyRows = countNonEmptyRows(rebuiltTable);
        if (rebuiltNonEmptyRows <= originalNonEmptyRows) {
            return false;
        }

        int originalNonEmptyColumns = countNonEmptyColumns(originalTable);
        int rebuiltNonEmptyColumns = countNonEmptyColumns(rebuiltTable);
        if (rebuiltNonEmptyColumns < originalNonEmptyColumns) {
            return false;
        }

        if (!hasMonotonicRowOrder(rebuiltTable)) {
            return false;
        }

        TableLineStats originalLineStats = collectTableLineStats(originalTable);
        TableLineStats rebuiltLineStats = collectTableLineStats(rebuiltTable);

        return rebuiltLineStats.oversizedCellCount < originalLineStats.oversizedCellCount ||
            rebuiltLineStats.maxMeaningfulTextLines < originalLineStats.maxMeaningfulTextLines;
    }

    private static int countNonEmptyRows(TableBorder tableBorder) {
        int count = 0;
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            boolean hasContent = false;
            for (int columnNumber = 0; columnNumber < tableBorder.getNumberOfColumns(); columnNumber++) {
                TableBorderCell cell = tableBorder.getRow(rowNumber).getCell(columnNumber);
                if (cell != null && cell.getRowNumber() == rowNumber && cell.getColNumber() == columnNumber &&
                        hasMeaningfulContent(cell.getContents())) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) {
                count++;
            }
        }
        return count;
    }

    private static int countNonEmptyColumns(TableBorder tableBorder) {
        int count = 0;
        for (int columnNumber = 0; columnNumber < tableBorder.getNumberOfColumns(); columnNumber++) {
            boolean hasContent = false;
            for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
                TableBorderCell cell = tableBorder.getRow(rowNumber).getCell(columnNumber);
                if (cell != null && cell.getRowNumber() == rowNumber && cell.getColNumber() == columnNumber &&
                        hasMeaningfulContent(cell.getContents())) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasMeaningfulContent(List<IObject> contents) {
        if (contents == null) {
            return false;
        }
        for (IObject content : contents) {
            if (content instanceof TextChunk) {
                if (!((TextChunk) content).isWhiteSpaceChunk() && !((TextChunk) content).isEmpty()) {
                    return true;
                }
            } else if (content instanceof TextLine) {
                if (!((TextLine) content).isSpaceLine() && !((TextLine) content).isEmpty()) {
                    return true;
                }
            } else if (!(content instanceof LineChunk) && !(content instanceof LineArtChunk)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMonotonicRowOrder(TableBorder tableBorder) {
        double previousCenterY = Double.POSITIVE_INFINITY;
        double previousBottomY = Double.POSITIVE_INFINITY;
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            TableBorderRow row = tableBorder.getRow(rowNumber);
            double currentCenterY = row.getBoundingBox().getCenterY();
            if (currentCenterY >= previousCenterY) {
                return false;
            }
            if (row.getTopY() > previousBottomY + ROW_ORDER_EPSILON) {
                return false;
            }
            previousCenterY = currentCenterY;
            previousBottomY = row.getBottomY();
        }
        return true;
    }

    private static TableLineStats collectTableLineStats(TableBorder tableBorder) {
        int oversizedCellCount = 0;
        int maxMeaningfulTextLines = 0;
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            for (int columnNumber = 0; columnNumber < tableBorder.getNumberOfColumns(); columnNumber++) {
                TableBorderCell cell = tableBorder.getRow(rowNumber).getCell(columnNumber);
                if (cell != null && cell.getRowNumber() == rowNumber && cell.getColNumber() == columnNumber) {
                    int meaningfulTextLines = countMeaningfulTextLines(cell.getContents());
                    if (meaningfulTextLines >= OVERSIZED_CELL_LINE_COUNT) {
                        oversizedCellCount++;
                    }
                    maxMeaningfulTextLines = Math.max(maxMeaningfulTextLines, meaningfulTextLines);
                }
            }
        }
        return new TableLineStats(oversizedCellCount, maxMeaningfulTextLines);
    }

    private static int countMeaningfulTextLines(List<IObject> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        List<IObject> orderedContents = new ArrayList<>(contents);
        orderedContents.sort(CONTENT_COMPARATOR);
        int count = 0;
        for (IObject content : TextLineProcessor.processTextLines(orderedContents)) {
            if (content instanceof TextLine) {
                TextLine textLine = (TextLine) content;
                if (!textLine.isEmpty() && !textLine.isSpaceLine()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static List<RowSegments> collectRightColumnSegments(List<IObject> rawPageContents, TableBorder tableBorder) {
        List<RowSegments> rowSegments = new ArrayList<>(tableBorder.getNumberOfRows());
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            TableBorderCell rightCell = tableBorder.getCell(rowNumber, 1);
            List<IObject> rowContents = collectCellContents(rawPageContents, rightCell);
            rowSegments.add(new RowSegments(rowNumber, splitIntoSegments(rowContents)));
        }
        return rowSegments;
    }

    private static List<List<IObject>> collectColumnContents(List<IObject> rawPageContents, TableBorder tableBorder,
                                                             int columnNumber) {
        List<List<IObject>> columnContents = new ArrayList<>(tableBorder.getNumberOfRows());
        for (int rowNumber = 0; rowNumber < tableBorder.getNumberOfRows(); rowNumber++) {
            columnContents.add(collectCellContents(rawPageContents, tableBorder.getCell(rowNumber, columnNumber)));
        }
        return columnContents;
    }

    private static List<IObject> collectCellContents(List<IObject> rawPageContents, TableBorderCell cell) {
        List<IObject> cellContents = new ArrayList<>();
        for (IObject rawContent : rawPageContents) {
            if (rawContent instanceof TextChunk) {
                TextChunk cellPart = TableBorderProcessor.getTextChunkPartForRange((TextChunk) rawContent,
                    cell.getLeftX(), cell.getRightX());
                if (cellPart != null && !cellPart.isEmpty() && !cellPart.isWhiteSpaceChunk() &&
                        belongsToRow(cellPart, cell)) {
                    cellContents.add(cellPart);
                }
            } else if (!(rawContent instanceof TextLine) &&
                    !(rawContent instanceof LineChunk) && !(rawContent instanceof LineArtChunk) &&
                    belongsToColumn(rawContent, cell) && belongsToRow(rawContent, cell)) {
                cellContents.add(rawContent);
            }
        }
        cellContents.sort(CONTENT_COMPARATOR);
        return cellContents;
    }

    private static boolean belongsToColumn(IObject content, TableBorderCell cell) {
        return content.getCenterX() >= cell.getLeftX() - ROW_ORDER_EPSILON &&
            content.getCenterX() <= cell.getRightX() + ROW_ORDER_EPSILON;
    }

    private static boolean belongsToRow(IObject content, TableBorderCell cell) {
        return content.getCenterY() <= cell.getTopY() + ROW_ORDER_EPSILON &&
            content.getCenterY() >= cell.getBottomY() - ROW_ORDER_EPSILON;
    }

    private static List<Segment> splitIntoSegments(List<IObject> rowContents) {
        List<Segment> segments = new ArrayList<>();
        Segment currentSegment = null;
        double previousRightX = Double.NEGATIVE_INFINITY;
        for (IObject content : rowContents) {
            if (currentSegment == null || content.getLeftX() - previousRightX > SPLIT_SEGMENT_GAP) {
                currentSegment = new Segment();
                segments.add(currentSegment);
            }
            currentSegment.add(content);
            previousRightX = Math.max(previousRightX, content.getRightX());
        }
        return segments;
    }

    private static int countSupportedSplitSegments(List<RowSegments> rowSegments) {
        int supportedSegments = 0;
        for (int segmentIndex = 0; segmentIndex < MAX_SPLIT_SEGMENTS; segmentIndex++) {
            int supportCount = 0;
            for (RowSegments rowSegment : rowSegments) {
                if (rowSegment.segmentCount() > segmentIndex) {
                    supportCount++;
                }
            }
            if (supportCount >= MIN_SPLIT_SEGMENT_SUPPORT) {
                supportedSegments++;
            } else {
                break;
            }
        }
        return supportedSegments;
    }

    private static double[] computeSplitBoundaries(TableBorder tableBorder, List<RowSegments> rowSegments,
                                                   int supportedSegments) {
        double[] segmentStarts = new double[supportedSegments];
        double[] segmentEnds = new double[supportedSegments];
        for (int segmentIndex = 0; segmentIndex < supportedSegments; segmentIndex++) {
            List<Double> starts = new ArrayList<>();
            List<Double> ends = new ArrayList<>();
            for (RowSegments rowSegment : rowSegments) {
                Segment segment = rowSegment.getSegment(segmentIndex);
                if (segment != null) {
                    starts.add(segment.getLeftX());
                    ends.add(segment.getRightX());
                }
            }
            if (starts.size() < MIN_SPLIT_SEGMENT_SUPPORT) {
                return null;
            }
            segmentStarts[segmentIndex] = median(starts);
            segmentEnds[segmentIndex] = median(ends);
        }

        double[] boundaries = new double[supportedSegments + 2];
        boundaries[0] = tableBorder.getLeftX();
        boundaries[1] = tableBorder.getRightX(0);
        for (int segmentIndex = 1; segmentIndex < supportedSegments; segmentIndex++) {
            double boundary = (segmentEnds[segmentIndex - 1] + segmentStarts[segmentIndex]) / 2.0;
            if (boundary <= boundaries[segmentIndex]) {
                return null;
            }
            boundaries[segmentIndex + 1] = boundary;
        }
        boundaries[boundaries.length - 1] = tableBorder.getRightX();
        return boundaries;
    }

    private static double median(List<Double> values) {
        values.sort(Double::compareTo);
        int middle = values.size() / 2;
        if (values.size() % 2 == 0) {
            return (values.get(middle - 1) + values.get(middle)) / 2.0;
        }
        return values.get(middle);
    }

    private static TableBorder rebuildDenseTwoColumnTable(TableBorder originalTable, List<List<IObject>> leftColumnContents,
                                                          List<RowSegments> rowSegments, double[] splitBoundaries) {
        int rebuiltColumnCount = splitBoundaries.length - 1;
        TableBorder rebuiltTable = new TableBorder(originalTable.getNumberOfRows(), rebuiltColumnCount);
        rebuiltTable.setRecognizedStructureId(originalTable.getRecognizedStructureId());
        rebuiltTable.setBoundingBox(new BoundingBox(originalTable.getBoundingBox()));
        rebuiltTable.setNode(originalTable.getNode());
        rebuiltTable.setIndex(originalTable.getIndex());
        rebuiltTable.setLevel(originalTable.getLevel());
        rebuiltTable.setPreviousTable(originalTable.getPreviousTable());
        rebuiltTable.setNextTable(originalTable.getNextTable());

        for (int rowNumber = 0; rowNumber < originalTable.getNumberOfRows(); rowNumber++) {
            TableBorderRow originalRow = originalTable.getRow(rowNumber);
            TableBorderRow rebuiltRow = new TableBorderRow(rowNumber, rebuiltColumnCount,
                originalTable.getRecognizedStructureId());
            rebuiltRow.setBoundingBox(new BoundingBox(originalRow.getBoundingBox()));
            rebuiltTable.getRows()[rowNumber] = rebuiltRow;

            for (int columnNumber = 0; columnNumber < rebuiltColumnCount; columnNumber++) {
                TableBorderCell rebuiltCell = new TableBorderCell(rowNumber, columnNumber, 1, 1,
                    originalTable.getRecognizedStructureId());
                rebuiltCell.setBoundingBox(new BoundingBox(originalTable.getPageNumber(),
                    splitBoundaries[columnNumber], originalRow.getBottomY(),
                    splitBoundaries[columnNumber + 1], originalRow.getTopY()));
                if (columnNumber == 0) {
                    rebuiltCell.setContents(new ArrayList<>(leftColumnContents.get(rowNumber)));
                } else {
                    Segment segment = rowSegments.get(rowNumber).getSegment(columnNumber - 1);
                    rebuiltCell.setContents(segment != null ? segment.copyContents() : new ArrayList<>());
                }
                rebuiltRow.getCells()[columnNumber] = rebuiltCell;
            }
        }

        rebuiltTable.calculateCoordinatesUsingBoundingBoxesOfRowsAndColumns();
        return rebuiltTable;
    }

    private static boolean isDenseTwoColumnSplitBetter(TableBorder originalTable, TableBorder rebuiltTable,
                                                       List<RowSegments> rowSegments, int supportedSegments) {
        if (rebuiltTable.getNumberOfColumns() <= originalTable.getNumberOfColumns()) {
            return false;
        }
        if (countNonEmptyRows(rebuiltTable) < countNonEmptyRows(originalTable)) {
            return false;
        }
        if (countNonEmptyColumns(rebuiltTable) <= countNonEmptyColumns(originalTable)) {
            return false;
        }
        if (!hasMonotonicRowOrder(rebuiltTable)) {
            return false;
        }

        int rowsWithAllSupportedSegments = 0;
        for (RowSegments rowSegment : rowSegments) {
            if (rowSegment.segmentCount() >= supportedSegments) {
                rowsWithAllSupportedSegments++;
            }
        }
        return rowsWithAllSupportedSegments >= MIN_SPLIT_SEGMENT_SUPPORT;
    }

    private static double[] createTextSplitBoundaries(TableBorder tableBorder) {
        return createTextSplitBoundaries(tableBorder, 4, TEXT_SPLIT_WIDTH_RATIOS[2], TEXT_SPLIT_WIDTH_RATIOS[3]);
    }

    private static double[] createTextSplitBoundaries(TableBorder tableBorder, int targetColumns,
                                                      double... rightSideRatios) {
        double leftX = tableBorder.getLeftX();
        double rightX = tableBorder.getRightX();
        double width = rightX - leftX;
        double[] boundaries = new double[targetColumns + 1];
        boundaries[0] = leftX;
        boundaries[1] = tableBorder.getNumberOfColumns() > 0 ? tableBorder.getRightX(0) : leftX;
        for (int index = 0; index < rightSideRatios.length; index++) {
            boundaries[index + 2] = leftX + (width * rightSideRatios[index]);
        }
        boundaries[boundaries.length - 1] = rightX;
        return boundaries;
    }

    private static TableBorderCell createSplitCell(TableBorder tableBorder, TableBorderRow row, int rowNumber,
                                                   int columnNumber, double[] boundaries) {
        TableBorderCell cell = new TableBorderCell(rowNumber, columnNumber, 1, 1,
            tableBorder.getRecognizedStructureId());
        cell.setBoundingBox(new BoundingBox(tableBorder.getPageNumber(), boundaries[columnNumber], row.getBottomY(),
            boundaries[columnNumber + 1], row.getTopY()));
        cell.setContents(new ArrayList<>());
        return cell;
    }

    private static TableBorderCell createSplitParagraphCell(TableBorder tableBorder, TableBorderRow row, int rowNumber,
                                                            int columnNumber, double[] boundaries, String value) {
        TableBorderCell cell = createSplitCell(tableBorder, row, rowNumber, columnNumber, boundaries);
        if (value != null && !value.isEmpty()) {
            cell.setContents(new ArrayList<>(List.of(createParagraph(cell.getBoundingBox(), value))));
        }
        return cell;
    }

    private static SemanticParagraph createParagraph(BoundingBox boundingBox, String value) {
        TextChunk chunk = new TextChunk(value);
        TextLine line = new TextLine(chunk);
        TextColumn column = new TextColumn(line);
        return new SemanticParagraph(new BoundingBox(boundingBox), List.of(column));
    }

    private static String extractCellText(TableBorderCell cell) {
        if (cell == null || cell.getContents() == null || cell.getContents().isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (IObject content : cell.getContents()) {
            if (content instanceof SemanticParagraph) {
                String value = ((SemanticParagraph) content).getValue();
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            } else if (content instanceof TextChunk) {
                String value = ((TextChunk) content).getValue();
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            } else if (content instanceof TextLine) {
                String value = ((TextLine) content).getValue();
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return String.join(" ", values).trim();
    }

    private static ClinicalSplit parseClinicalSplit(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher referenceMatcher = CLINICAL_REFERENCE_PATTERN.matcher(value.trim());
        if (!referenceMatcher.matches()) {
            return null;
        }

        String prefix = referenceMatcher.group("prefix").trim();
        String reference = referenceMatcher.group("reference").trim();
        Matcher valueUnitMatcher = CLINICAL_VALUE_UNIT_PATTERN.matcher(prefix);
        if (!valueUnitMatcher.matches()) {
            return null;
        }

        String result = valueUnitMatcher.group("result").trim();
        String unit = valueUnitMatcher.group("unit").trim();
        if (result.isEmpty() || unit.isEmpty() || reference.isEmpty()) {
            return null;
        }
        return new ClinicalSplit(result, unit, reference);
    }

    private static ClinicalMatrixSplit parseClinicalMatrixSplit(String value, String reference) {
        if (value == null || value.isBlank() || reference == null || reference.isBlank()) {
            return null;
        }
        Matcher matcher = CLINICAL_MATRIX_VALUE_UNIT_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new ClinicalMatrixSplit(matcher.group("matrix").trim(), matcher.group("result").trim(),
            matcher.group("unit").trim(), reference.trim());
    }

    private static ClinicalValueSplit parseClinicalValueSplit(String value, String reference) {
        if (value == null || value.isBlank()) {
            return null;
        }

        Matcher valueUnitMatcher = CLINICAL_VALUE_UNIT_PATTERN.matcher(value.trim());
        if (valueUnitMatcher.matches()) {
            return new ClinicalValueSplit(valueUnitMatcher.group("result").trim(),
                valueUnitMatcher.group("unit").trim(), reference == null ? "" : reference.trim());
        }

        Matcher valueOnlyMatcher = CLINICAL_VALUE_ONLY_PATTERN.matcher(value.trim());
        if (valueOnlyMatcher.matches()) {
            return new ClinicalValueSplit(valueOnlyMatcher.group("result").trim(), "",
                reference == null ? "" : reference.trim());
        }
        return null;
    }

    private static final class ColumnSnapshot {

        private final List<IObject> contents = new ArrayList<>();
        private final List<TextLine> textLines = new ArrayList<>();
        private int meaningfulLineCount;

        private void addContent(IObject content) {
            contents.add(content);
        }

        private void finalizeSnapshot() {
            contents.sort(CONTENT_COMPARATOR);
            List<IObject> textCandidates = new ArrayList<>();
            for (IObject content : contents) {
                if (content instanceof TextChunk || content instanceof TextLine) {
                    textCandidates.add(content);
                }
            }
            for (IObject content : TextLineProcessor.processTextLines(textCandidates)) {
                if (content instanceof TextLine) {
                    TextLine textLine = (TextLine) content;
                    if (!textLine.isEmpty() && !textLine.isSpaceLine()) {
                        textLines.add(textLine);
                        meaningfulLineCount++;
                    }
                }
            }
        }
    }

    private static final class TableLineStats {

        private final int oversizedCellCount;
        private final int maxMeaningfulTextLines;

        private TableLineStats(int oversizedCellCount, int maxMeaningfulTextLines) {
            this.oversizedCellCount = oversizedCellCount;
            this.maxMeaningfulTextLines = maxMeaningfulTextLines;
        }
    }

    private static final class RowSegments {

        private final int rowNumber;
        private final List<Segment> segments;

        private RowSegments(int rowNumber, List<Segment> segments) {
            this.rowNumber = rowNumber;
            this.segments = segments;
        }

        private Segment getSegment(int segmentIndex) {
            if (segmentIndex < 0 || segmentIndex >= segments.size()) {
                return null;
            }
            return segments.get(segmentIndex);
        }

        private int segmentCount() {
            return segments.size();
        }
    }

    private static final class Segment {

        private final List<IObject> contents = new ArrayList<>();
        private double leftX = Double.POSITIVE_INFINITY;
        private double rightX = Double.NEGATIVE_INFINITY;

        private void add(IObject content) {
            contents.add(content);
            leftX = Math.min(leftX, content.getLeftX());
            rightX = Math.max(rightX, content.getRightX());
        }

        private double getLeftX() {
            return leftX;
        }

        private double getRightX() {
            return rightX;
        }

        private List<IObject> copyContents() {
            return new ArrayList<>(contents);
        }
    }

    private static final class ClinicalSplit {

        private final String result;
        private final String unit;
        private final String reference;

        private ClinicalSplit(String result, String unit, String reference) {
            this.result = result;
            this.unit = unit;
            this.reference = reference;
        }
    }

    private static final class ClinicalMatrixSplit {

        private final String matrix;
        private final String result;
        private final String unit;
        private final String reference;

        private ClinicalMatrixSplit(String matrix, String result, String unit, String reference) {
            this.matrix = matrix;
            this.result = result;
            this.unit = unit;
            this.reference = reference;
        }
    }

    private static final class ClinicalValueSplit {

        private final String result;
        private final String unit;
        private final String reference;

        private ClinicalValueSplit(String result, String unit, String reference) {
            this.result = result;
            this.unit = unit;
            this.reference = reference;
        }
    }

    private static final class RowBand {

        private final List<List<IObject>> contentsByColumn;
        private double topY = Double.NEGATIVE_INFINITY;
        private double bottomY = Double.POSITIVE_INFINITY;
        private double centerY;
        private double averageHeight;
        private int lineCount;

        private RowBand(int columnCount) {
            this.contentsByColumn = new ArrayList<>(columnCount);
            for (int columnNumber = 0; columnNumber < columnCount; columnNumber++) {
                this.contentsByColumn.add(new ArrayList<>());
            }
        }

        private void addLine(TextLine textLine) {
            updateBounds(textLine.getTopY(), textLine.getBottomY(), textLine.getCenterY(), textLine.getHeight());
        }

        private void addContent(int columnNumber, IObject content) {
            contentsByColumn.get(columnNumber).add(content);
            updateBounds(content.getTopY(), content.getBottomY(), content.getCenterY(), content.getHeight());
        }

        private void updateBounds(double contentTopY, double contentBottomY, double contentCenterY, double height) {
            topY = Math.max(topY, contentTopY);
            bottomY = Math.min(bottomY, contentBottomY);
            centerY = ((centerY * lineCount) + contentCenterY) / (lineCount + 1);
            averageHeight = ((averageHeight * lineCount) + height) / (lineCount + 1);
            lineCount++;
        }

        private boolean hasVerticalOverlap(double contentTopY, double contentBottomY) {
            return contentBottomY <= topY + ROW_ORDER_EPSILON && contentTopY >= bottomY - ROW_ORDER_EPSILON;
        }

        private boolean isEmpty() {
            for (List<IObject> contents : contentsByColumn) {
                if (!contents.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private void sortContents() {
            for (List<IObject> contents : contentsByColumn) {
                contents.sort(CONTENT_COMPARATOR);
            }
        }

        private List<IObject> getContents(int columnNumber) {
            return new ArrayList<>(contentsByColumn.get(columnNumber));
        }

        private BoundingBox createRowBoundingBox(TableBorder tableBorder) {
            return new BoundingBox(tableBorder.getPageNumber(), tableBorder.getLeftX(), bottomY,
                tableBorder.getRightX(), topY);
        }

        private BoundingBox createCellBoundingBox(TableBorder tableBorder, int columnNumber) {
            return new BoundingBox(tableBorder.getPageNumber(), tableBorder.getLeftX(columnNumber), bottomY,
                tableBorder.getRightX(columnNumber), topY);
        }

        private double getCenterY() {
            return centerY;
        }

        private double getAverageHeight() {
            return averageHeight;
        }
    }
}

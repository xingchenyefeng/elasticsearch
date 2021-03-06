/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.filestructurefinder;

import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.xpack.core.ml.filestructurefinder.FieldStats;
import org.elasticsearch.xpack.core.ml.filestructurefinder.FileStructure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class TextLogFileStructureFinder implements FileStructureFinder {

    private final List<String> sampleMessages;
    private final FileStructure structure;

    static TextLogFileStructureFinder makeTextLogFileStructureFinder(List<String> explanation, String sample, String charsetName,
                                                                     Boolean hasByteOrderMarker, FileStructureOverrides overrides,
                                                                     TimeoutChecker timeoutChecker) {
        String[] sampleLines = sample.split("\n");
        TimestampFormatFinder timestampFormatFinder = populateTimestampFormatFinder(explanation, sampleLines, overrides, timeoutChecker);
        switch (timestampFormatFinder.getNumMatchedFormats()) {
            case 0:
                // Is it appropriate to treat a file that is neither structured nor has
                // a regular pattern of timestamps as a log file?  Probably not...
                throw new IllegalArgumentException("Could not find " + ((overrides.getTimestampFormat() == null)
                    ? "a timestamp"
                    : "the specified timestamp format") + " in the sample provided");
            case 1:
                // Simple case
                break;
            default:
                timestampFormatFinder.selectBestMatch();
                break;
        }

        explanation.add(((overrides.getTimestampFormat() == null) ? "Most likely timestamp" : "Timestamp") + " format is " +
            timestampFormatFinder.getJavaTimestampFormats());

        List<String> sampleMessages = new ArrayList<>();
        StringBuilder preamble = new StringBuilder();
        int linesConsumed = 0;
        StringBuilder message = null;
        int linesInMessage = 0;
        String multiLineRegex = createMultiLineMessageStartRegex(timestampFormatFinder.getPrefaces(),
            timestampFormatFinder.getSimplePattern().pattern());
        Pattern multiLinePattern = Pattern.compile(multiLineRegex);
        for (String sampleLine : sampleLines) {
            if (multiLinePattern.matcher(sampleLine).find()) {
                if (message != null) {
                    sampleMessages.add(message.toString());
                    linesConsumed += linesInMessage;
                }
                message = new StringBuilder(sampleLine);
                linesInMessage = 1;
            } else {
                // If message is null here then the sample probably began with the incomplete ending of a previous message
                if (message == null) {
                    // We count lines before the first message as consumed (just like we would
                    // for the CSV header or lines before the first XML document starts)
                    ++linesConsumed;
                } else {
                    message.append('\n').append(sampleLine);
                    ++linesInMessage;
                }
            }
            timeoutChecker.check("multi-line message determination");
            if (sampleMessages.size() < 2) {
                preamble.append(sampleLine).append('\n');
            }
        }
        // Don't add the last message, as it might be partial and mess up subsequent pattern finding

        if (sampleMessages.isEmpty()) {
            throw new IllegalArgumentException("Failed to create more than one message from the sample lines provided. (The "
                + "last is discarded in case the sample is incomplete.) If your sample does contain multiple messages the "
                + "problem is probably that the primary timestamp format has been incorrectly detected, so try overriding it.");
        }

        // null to allow GC before Grok pattern search
        sampleLines = null;

        FileStructure.Builder structureBuilder = new FileStructure.Builder(FileStructure.Format.SEMI_STRUCTURED_TEXT)
            .setCharset(charsetName)
            .setHasByteOrderMarker(hasByteOrderMarker)
            .setSampleStart(preamble.toString())
            .setNumLinesAnalyzed(linesConsumed)
            .setNumMessagesAnalyzed(sampleMessages.size())
            .setMultilineStartPattern(multiLineRegex);

        SortedMap<String, Object> mappings = new TreeMap<>();
        mappings.put("message", Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, "text"));
        mappings.put(FileStructureUtils.DEFAULT_TIMESTAMP_FIELD, Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, "date"));

        SortedMap<String, FieldStats> fieldStats = new TreeMap<>();
        fieldStats.put("message", FileStructureUtils.calculateFieldStats(sampleMessages, timeoutChecker));

        Map<String, String> customGrokPatternDefinitions = timestampFormatFinder.getCustomGrokPatternDefinitions();
        GrokPatternCreator grokPatternCreator = new GrokPatternCreator(explanation, sampleMessages, mappings, fieldStats,
            customGrokPatternDefinitions, timeoutChecker);
        // We can't parse directly into @timestamp using Grok, so parse to some other time field, which the date filter will then remove
        String interimTimestampField = overrides.getTimestampField();
        String grokPattern = overrides.getGrokPattern();
        if (grokPattern != null) {
            if (interimTimestampField == null) {
                interimTimestampField = "timestamp";
            }
            grokPatternCreator.validateFullLineGrokPattern(grokPattern, interimTimestampField);
        } else {
            Tuple<String, String> timestampFieldAndFullMatchGrokPattern =
                grokPatternCreator.findFullLineGrokPattern(interimTimestampField);
            if (timestampFieldAndFullMatchGrokPattern != null) {
                interimTimestampField = timestampFieldAndFullMatchGrokPattern.v1();
                grokPattern = timestampFieldAndFullMatchGrokPattern.v2();
            } else {
                if (interimTimestampField == null) {
                    interimTimestampField = "timestamp";
                }
                grokPattern =
                    grokPatternCreator.createGrokPatternFromExamples(timestampFormatFinder.getGrokPatternName(), interimTimestampField);
            }
        }

        boolean needClientTimeZone = timestampFormatFinder.hasTimezoneDependentParsing();

        FileStructure structure = structureBuilder
            .setTimestampField(interimTimestampField)
            .setJodaTimestampFormats(timestampFormatFinder.getJodaTimestampFormats())
            .setJavaTimestampFormats(timestampFormatFinder.getJavaTimestampFormats())
            .setNeedClientTimezone(needClientTimeZone)
            .setGrokPattern(grokPattern)
            .setIngestPipeline(FileStructureUtils.makeIngestPipelineDefinition(grokPattern,
                customGrokPatternDefinitions, interimTimestampField,
                timestampFormatFinder.getJavaTimestampFormats(), needClientTimeZone))
            .setMappings(mappings)
            .setFieldStats(fieldStats)
            .setExplanation(explanation)
            .build();

        return new TextLogFileStructureFinder(sampleMessages, structure);
    }

    private TextLogFileStructureFinder(List<String> sampleMessages, FileStructure structure) {
        this.sampleMessages = Collections.unmodifiableList(sampleMessages);
        this.structure = structure;
    }

    @Override
    public List<String> getSampleMessages() {
        return sampleMessages;
    }

    @Override
    public FileStructure getStructure() {
        return structure;
    }

    static TimestampFormatFinder populateTimestampFormatFinder(List<String> explanation, String[] sampleLines,
                                                               FileStructureOverrides overrides, TimeoutChecker timeoutChecker) {
        TimestampFormatFinder timestampFormatFinder =
            new TimestampFormatFinder(explanation, overrides.getTimestampFormat(), false, false, false, timeoutChecker);

        for (String sampleLine : sampleLines) {
            timestampFormatFinder.addSample(sampleLine);
        }

        return timestampFormatFinder;
    }

    static String createMultiLineMessageStartRegex(Collection<String> prefaces, String simpleDateRegex) {

        StringBuilder builder = new StringBuilder("^");
        GrokPatternCreator.addIntermediateRegex(builder, prefaces);
        builder.append(simpleDateRegex);
        if (builder.substring(0, 3).equals("^\\b")) {
            builder.delete(1, 3);
        }
        return builder.toString();
    }
}

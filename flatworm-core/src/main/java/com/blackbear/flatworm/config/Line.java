/*
 * Flatworm - A Java Flat File Importer/Exporter Copyright (C) 2004 James M. Turner.
 * Extended by James Lawrence 2005
 * Extended by Josh Brackett in 2011 and 2012
 * Extended by Alan Henson in 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.blackbear.flatworm.config;

import com.google.common.base.Strings;

import com.blackbear.flatworm.BeanMappingStrategy;
import com.blackbear.flatworm.CardinalityMode;
import com.blackbear.flatworm.ParseUtils;
import com.blackbear.flatworm.PropertyUtilsMappingStrategy;
import com.blackbear.flatworm.Util;
import com.blackbear.flatworm.converters.ConversionHelper;
import com.blackbear.flatworm.errors.FlatwormParserException;

import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Bean class used to store the values from the Line XML tag
 */
@Slf4j
public class Line {
    private List<LineElement> elements = new ArrayList<>();
    private ConversionHelper conversionHelper;
    private Map<String, Object> beans;
    private BeanMappingStrategy mappingStrategy = new PropertyUtilsMappingStrategy();

    // properties used for processing delimited input
    private List<LineToken> lineTokens;
    private int currentField = 0;

    @Getter
    @Setter
    private String delimiter;

    @Getter
    private char chrQuote = '\0';

    @Getter
    @Setter
    private RecordDefinition parentRecordDefinition;

    public Line() {
    }

    /**
     * <b>NOTE:</b> Only the first character in the string is considered.
     *
     * @param quote The quote character that encompass the tokens in a delimited file.
     */
    public void setQuoteChar(String quote) {
        if (quote != null) {
            chrQuote = quote.charAt(0);
        }
    }

    public boolean isDelimited() {
        return (null != delimiter);
    }

    public List<LineElement> getElements() {
        return Collections.unmodifiableList(elements);
    }

    public void addElement(LineElement lineElement) {
        lineElement.setParentLine(this);
        elements.add(lineElement);
    }

    @Override
    public String toString() {
        return super.toString() + "[elements = " + elements + "]";
    }

    /**
     * @param inputLine  A single line from file to be parsed into its corresponding bean
     * @param beans      A Hashmap containing a collection of beans which will be populated with parsed data
     * @param convHelper A ConversionHelper which aids in the conversion of datatypes and string formatting
     * @throws FlatwormParserException should any issues occur while parsing the data.
     */
    public void parseInput(String inputLine, Map<String, Object> beans, ConversionHelper convHelper) throws FlatwormParserException {
        this.conversionHelper = convHelper;
        this.beans = beans;

        // JBL - check for delimited status
        if (isDelimited()) {
            // Don't parse empty lines
            if (!Strings.isNullOrEmpty(inputLine)) {
                parseInputDelimited(inputLine);
                return;
            }
        }

        int charPos = 0;
        for (LineElement le : elements) {
            if (le instanceof RecordElement) {
                RecordElement re = (RecordElement) le;
                int start = charPos;
                int end = charPos;
                if (re.isFieldStartSet())
                    start = re.getFieldStart();
                if (re.isFieldEndSet()) {
                    end = re.getFieldEnd();
                    charPos = end;
                }
                if (re.isFieldLengthSet()) {
                    end = start + re.getFieldLength();
                    charPos = end;
                }
                if (end > inputLine.length())
                    throw new FlatwormParserException("Looking for field " + re.getBeanRef()
                            + " at pos " + start + ", end " + end + ", input length = " + inputLine.length());
                String beanRef = re.getBeanRef();
                if (beanRef != null) {
                    String fieldChars = inputLine.substring(start, end);

                    // JBL - to keep from dup. code, moved this to a private method
                    mapField(fieldChars, re);
                }
            }
            /* TODO - to be added. For now we only support delimited. But there really is no reason not to support fixed-format as well
            else if (le instanceof SegmentElement) {
                SegmentElement se = (SegmentElement) le;
                int start = charPos;
                int end = charPos;
                if (se.isFieldStartSet())
                    start = se.getFieldStart();
                if (se.isFieldEndSet())
                {
                    end = se.getFieldEnd();
                    charPos = end;
                }
                if (se.isFieldLengthSet())
                {
                    end = start + se.getFieldLength();
                    charPos = end;
                }
                if (end > inputLine.length())
                    throw new FlatwormParseException("Looking for field " + se.getBeanRef() + " at pos " + start
                            + ", end " + end + ", input length = " + inputLine.length());
                String beanRef = se.getBeanRef();
                if (beanRef != null)
                {
                    String fieldChars = inputLine.substring(start, end);

                    // JBL - to keep from dup. code, moved this to a private method
                    mapField(conversionHelper, fieldChars, se, beans);
                }
            }
            */
        }
    }

    /**
     * Convert string field from file into appropriate converterName and set bean's value<br>
     *
     * @param fieldChars    the raw string data read from the field
     * @param recordElement the RecordElement, which contains detailed information about the field
     * @throws FlatwormParserException should any issues occur while parsing the data.
     */
    private void mapField(String fieldChars, RecordElement recordElement) throws FlatwormParserException {
        String beanRef = recordElement.getBeanRef();
        int posOfFirstDot = beanRef.indexOf('.');
        String beanName = beanRef.substring(0, posOfFirstDot);
        String property = beanRef.substring(posOfFirstDot + 1);
        Object bean = beans.get(beanName);

        Object value;
        if (!StringUtils.isBlank(recordElement.getConverterName())) {
            // Using the configuration based approach.
            value = conversionHelper.convert(recordElement.getConverterName(), fieldChars, recordElement.getConversionOptions(),
                    recordElement.getBeanRef());
        } else {
            // Use the reflection approach.
            value = conversionHelper.convert(bean, beanName, property, fieldChars, recordElement.getConversionOptions());
        }

        mappingStrategy.mapBean(bean, beanName, property, value, recordElement.getConversionOptions());
    }

    /**
     * Convert string field from file into appropriate converterName and set bean's value. This is used for delimited files only<br>
     *
     * @param inputLine the line of data read from the data file
     * @throws FlatwormParserException should any issues occur while parsing the data.
     */
    private void parseInputDelimited(String inputLine) throws FlatwormParserException {

        char split = delimiter.charAt(0);
        if (delimiter.length() == 2 && delimiter.charAt(0) == '\\') {
            char specialChar = delimiter.charAt(1);
            switch (specialChar) {
                case 't':
                    split = '\t';
                    break;
                case 'n':
                    split = '\n';
                    break;
                case 'r':
                    split = '\r';
                    break;
                case 'f':
                    split = '\f';
                    break;
                case '\\':
                    split = '\\';
                    break;
                default:
                    break;
            }
        }
        lineTokens = Util.split(inputLine, split, chrQuote);
        cleanupLineTokens();
        currentField = 0;
        doParseDelimitedInput(elements);
    }

    /**
     * Remove any record-level, {@link LineTokenIdentity} instance tokens from the list of line tokens so that they don't affect the
     * processing of the data elements. Additional, for any place where there are segment-records, the {@link LineToken} instances
     * need to have the positional information updated to reflect that they are virtually at the head of the segment-record
     * even though they aren't at the head of the line.
     */
    private void cleanupLineTokens() {
        Iterator<LineToken> lineTokenIterator = lineTokens.iterator();
        while (lineTokenIterator.hasNext()) {
            if (parentRecordDefinition.getParentRecord().matchesIdentifier(lineTokenIterator.next())) {
                lineTokenIterator.remove();
            }
        }
    }

    private void doParseDelimitedInput(List<LineElement> elements) throws FlatwormParserException {
        for (LineElement lineElement : elements) {
            if (lineElement instanceof RecordElement) {
                try {
                    RecordElement recordElement = RecordElement.class.cast(lineElement);
                    parseDelimitedRecordElement(recordElement, lineTokens.get(currentField).getToken());
                    ++currentField;
                } catch (ArrayIndexOutOfBoundsException ex) {
                    log.warn("Ran out of data on field " + (currentField + 1));
                }
            } else if (lineElement instanceof SegmentElement) {
                parseDelimitedSegmentElement(SegmentElement.class.cast(lineElement));
            }
        }
    }

    private void parseDelimitedRecordElement(RecordElement recordElement, String fieldStr) throws FlatwormParserException {
        if (!recordElement.getIgnoreField()) {
            // JBL - to keep from dup. code, moved this to a private method
            mapField(fieldStr, recordElement);
        }
    }

    private void parseDelimitedSegmentElement(SegmentElement segment) throws FlatwormParserException {
        int minCount = segment.getMinCount();
        int maxCount = segment.getMaxCount();
        if (maxCount <= 0) {
            maxCount = Integer.MAX_VALUE;
        }
        if (minCount < 0) {
            minCount = 0;
        }

        // TODO: handle allowance for a single instance that is for a field rather than a list
        String beanRef = segment.getBeanRef();
        if (!segment.matchesIdentity(lineTokens.get(currentField)) && minCount > 0) {
            log.error("Segment " + segment.getCollectionPropertyName() + " with minimum required count of " + minCount + " missing.");
        }
        int cardinality = 0;
        try {
            while (currentField < lineTokens.size() && segment.matchesIdentity(lineTokens.get(currentField))) {
                currentField++; // Advanced past the identifier token.
                if (beanRef != null) {
                    ++cardinality;
                    String parentRef = segment.getParentBeanRef();
                    if (parentRef != null) {
                        Object instance = ParseUtils.newBeanInstance(beans.get(beanRef));
                        beans.put(beanRef, instance);
                        if (cardinality > maxCount) {
                            if (segment.getCardinalityMode() == CardinalityMode.STRICT) {
                                throw new FlatwormParserException("Cardinality exceeded with mode set to STRICT");
                            } else if (segment.getCardinalityMode() != CardinalityMode.RESTRICTED) {
                                ParseUtils.addValueToCollection(segment, beans.get(parentRef), instance);
                            }
                        } else {
                            ParseUtils.addValueToCollection(segment, beans.get(parentRef), instance);
                        }
                    }
                    doParseDelimitedInput(segment.getElements());
                }
            }
        } finally {
            if (cardinality > maxCount) {
                log.error("Segment '" + segment.getCollectionPropertyName() + "' with maximum of " + maxCount
                        + " encountered actual count of " + cardinality);
            }
        }
    }
}
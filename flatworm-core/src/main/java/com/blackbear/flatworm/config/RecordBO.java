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

import com.blackbear.flatworm.FileFormat;
import com.blackbear.flatworm.converters.ConversionHelper;
import com.blackbear.flatworm.errors.FlatwormParserException;

import java.io.BufferedReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Class used to store the values from the RecordBO XML tag. Also aids in parsing and matching lines in the input file.
 */
@Data
@Slf4j
public class RecordBO {

    /**
     * Default code for a record's identity configuration.
     */
    public static final char DEFAULT_IDENTITY_CODE = '\0';

    /**
     * Identity code for records that are identified by an identity of some sort.
     */
    public static final char FIELD_IDENTITY_CODE = 'F';

    /**
     * Identity code for records that are identified by the length of the record.
     */
    public static final char LENGTH_IDENTITY_CODE = 'L';

    /**
     * Identify code for records that are identified using JavaScript.
     */
    public static final char SCRIPT_IDENTITY_CODE = 'S';

    private String name;

    private Identity recordIdentity;

    private RecordDefinitionBO recordDefinition;

    private ScriptletBO beforeScriptlet;
    private ScriptletBO afterScriptlet;
    
    private FileFormat parentFileFormat;

    public RecordBO() {
    }

    /**
     * Determine if this {@code RecordBO} instance is capable of parsing the given line.
     *
     * @param fileFormat not used at this time, for later expansion?
     * @param line       the input line from the file being parsed.
     * @return boolean does this line match according to the defined criteria?
     * @throws FlatwormParserException should the script function lack the {@code DEFAULT_SCRIPT_IDENTITY_FUNCTION_NAME} function, which should take
     *                                 one parameter, the {@link FileFormat} instance - the method should return {@code true} if the line
     *                                 should be parsed by this {@code RecordBO} instance and {@code false} if not.
     */
    public boolean matchesLine(FileFormat fileFormat, String line) throws FlatwormParserException {
        boolean matchesLine = true;
        if (recordIdentity != null) {
            matchesLine = recordIdentity.matchesIdentity(this, fileFormat, line);
        }
        return matchesLine;
    }

    /**
     * If the RecordBO's {@link Identity} is an implementation of the {@link LineTokenIdentity} then pass it the {@code lineToken} instance
     * to see if it matches with the configured identity.
     *
     * @param lineToken The {@link LineToken} to test.
     * @return {@code true} if the {@code recordIdentity} property is set to an instance of {@link LineTokenIdentity} and its {@code
     * matchesIdentity} return {@code true}. All other cases return {@code false}.
     */
    public boolean matchesIdentifier(LineToken lineToken) {
        boolean matches = false;
        if (recordIdentity instanceof LineTokenIdentity) {
            matches = LineTokenIdentity.class.cast(recordIdentity).matchesIdentity(lineToken);
        }
        return matches;
    }

    /**
     * Parse the record into the bean(s).
     *
     * @param firstLine        first line to be considered.
     * @param in               used to retrieve additional lines of input for parsing multi-line records.
     * @param conversionHelper used to help convert datatypes and format strings.
     * @return collection of beans populated with file data.
     * @throws FlatwormParserException should an error occur while parsing the data.
     */
    public Map<String, Object> parseRecord(String firstLine, BufferedReader in,
                                           ConversionHelper conversionHelper) throws FlatwormParserException {
        Map<String, Object> beans = new HashMap<>();
        try {

            Object beanObj;
            for (BeanBO bean : recordDefinition.getBeans()) {
                beanObj = bean.getBeanObjectClass().newInstance();
                beans.put(bean.getBeanName(), beanObj);
            }

            List<LineBO> lines = recordDefinition.getLines();
            String inputLine = firstLine;
            for (int i = 0; i < lines.size(); i++) {
                LineBO line = lines.get(i);
                line.parseInput(inputLine, beans, conversionHelper);
                if (i + 1 < lines.size()) {
                    inputLine = in.readLine();
                }
            }

        } catch (Exception e) {
            throw new FlatwormParserException(e.getMessage(), e);
        }
        return beans;
    }

    @Override
    public String toString() {
        return "RecordBO{" +
                "name='" + name + '\'' +
                ", recordIdentity=" + recordIdentity +
                '}';
    }
}
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

package com.blackbear.flatworm.config.impl;

import com.blackbear.flatworm.CardinalityMode;
import com.blackbear.flatworm.FileFormat;
import com.blackbear.flatworm.ParseUtils;
import com.blackbear.flatworm.Util;
import com.blackbear.flatworm.annotations.Cardinality;
import com.blackbear.flatworm.annotations.ConversionOption;
import com.blackbear.flatworm.annotations.Converter;
import com.blackbear.flatworm.annotations.DataIdentity;
import com.blackbear.flatworm.annotations.FieldIdentity;
import com.blackbear.flatworm.annotations.ForProperty;
import com.blackbear.flatworm.annotations.LengthIdentity;
import com.blackbear.flatworm.annotations.Line;
import com.blackbear.flatworm.annotations.Record;
import com.blackbear.flatworm.annotations.RecordElement;
import com.blackbear.flatworm.annotations.RecordLink;
import com.blackbear.flatworm.annotations.Scriptlet;
import com.blackbear.flatworm.annotations.SegmentElement;
import com.blackbear.flatworm.config.AnnotationConfigurationReader;
import com.blackbear.flatworm.config.BeanBO;
import com.blackbear.flatworm.config.CardinalityBO;
import com.blackbear.flatworm.config.ConfigurationValidator;
import com.blackbear.flatworm.config.ConversionOptionBO;
import com.blackbear.flatworm.config.ConverterBO;
import com.blackbear.flatworm.config.Identity;
import com.blackbear.flatworm.config.LineBO;
import com.blackbear.flatworm.config.LineElementCollection;
import com.blackbear.flatworm.config.RecordBO;
import com.blackbear.flatworm.config.RecordDefinitionBO;
import com.blackbear.flatworm.config.RecordElementBO;
import com.blackbear.flatworm.config.ScriptletBO;
import com.blackbear.flatworm.config.SegmentElementBO;
import com.blackbear.flatworm.errors.FlatwormConfigurationException;

import org.apache.commons.lang.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of the {@link AnnotationConfigurationReader} interface. Parses all annotated classes and then constructs the
 * {@link FileFormat} instance from those annotations. This class is not thread safe - a new instance should be used for each parsing
 * activity that needs to be performed.
 *
 * @author Alan Henson
 */
@Slf4j
public class DefaultAnnotationConfigurationReaderImpl implements AnnotationConfigurationReader {

    private Map<String, RecordBO> recordCache;
    private Map<Integer, LineBO> lineCache;
    private Deque<LineElementCollection> lineElementDeque;

    @Setter
    @Getter
    private boolean performValidation;

    private boolean onFirstPassFlag;

    @Getter
    private FileFormat fileFormat;

    public DefaultAnnotationConfigurationReaderImpl() {
        fileFormat = new FileFormat();
        recordCache = new HashMap<>();
        lineCache = new HashMap<>();
        lineElementDeque = new ArrayDeque<>();
        performValidation = true;
        onFirstPassFlag = false;
    }

    @Override
    public FileFormat loadConfiguration(Class<?>... classes) throws FlatwormConfigurationException {
        if (classes != null) {
            fileFormat = loadConfiguration(Arrays.asList(classes));
        }
        return fileFormat;
    }

    @Override
    public FileFormat loadConfiguration(Collection<Class<?>> classes) throws FlatwormConfigurationException {
        boolean performCleanup = false;
        if (!onFirstPassFlag) {
            // The loadConfiguration method is recursively called, we only need to perform cleanup after the very first call
            // completes - not every time it is invoked.
            performCleanup = true;
            onFirstPassFlag = true;
        }

        // Load the configuration.
        fileFormat = loadConfiguration(Collections.emptyList(), classes);

        // Sort all LineElementCollection instances.
        if (performCleanup && performValidation) {
            fileFormat.getRecords().stream()
                    .filter(record -> record.getRecordDefinition() != null)
                    .forEach(record ->
                            record.getRecordDefinition().getLines()
                                    .forEach(this::sortLineElementCollections));

            // Validate that all required metadata has been captured.
            ConfigurationValidator.validateFileFormat(fileFormat);
        }

        return fileFormat;
    }

    /**
     * Given the dot-noted package names, attempt to load all classes accessible within the given {@code getClass().getClassLoader()} or
     * {@code Thread.currentThread().getContextClassloader()} instance and then search for classes that have the {@code Record} annotation
     * and load them accordingly.
     *
     * @param packageNames The collection of package names to search.
     * @return The {@link FileFormat} instance created from any classes found with the {@link Record} annotation within the specified
     * packages.
     * @throws FlatwormConfigurationException should parsing the classpaths cause an issue or if parsing the configuration causes any
     *                                        issues.
     */
    @Override
    public FileFormat loadConfigurationByPackageNames(Collection<String> packageNames) throws FlatwormConfigurationException {
        FileFormat fileFormat = null;
        List<Class<?>> recordAnnotatedClasses = Util.findRecordAnnotatedClasses(packageNames, Record.class);
        if (!recordAnnotatedClasses.isEmpty()) {
            fileFormat = loadConfiguration(recordAnnotatedClasses);
        }
        return fileFormat;
    }

    /**
     * Attempt to find all {@link Record} annotated classes within the given {@code packageName} (and its child packages) and then load
     * those elements.
     *
     * @param packageName The package name to search.
     * @return The {@link FileFormat} instance constructed from any {@link Record} annotated classes found.
     * @throws FlatwormConfigurationException should parsing the classpath or the configuration data in the annotations fail for any
     *                                        reason.
     */
    @Override
    public FileFormat loadConfigurationByPackageName(String packageName) throws FlatwormConfigurationException {
        FileFormat fileFormat = null;
        List<Class<?>> recordAnnotatedClasses = Util.findRecordAnnotatedClasses(packageName, Record.class);
        if (!recordAnnotatedClasses.isEmpty()) {
            fileFormat = loadConfiguration(recordAnnotatedClasses);
        }
        return fileFormat;
    }

    /**
     * Load the configuration for the specified Classes, but keep track of what is being requested for retry to avoid an infinite loop.
     *
     * @param lastRetryList The last set of classes that were marked for retry.
     * @param classes       The classes to process.
     * @return This is a convenience method to make it easy to assign the {@link FileFormat} constructed to the invoker of the {@code
     * loadConfiguration} call.
     * @throws FlatwormConfigurationException should any issues arise while parsing the annotated configuration.
     */
    protected FileFormat loadConfiguration(List<Class<?>> lastRetryList, Collection<Class<?>> classes)
            throws FlatwormConfigurationException {
        List<Class<?>> classesToReprocess = new ArrayList<>();
        FlatwormConfigurationException lastException = null;

        for (Class<?> clazz : classes) {
            try {
                RecordBO recordBO = null;

                // Record Annotations.
                if (clazz.isAnnotationPresent(Record.class)) {
                    recordBO = loadRecord(clazz.getAnnotation(Record.class));
                    recordCache.put(clazz.getName(), recordBO);
                    fileFormat.addRecord(recordBO);
                } else if (clazz.isAnnotationPresent(RecordLink.class)) {
                    // See if we have loaded the RecordBO yet - if not, we'll need to try again later.
                    Class<?> recordClass = loadRecordLinkClass(clazz.getAnnotation(RecordLink.class));
                    if (recordCache.containsKey(recordClass.getName())) {
                        recordBO = recordCache.get(recordClass.getName());
                    } else {
                        classesToReprocess.add(clazz);
                    }
                }

                // We must always have a reference to the record - if we don't have it then we'll need to
                // reprocess the item later.
                if (recordBO != null) {
                    // See what field-level annotations might exist.
                    processFieldAnnotations(recordBO, clazz);
                }
            } catch (FlatwormConfigurationException e) {
                classesToReprocess.add(clazz);
                lastException = e;
            }
        }

        // See if we need to reprocess or kick-out.
        if (!classesToReprocess.isEmpty()) {
            if (isValidRetryList(lastRetryList, classesToReprocess)) {
                fileFormat = loadConfiguration(classesToReprocess, classesToReprocess);
            } else {
                String errMessage = "Unable to complete loading configuration as the following classes " +
                        "provided are not resolvable for a number of reasons. Ensure that all Records and RecordLinks are properly" +
                        " defined. All classes involved must either have a Record annotation or a RecordLink annotation." +
                        String.format("%n") + classesToReprocess.toString();
                if (lastException != null) {
                    throw new FlatwormConfigurationException(errMessage, lastException);
                } else {
                    throw new FlatwormConfigurationException(errMessage);
                }
            }
        }

        return fileFormat;
    }

    /**
     * Make sure we have a valid retry list before dropping into an infinite loop.
     *
     * @param lastRetryList      The last retry list.
     * @param classesToReprocess The retry list constructed this time around.
     * @return {@code true} if the list is valid for retrying and {@code false} if not.
     */
    private boolean isValidRetryList(List<Class<?>> lastRetryList, List<Class<?>> classesToReprocess) {
        boolean isValid = false;
        if (!classesToReprocess.isEmpty()) {
            if (lastRetryList.size() == classesToReprocess.size()) {
                for (Class<?> clazz : classesToReprocess) {
                    if (!lastRetryList.contains(clazz)) {
                        isValid = true;
                        break;
                    }
                }
            } else {
                isValid = true;
            }
        }
        return isValid;
    }

    /**
     * Get the linked {@link Record} class from the given {@link RecordLink} annotation instance.
     *
     * @param recordLink The {@link RecordLink} annotation instance from which to extract the data.
     * @return The Class instance found in the class parameter of the {@link RecordLink} instance or {@code null} if it wasn't found.
     */
    public Class<?> loadRecordLinkClass(RecordLink recordLink) {
        return recordLink.recordClass();
    }

    /**
     * For the given {@link Class}, load the {@link RecordBO} information if present.
     *
     * @param annotatedRecord The {@link Record} instance from which to extract data.
     * @return A {@link RecordBO} instance if the annotation is found and {@code null} if not.
     * @throws FlatwormConfigurationException should any issues occur with parsing the configuration elements within the annotations.
     */
    public RecordBO loadRecord(Record annotatedRecord) throws FlatwormConfigurationException {
        RecordBO record = null;

        if (annotatedRecord != null) {
            record = new RecordBO();
            record.setRecordDefinition(new RecordDefinitionBO(record));

            // Capture the identifying information.
            record.setName(annotatedRecord.name());

            // Load the data that is provided.
            record.setRecordIdentity(loadIdentity(annotatedRecord.identity()));

            // Load the converters.
            loadConverters(annotatedRecord);

            // Load the lines.
            loadLinesFromRecord(record, annotatedRecord);

            fileFormat.setEncoding(annotatedRecord.encoding());
            
            // Load the before and after scriptlets.
            // -- Before
            if (annotatedRecord.beforeReadRecordScript().enabled()) {
                record.setBeforeScriptlet(loadScriptlet(annotatedRecord.beforeReadRecordScript()));
            } else {
                record.setBeforeScriptlet(null);
            }

            // -- After
            if (annotatedRecord.afterReadRecordScript().enabled()) {
                record.setAfterScriptlet(loadScriptlet(annotatedRecord.afterReadRecordScript()));
            } else {
                record.setAfterScriptlet(null);
            }
        }
        return record;
    }

    /**
     * Load any {@link ConverterBO} instances found from the configuration provided by the {@link Converter} elements within the {@code
     * annotatedRecord} {@link Record} instance. The {@link FileFormat} instance being built up will be updated with the loaded converters.
     *
     * @param annotatedRecord The {@link Record} instance.
     * @return A {@link List} of {@link ConverterBO} instances created from whatever {@link Converter} instances have been configured within
     * the {@link Record} annotation.
     */
    public List<ConverterBO> loadConverters(Record annotatedRecord) {
        ConverterBO converter;
        List<ConverterBO> converters = new ArrayList<>();
        for (Converter annotatedConverter : annotatedRecord.converters()) {
            converter = loadConverter(annotatedConverter);
            converters.add(converter);
            fileFormat.addConverter(converter);
        }

        return converters;
    }

    /**
     * Create a {@link ConverterBO} instance from the {@link Converter} annotation.
     *
     * @param annotatedConverter The {@link Converter} annotation instance.
     * @return A contsructed {@link ConverterBO} instance from the {@link Converter} instance.
     */
    public ConverterBO loadConverter(Converter annotatedConverter) {
        return new ConverterBO(
                annotatedConverter.clazz().getName(),
                annotatedConverter.name(),
                annotatedConverter.returnType().getName(),
                annotatedConverter.methodName());
    }

    /**
     * Load the discovered the {@link Line} annotation values into the {@link RecordBO} instance (via the {@link RecordDefinitionBO}
     * property.
     *
     * @param record          The {@link RecordBO} instance being built up.
     * @param annotatedRecord The loaded {@link Record} annotation.
     * @return a {@link List} of {@link Line} instances loaded.
     * @throws FlatwormConfigurationException should parsing the annotation's values fail for any reason.
     */
    public List<LineBO> loadLinesFromRecord(RecordBO record, Record annotatedRecord) throws FlatwormConfigurationException {
        List<LineBO> lines = new ArrayList<>();
        LineBO line;
        for (Line annotatedLine : annotatedRecord.lines()) {
            line = loadLine(annotatedLine);

            record.getRecordDefinition().addLine(line);
            lines.add(line);
        }
        return lines;
    }

    /**
     * Create a {@link LineBO} instance from the {@link Line} annotation.
     *
     * @param annotatedLine The {@link Line} annotation instance.
     * @return A contsructed {@link LineBO} instance from the {@link Line} instance.
     */
    public LineBO loadLine(Line annotatedLine) throws FlatwormConfigurationException {
        LineBO line;
        line = new LineBO();
        line.setDelimiter(annotatedLine.delimiter());
        line.setQuoteChar(annotatedLine.quoteCharacter());
        line.setIndex(annotatedLine.index());
        lineCache.put(line.getIndex(), line);

        line.setRecordStartLine(annotatedLine.forProperty().isRecordStartLine());
        line.setRecordEndLine(annotatedLine.forProperty().isRecordEndLine());
        
        line.setLineIdentity(loadIdentity(annotatedLine.forProperty().identity()));
        
        // Scriptlets.
        // -- Before
        if (annotatedLine.beforeParseLine().enabled()) {
            line.setBeforeScriptlet(loadScriptlet(annotatedLine.beforeParseLine()));
        } else {
            line.setBeforeScriptlet(null);
        }

        // -- After
        if (annotatedLine.afterParseLine().enabled()) {
            line.setAfterScriptlet(loadScriptlet(annotatedLine.afterParseLine()));
        } else {
            line.setAfterScriptlet(null);
        }
        return line;
    }

    /**
     * If the {@link ForProperty} {@code enabled} flag is set to {@code true}, then parse out the contents into the given
     * {@link LineBO} instance.
     * @param forProperty The {@link ForProperty} annotation containing the data to be captured.
     * @param line The {@link LineBO} instance that will be updated with the data if the {@code enabled} flag on the {@code forProperty}
     *             parameter is {@code true}.
     * @throws FlatwormConfigurationException should parsing the data fail for any reason.
     */
    public void loadForProperty(ForProperty forProperty, LineBO line) throws FlatwormConfigurationException {
        if(forProperty.enabled()) {
            line.setRecordEndLine(forProperty.isRecordEndLine());
            line.setLineIdentity(loadIdentity(forProperty.identity()));
            line.setCardinality(loadCardinality(forProperty.cardinality()));
        }
    }
    
    /**
     * Determine which {@link com.blackbear.flatworm.config.Identity} information is present in the {@link Record} annotation and create the
     * corresponding {@link com.blackbear.flatworm.config.Identity} implementation.
     *
     * @param annotatedIdentity The {@link DataIdentity} annotation instance from which to extract data.
     * @return the {@link Identity} instance loaded.
     * @throws FlatwormConfigurationException should parsing the annotation's values fail for any reason.
     */
    public Identity loadIdentity(DataIdentity annotatedIdentity) throws FlatwormConfigurationException {
        Identity identity = null;

        // Load the identities.
        if (annotatedIdentity.lengthIdentity().enabled()) {
            identity = loadLengthIdentity(annotatedIdentity.lengthIdentity());
        } else if (annotatedIdentity.fieldIdentity().enabled()) {
            identity = loadFieldIdentity(annotatedIdentity.fieldIdentity());
        } else if (annotatedIdentity.scriptIdentity().enabled()) {
            identity = loadScriptIdentity(annotatedIdentity.scriptIdentity());
        }

        return identity;
    }

    /**
     * Load the {@link LengthIdentity} annotation configuration into a {@link LengthIdentityImpl} instance and return it.
     *
     * @param annotatedIdentity The {@link LengthIdentity} annotation instance.
     * @return the {@link LengthIdentityImpl} instance constructed.
     */
    public LengthIdentityImpl loadLengthIdentity(LengthIdentity annotatedIdentity) {
        LengthIdentityImpl lengthIdentity = new LengthIdentityImpl();
        lengthIdentity.setMinLength(annotatedIdentity.minLength());
        lengthIdentity.setMaxLength(annotatedIdentity.maxLength());
        return lengthIdentity;
    }

    /**
     * Load the {@link FieldIdentity} annotation configuration into a {@link FieldIdentityImpl} instance and return it.
     *
     * @param annotatedIdentity The {@link FieldIdentity} annotation instance.
     * @return the {@link FieldIdentityImpl} instance constructed.
     */
    public FieldIdentityImpl loadFieldIdentity(FieldIdentity annotatedIdentity) {
        FieldIdentityImpl fieldIdentity = new FieldIdentityImpl(annotatedIdentity.ignoreCase());
        fieldIdentity.setStartPosition(annotatedIdentity.startPosition());

        int maxFieldLength = Integer.MIN_VALUE;
        
        for (String matchIdentity : annotatedIdentity.matchIdentities()) {
            fieldIdentity.addMatchingString(matchIdentity);
            maxFieldLength = Math.max(maxFieldLength, matchIdentity.length());
        }
        
        if(annotatedIdentity.fieldLength() == -1) {
            fieldIdentity.setFieldLength(maxFieldLength);
        }
        else {
            fieldIdentity.setFieldLength(annotatedIdentity.fieldLength());
        }
        
        return fieldIdentity;
    }

    /**
     * Load the {@link Scriptlet} annotation configuration into a {@link ScriptIdentityImpl} instance and return it.
     *
     * @param annotatedScriptlet The {@link Scriptlet} annotation instance.
     * @return the {@link ScriptIdentityImpl} instance constructed.
     * @throws FlatwormConfigurationException should the parsing of the configuration data fail for any reason.
     */
    public ScriptIdentityImpl loadScriptIdentity(Scriptlet annotatedScriptlet) throws FlatwormConfigurationException {
        ScriptletBO scriptlet = loadScriptlet(annotatedScriptlet);
        return new ScriptIdentityImpl(scriptlet);
    }

    /**
     * Load the {@link Scriptlet} annotation configuration into a {@link ScriptletBO} instance and return it.
     *
     * @param annotatedScriptlet The {@link Scriptlet} annotation instance.
     * @return the {@link ScriptletBO} instance constructed.
     * @throws FlatwormConfigurationException should the parsing of the configuration data fail for any reason.
     */
    public ScriptletBO loadScriptlet(Scriptlet annotatedScriptlet) throws FlatwormConfigurationException {
        ScriptletBO scriptlet = new ScriptletBO(annotatedScriptlet.scriptEngine(), annotatedScriptlet.functionName());
        if (!StringUtils.isBlank(annotatedScriptlet.script())) {
            scriptlet.setScript(annotatedScriptlet.script());
        } else if (!StringUtils.isBlank(annotatedScriptlet.scriptFile())) {
            scriptlet.setScriptFile(annotatedScriptlet.scriptFile());
        }
        return scriptlet;
    }

    /**
     * Look through the given {@code clazz}'s {@link Field}s and see if there are any that have {@code annotations} that are supported by
     * flatworm and if so, process them.
     *
     * @param record The {@link RecordBO} instance that is being built up - all processed data will get loaded to this {@link RecordBO}
     *               instance.
     * @param clazz  The {@link Class} to be interrogated.
     * @throws FlatwormConfigurationException should any of the configuration data be invalid.
     */
    public void processFieldAnnotations(RecordBO record, Class<?> clazz) throws FlatwormConfigurationException {
        for (Field field : clazz.getDeclaredFields()) {
            // Check for RecordElement.
            if (field.isAnnotationPresent(RecordElement.class)) {
                loadRecordElement(record, clazz, field);
            } else if (field.isAnnotationPresent(SegmentElement.class)) {
                loadSegment(clazz, field);
            } else if (field.isAnnotationPresent(Line.class)) {
                Line annotatedLine = field.getAnnotation(Line.class);
                LineBO line = loadLine(annotatedLine);
                loadForProperty(annotatedLine.forProperty(), line);
                
                Class<?> fieldType = Util.getActualFieldType(field);
                line.getCardinality().setParentBeanRef(clazz.getName());
                line.getCardinality().setBeanRef(fieldType.getName());
                line.getCardinality().setPropertyName(field.getName());

                addBeanToRecord(clazz, record);
                addBeanToRecord(fieldType, record);
                
                if(line.getCardinality().getCardinalityMode() == CardinalityMode.AUTO_RESOLVE) {
                    line.getCardinality().setCardinalityMode(ParseUtils.resolveCardinality(field.getType()));
                }
                
                record.getRecordDefinition().addLine(line);
                
                lineElementDeque.add(line);
                processFieldAnnotations(record, fieldType);
                lineElementDeque.removeLast();
            }
        }
    }

    /**
     * Load all of the {@link RecordElement} annotation data to a {@link RecordElementBO} instance and return it. If the current {@link
     * LineElementCollection} instance can be determined then the {@link RecordElementBO} instance constructed will be added to it.
     *
     * @param record The {@link RecordBO} instance being built up.
     * @param clazz  The class that owns with the {@link Field} with the {@link RecordElement} annotation.
     * @param field  The {@link Field} that has the {@link RecordElement} annotation.
     * @return the built up {@link RecordElementBO} instance.
     * @throws FlatwormConfigurationException should any of the configuration data be invalid.
     */
    public RecordElementBO loadRecordElement(RecordBO record, Class<?> clazz, Field field) throws FlatwormConfigurationException {
        RecordElementBO recordElement = new RecordElementBO();
        RecordElement annotatedElement = field.getAnnotation(RecordElement.class);

        LineBO line = lineCache.get(annotatedElement.lineIndex());
        LineElementCollection elementCollection = lineElementDeque.isEmpty() ? line : lineElementDeque.getLast();

        try {
            // See if the bean has been registered.
            addBeanToRecord(clazz, record);

            CardinalityBO cardinality = new CardinalityBO();
            cardinality.setBeanRef(clazz.getName());
            cardinality.setPropertyName(field.getName());
            cardinality.setCardinalityMode(CardinalityMode.SINGLE);
            recordElement.setCardinality(cardinality);

            recordElement.setOrder(annotatedElement.order());
            recordElement.setConverterName(annotatedElement.converterName());
            recordElement.setTrimValue(annotatedElement.trimValue());

            if (annotatedElement.length() != -1) {
                recordElement.setFieldLength(annotatedElement.length());
            }

            if (annotatedElement.startPosition() != -1) {
                recordElement.setFieldStart(annotatedElement.startPosition());
            }
            if (annotatedElement.endPosition() != -1) {
                recordElement.setFieldEnd(annotatedElement.endPosition());
            }

            if (annotatedElement.conversionOptions().length > 0) {
                for (ConversionOption annotatedOption : annotatedElement.conversionOptions()) {
                    loadConversionOption(recordElement, annotatedOption);
                }
            }

            if (elementCollection != null) {
                elementCollection.addLineElement(recordElement);
            }
        } catch (Exception e) {
            throw new FlatwormConfigurationException(String.format(
                    "For %s::%s, line with index %s was specified, but could not be found.",
                    clazz.getName(), field.getName(), annotatedElement.lineIndex()));
        }

        return recordElement;
    }

    /**
     * Create a {@link BeanBO} entry for the given {@code clazz}.
     * @param clazz the class from which to construct a new {@link BeanBO} instance.
     * @param record the {@link RecordBO} instance to which a new {@link BeanBO} instance will be added.
     */
    public void addBeanToRecord(Class<?> clazz, RecordBO record) {
        if (!record.getRecordDefinition().getBeanMap().containsKey(clazz.getName())) {
            BeanBO bean = new BeanBO();
            bean.setBeanName(clazz.getName());
            bean.setBeanClass(clazz.getName());
            bean.setBeanObjectClass(clazz);
            record.getRecordDefinition().addBean(bean);
        }
    }

    /**
     * Load the {@link SegmentElement} metadata and associated {@link RecordElement} data (and so on) for the given {@code Field} within the
     * given {@code clazz}. Due to the tree-like structure of {@link SegmentElementBO} instances, this could result in several recursive
     * calls as the bean tree is traversed. This will add the {@link SegmentElementBO} instance to the {@link LineBO} referenced within the
     * {@link SegmentElement} annotation.
     *
     * @param clazz The class instance that owns the {@code field}.
     * @param field The {@code field} that has the {@link SegmentElement} annotation.
     * @return a constructed {@link SegmentElementBO} instance from the data found within the {@link SegmentElement} annotation.
     * @throws FlatwormConfigurationException should the annotated metadata prove invalid.
     */
    public SegmentElementBO loadSegment(Class<?> clazz, Field field) throws FlatwormConfigurationException {
        SegmentElementBO segmentElementBO = new SegmentElementBO();
        SegmentElement annotatedElement = field.getAnnotation(SegmentElement.class);

        LineBO line = lineCache.get(annotatedElement.lineIndex());
        LineElementCollection elementCollection = lineElementDeque.isEmpty() ? line : lineElementDeque.getLast();
        elementCollection.addLineElement(segmentElementBO);

        Class<?> fieldType = Util.getActualFieldType(field);

        // Need to see if this is a collection or a single attributes.
        if (fieldType != null) {
            if (fieldType.isAnnotationPresent(RecordLink.class) || fieldType.isAnnotationPresent(Record.class)) {
                lineElementDeque.add(segmentElementBO);
                FieldIdentityImpl fieldIdentity = loadFieldIdentity(annotatedElement.fieldIdentity());
                segmentElementBO.setOrder(annotatedElement.order());
                segmentElementBO.setFieldIdentity(fieldIdentity);

                CardinalityBO cardinality;
                if (Collection.class.isAssignableFrom(field.getType()) || field.getType().isArray()) {
                    segmentElementBO.setCardinality(loadCardinality(annotatedElement.cardinality()));
                    
                } else {
                    // This is a singular instance.
                    cardinality = new CardinalityBO();
                    cardinality.setMinCount(Integer.MIN_VALUE);
                    cardinality.setMaxCount(Integer.MAX_VALUE);
                    cardinality.setCardinalityMode(CardinalityMode.SINGLE);
                }

                cardinality = segmentElementBO.getCardinality();
                cardinality.setParentBeanRef(clazz.getName());
                cardinality.setPropertyName(field.getName());
                cardinality.setBeanRef(fieldType.getName());
                
                loadConfiguration(fieldType);
                lineElementDeque.removeLast();
            } else {
                throw new FlatwormConfigurationException(String.format(
                        "Class %s has a %s defined with type %s, which must have a %s or %s annotation.",
                        clazz.getName(), SegmentElement.class.getName(), fieldType.getName(),
                        RecordLink.class.getName(), Record.class.getName()));
            }
        }

        return segmentElementBO;
    }

    /**
     * Load a {@link CardinalityBO} annotation instance and return it.
     *
     * @param annotatedCardinality The {@link Cardinality} annotation instance.
     * @return The built up {@link CardinalityBO}.
     */
    public CardinalityBO loadCardinality(Cardinality annotatedCardinality) {
        CardinalityBO cardinality = new CardinalityBO();
        cardinality.setCardinalityMode(annotatedCardinality.cardinalityMode());
        cardinality.setMinCount(annotatedCardinality.mintCount());
        cardinality.setMaxCount(annotatedCardinality.maxCount());
        cardinality.setAddMethod(annotatedCardinality.addMethod());
        return cardinality;
    }

    /**
     * Load a {@link ConversionOption} annotation instance into the {@code recordElement} instance.
     *
     * @param recordElement   The {@link RecordElementBO} instance to load the {@code ConversionOption} into.
     * @param annotatedOption The {@link ConversionOption} annotation instance.
     * @return The built up {@link ConversionOptionBO} for convenience - it will already be loaded to the {@code recordElement} instance.
     */
    public ConversionOptionBO loadConversionOption(RecordElementBO recordElement, ConversionOption annotatedOption) {
        ConversionOptionBO option = new ConversionOptionBO(annotatedOption.name(), annotatedOption.option());
        recordElement.addConversionOption(annotatedOption.name(), option);
        return option;
    }

    /**
     * Ensure all {@link LineElementCollection} instances in the tree have been properly sorted - this method is recursively called to
     * navigate the full tree.
     *
     * @param lineElementCollection The {@link LineElementCollection} to sort.
     */
    protected void sortLineElementCollections(LineElementCollection lineElementCollection) {
        lineElementCollection.sortLineElements();
        lineElementCollection.getLineElements().stream()
                .filter(lineElement -> lineElement instanceof LineElementCollection)
                .map(LineElementCollection.class::cast)
                .forEach(this::sortLineElementCollections);
    }
}

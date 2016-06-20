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

package com.blackbear.flatworm;

import com.google.common.base.Joiner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;

/**
 * The <code>MatchedRecord</code> is used to return the record data from a <code>FileFormat</code> record request. It has a name field,
 * which stores the name of the record found, and a set of beans generated by parsing of the record.
 */
public class MatchedRecord {
    private Map<String, Object> beans = new HashMap<>();

    @Getter
    private String recordName;

    @Getter
    private String dataLine;

    public MatchedRecord(String name, Map<String, Object> beans, String dataLine) {
        recordName = name;
        this.beans.putAll(beans);
        this.dataLine = dataLine;
    }

    /**
     * Gets a specific bean, or null if not found.
     *
     * @param beanName The name of the bean to retrieve
     * @return The bean, or null
     */
    public Object getBean(String beanName) {
        return beans.get(beanName);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString());
        sb.append("[");
        sb.append("MatchedRecord: recordName = ");
        sb.append(recordName);
        sb.append(", beans = {");

        List<String> beanStrings = new ArrayList<>();
        beans.keySet().forEach(key -> {
            Object val = beans.get(key);
            beanStrings.add(key + "=" + val);
        });
        sb.append(Joiner.on(',').join(beanStrings));
        sb.append("}]");
        return sb.toString();
    }

}

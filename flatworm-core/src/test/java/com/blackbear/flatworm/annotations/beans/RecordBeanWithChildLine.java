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

package com.blackbear.flatworm.annotations.beans;

import com.blackbear.flatworm.annotations.DataIdentity;
import com.blackbear.flatworm.annotations.FieldIdentity;
import com.blackbear.flatworm.annotations.ForProperty;
import com.blackbear.flatworm.annotations.Line;
import com.blackbear.flatworm.annotations.Record;
import com.blackbear.flatworm.annotations.RecordElement;

import lombok.Data;

/**
 * A bean for testing the Line annotation as a field annotation.
 *
 * @author Alan Henson
 */
@Data
@Record(
        name = "RecordBeanWithChildLine",
        lines = {@Line},
        identity = @DataIdentity(
                fieldIdentity = @FieldIdentity(
                        matchIdentities = {"LB1"},
                        enabled = true
                )
        )
)
public class RecordBeanWithChildLine {

    @RecordElement
    private String valueOne;

    @RecordElement
    private String valueTwo;

    @Line(
            forProperty = @ForProperty(
                    enabled = true,
                    identity = @DataIdentity(
                            fieldIdentity = @FieldIdentity(
                                    matchIdentities = "LBC",
                                    enabled = true
                            )
                    ))
    )
    private RecordBeanTheChildLine childLine;
}

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.hadoop.pig;

import java.util.List;
import java.util.Map;

import org.apache.pig.ResourceSchema;
import org.apache.pig.ResourceSchema.ResourceFieldSchema;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.serialization.EsHadoopSerializationException;
import org.elasticsearch.hadoop.serialization.Generator;
import org.elasticsearch.hadoop.serialization.SettingsAware;
import org.elasticsearch.hadoop.serialization.builder.ValueWriter;
import org.elasticsearch.hadoop.util.FieldAlias;
import org.elasticsearch.hadoop.util.StringUtils;
import org.elasticsearch.hadoop.util.unit.Booleans;

public class PigValueWriter implements ValueWriter<PigTuple>, SettingsAware {

    private final boolean writeUnknownTypes;
    private boolean useTupleFieldNames;
    private FieldAlias alias;

    public PigValueWriter() {
        this(false);
    }

    public PigValueWriter(boolean useTupleFieldNames) {
        writeUnknownTypes = false;
        this.useTupleFieldNames = useTupleFieldNames;
        alias = new FieldAlias();
    }

    @Override
    public void setSettings(Settings settings) {
        alias = PigUtils.alias(settings);
        useTupleFieldNames = Booleans.parseBoolean(settings.getProperty(PigUtils.NAMED_TUPLE), PigUtils.NAMED_TUPLE_DEFAULT);
    }

    @Override
    public boolean write(PigTuple type, Generator generator) {
        return writeRootTuple(type.getTuple(), type.getSchema(), generator, true);
    }

    private boolean write(Object object, ResourceFieldSchema field, Generator generator) {
        byte type = (field != null ? field.getType() : DataType.findType(object));

        if (object == null) {
            generator.writeNull();
            return true;
        }

        switch (type) {
        case DataType.ERROR:
        case DataType.UNKNOWN:
            return handleUnknown(object, field, generator);
        case DataType.NULL:
            generator.writeNull();
            break;
        case DataType.BOOLEAN:
            generator.writeBoolean((Boolean) object);
            break;
        case DataType.INTEGER:
            generator.writeNumber(((Number) object).intValue());
            break;
        case DataType.LONG:
            generator.writeNumber(((Number) object).longValue());
            break;
        case DataType.FLOAT:
            generator.writeNumber(((Number) object).floatValue());
            break;
        case DataType.DOUBLE:
            generator.writeNumber(((Number) object).doubleValue());
            break;
        case DataType.BYTE:
            generator.writeNumber((Byte) object);
            break;
        case DataType.CHARARRAY:
            generator.writeString(object.toString());
            break;
        case DataType.BYTEARRAY:
            generator.writeBinary(((DataByteArray) object).get());
            break;
        // DateTime introduced in Pig 11
        case 30: //DataType.DATETIME
            generator.writeString(PigUtils.convertDateToES(object));
            break;
        // DateTime introduced in Pig 12
        case 65: //DataType.BIGINTEGER
            throw new EsHadoopSerializationException("Big integers are not supported by Elasticsearch - consider using a different type (such as string)");
        // DateTime introduced in Pig 12
        case 70: //DataType.BIGDECIMAL
            throw new EsHadoopSerializationException("Big decimals are not supported by Elasticsearch - consider using a different type (such as string)");
        case DataType.MAP:
            ResourceSchema nestedSchema = null;
            ResourceFieldSchema[] nestedFields = null;
            if (field != null) {
                nestedSchema = field.getSchema();
                nestedFields = nestedSchema.getFields();
            }

            generator.writeBeginObject();
            // Pig maps are actually String -> Object association so we can save the key right away
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                generator.writeFieldName(alias.toES(entry.getKey().toString()));
                write(entry.getValue(), (nestedFields != null ? nestedFields[0] : null), generator);
            }
            generator.writeEndObject();
            break;

        case DataType.TUPLE:
            writeTuple(object, field, generator, useTupleFieldNames, false);
            break;

        case DataType.BAG:
            ResourceFieldSchema bagType = null;
            if (field != null) {
                nestedSchema = field.getSchema();
                bagType = nestedSchema.getFields()[0];
            }

            generator.writeBeginArray();
            for (Tuple tuple : (DataBag) object) {
                write(tuple, bagType, generator);
            }
            generator.writeEndArray();
            break;
        default:
            if (writeUnknownTypes) {
                return handleUnknown(object, field, generator);
            }
            return false;
        }
        return true;
    }

    private boolean writeRootTuple(Tuple tuple, ResourceFieldSchema field, Generator generator, boolean writeTupleFieldNames) {
        return writeTuple(tuple, field, generator, writeTupleFieldNames, true);
    }

    private boolean writeTuple(Object object, ResourceFieldSchema field, Generator generator, boolean writeTupleFieldNames, boolean isRoot) {
        ResourceSchema nestedSchema = null;
        if (field != null) {
            nestedSchema = field.getSchema();
        }

        boolean result = true;
        boolean writeAsObject = isRoot || writeTupleFieldNames;

        boolean isEmpty = (field != null && nestedSchema == null);

        if (!isEmpty && nestedSchema != null) {
            // check if the tuple contains only empty fields
            boolean allEmpty = true;
            for (ResourceFieldSchema nestedField : nestedSchema.getFields()) {
                allEmpty &= (nestedField.getSchema() == null && PigUtils.isComplexType(nestedField));
            }
            isEmpty = allEmpty;
        }

        // empty tuple shortcut
        if (isEmpty) {
            if (!isRoot) {
                generator.writeBeginArray();
            }
            if (writeAsObject) {
                generator.writeBeginObject();
                generator.writeEndObject();
            }
            if (!isRoot) {
                generator.writeEndArray();
            }
            return result;
        }

        ResourceFieldSchema[] nestedFields = null;
        if (nestedSchema != null) {
            nestedFields = nestedSchema.getFields();
        }

        // use getAll instead of get(int) to avoid having to handle Exception...
        List<Object> tuples = ((Tuple) object).getAll();

        if (!isRoot) {
            generator.writeBeginArray();
        }

        if (writeAsObject) {
            generator.writeBeginObject();
        }

        int len = (nestedFields != null ? nestedFields.length : tuples.size());
        for (int i = 0; i < len; i++) {
            if (writeAsObject) {
                String name = (nestedFields != null ? nestedFields[i].getName() : null);
                // handle schemas without names
                name = (StringUtils.hasText(name) ? alias.toES(name) : Integer.toString(i));
                generator.writeFieldName(name);
            }
            result &= write(tuples.get(i), (nestedFields != null ? nestedFields[i] : null), generator);
        }
        if (writeAsObject) {
            generator.writeEndObject();
        }
        if (!isRoot) {
            generator.writeEndArray();
        }

        return result;
    }

    protected boolean handleUnknown(Object value, ResourceFieldSchema field, Generator generator) {
        return false;
    }
}
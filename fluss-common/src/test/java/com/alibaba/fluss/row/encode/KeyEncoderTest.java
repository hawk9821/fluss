/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.row.encode;

import com.alibaba.fluss.memory.MemorySegment;
import com.alibaba.fluss.row.BinaryString;
import com.alibaba.fluss.row.GenericRow;
import com.alibaba.fluss.row.InternalRow;
import com.alibaba.fluss.row.compacted.CompactedRowDeserializer;
import com.alibaba.fluss.row.compacted.CompactedRowReader;
import com.alibaba.fluss.row.indexed.IndexedRow;
import com.alibaba.fluss.row.indexed.IndexedRowTest;
import com.alibaba.fluss.row.indexed.IndexedRowWriter;
import com.alibaba.fluss.testutils.DataTestUtils;
import com.alibaba.fluss.types.DataType;
import com.alibaba.fluss.types.DataTypes;
import com.alibaba.fluss.types.RowType;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static com.alibaba.fluss.row.TestInternalRowGenerator.createAllRowType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test for {@link KeyEncoder}. */
class KeyEncoderTest {

    @Test
    void testEncode() {
        // test int, long as primary key
        final RowType rowType = RowType.of(DataTypes.INT(), DataTypes.BIGINT(), DataTypes.INT());
        InternalRow row = DataTestUtils.row(rowType, new Object[] {1, 3L, 2});
        KeyEncoder encoder = new KeyEncoder(rowType);

        byte[] bytes = encoder.encode(row);
        assertThat(bytes).isEqualTo(new byte[] {1, 3, 2});

        row = DataTestUtils.row(rowType, new Object[] {2, 5L, 6});
        bytes = encoder.encode(row);
        assertThat(bytes).isEqualTo(new byte[] {2, 5, 6});
    }

    @Test
    void testEncodeWithPartitionedKey() {
        final DataType[] dataTypes =
                new DataType[] {DataTypes.STRING(), DataTypes.BIGINT(), DataTypes.STRING()};
        final String[] fieldNames = new String[] {"partition", "f1", "f2"};
        final RowType rowType = RowType.of(dataTypes, fieldNames);

        InternalRow row = DataTestUtils.row(rowType, new Object[] {"p1", 1L, "a2"});
        List<String> pk = Arrays.asList("partition", "f2");
        List<String> partitionKeys = Collections.singletonList("partition");

        KeyEncoder keyEncoder = KeyEncoder.createKeyEncoder(rowType, pk, partitionKeys);
        byte[] encodedBytes = keyEncoder.encode(row);

        // decode it, should only get "a2"
        InternalRow encodedKey =
                decodeRow(
                        new DataType[] {
                            DataTypes.STRING().copy(false),
                        },
                        encodedBytes);
        assertThat(encodedKey.getFieldCount()).isEqualTo(1);
        assertThat(encodedKey.getString(0).toString()).isEqualTo("a2");
    }

    @Test
    void testGetKey() {
        // test int, long as primary key
        final RowType rowType =
                RowType.of(
                        DataTypes.INT(), DataTypes.BIGINT(), DataTypes.INT(), DataTypes.STRING());
        int[] pkIndexes = new int[] {0, 1, 2};
        final KeyEncoder keyEncoder = new KeyEncoder(rowType, pkIndexes);

        InternalRow row = DataTestUtils.row(rowType, new Object[] {1, 3L, 2, "a1"});

        byte[] keyBytes = keyEncoder.encode(row);
        assertThat(keyBytes).isEqualTo(new byte[] {1, 3, 2});

        // should throw exception when the column is null
        assertThatThrownBy(
                        () -> {
                            InternalRow nullRow =
                                    DataTestUtils.row(rowType, new Object[] {1, 2L, null, "a2"});
                            keyEncoder.encode(nullRow);
                        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Null value is not allowed for compacted key encoder in position 2 with type INT.");

        // test int, string as primary key
        RowType rowType1 =
                RowType.of(
                        DataTypes.STRING(),
                        DataTypes.INT(),
                        DataTypes.STRING(),
                        DataTypes.STRING());
        pkIndexes = new int[] {1, 2};
        final KeyEncoder keyEncoder1 = new KeyEncoder(rowType1, pkIndexes);
        row =
                DataTestUtils.row(
                        rowType1,
                        new Object[] {
                            BinaryString.fromString("a1"),
                            1,
                            BinaryString.fromString("a2"),
                            BinaryString.fromString("a3")
                        });
        keyBytes = keyEncoder1.encode(row);

        InternalRow keyRow =
                decodeRow(
                        new DataType[] {
                            DataTypes.INT().copy(false), DataTypes.STRING().copy(false),
                        },
                        keyBytes);
        assertThat(keyRow.getInt(0)).isEqualTo(1);
        assertThat(keyRow.getString(1).toString()).isEqualTo("a2");
    }

    @Test
    void testGetKeyForAllTypes() throws Exception {
        // just test the InternalRowKeyGetter can handle all datatypes as primary key
        RowType rowType = createAllRowType();
        DataType[] dataTypes = rowType.getChildren().toArray(new DataType[0]);
        try (IndexedRowWriter writer = IndexedRowTest.genRecordForAllTypes(dataTypes)) {
            IndexedRow row = new IndexedRow(dataTypes);
            row.pointTo(writer.segment(), 0, writer.position());
            // the last column will be null, we exclude the last column as primary key
            int[] pkIndexes = IntStream.range(0, rowType.getFieldCount() - 1).toArray();
            DataType[] keyDataTypes = new DataType[pkIndexes.length];
            for (int i = 0; i < pkIndexes.length; i++) {
                keyDataTypes[i] = dataTypes[pkIndexes[i]].copy(false);
            }

            final KeyEncoder keyEncoder = new KeyEncoder(rowType, pkIndexes);
            byte[] keyBytes = keyEncoder.encode(row);

            InternalRow keyRow = decodeRow(keyDataTypes, keyBytes);

            // get the field getter for the key field
            InternalRow.FieldGetter[] fieldGetters =
                    new InternalRow.FieldGetter[keyDataTypes.length];
            for (int i = 0; i < keyDataTypes.length; i++) {
                fieldGetters[i] = InternalRow.createFieldGetter(keyDataTypes[i], i);
            }
            // get the field from key row and origin row, and then check each field
            for (int i = 0; i < keyDataTypes.length; i++) {
                assertThat(fieldGetters[i].getFieldOrNull(keyRow))
                        .isEqualTo(fieldGetters[i].getFieldOrNull(row));
            }
        }
    }

    private InternalRow decodeRow(DataType[] dataTypes, byte[] values) {
        // use 0 as field count, then the null bits will be 0
        CompactedRowReader compactedRowReader = new CompactedRowReader(0);
        compactedRowReader.pointTo(MemorySegment.wrap(values), 0, values.length);

        CompactedRowDeserializer compactedRowDeserializer = new CompactedRowDeserializer(dataTypes);
        GenericRow genericRow = new GenericRow(dataTypes.length);
        compactedRowDeserializer.deserialize(compactedRowReader, genericRow);
        return genericRow;
    }
}
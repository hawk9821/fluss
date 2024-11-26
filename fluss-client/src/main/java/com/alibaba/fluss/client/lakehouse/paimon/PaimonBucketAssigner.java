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

package com.alibaba.fluss.client.lakehouse.paimon;

import com.alibaba.fluss.metadata.TableDescriptor;
import com.alibaba.fluss.row.InternalRow;
import com.alibaba.fluss.row.ProjectedRow;

import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.serializer.InternalRowSerializer;
import org.apache.paimon.table.sink.KeyAndBucketExtractor;
import org.apache.paimon.types.DataType;

import java.util.List;

/** A bucket assigner to align with Paimon. */
public class PaimonBucketAssigner {

    private final int bucketNum;

    private final FlussRowWrapper flussRowWrapper;
    private final InternalRowSerializer bucketKeyRowSerializer;
    private final ProjectedRow bucketKeyProjectedRow;

    public PaimonBucketAssigner(TableDescriptor tableDescriptor, int bucketNum) {
        this.bucketNum = bucketNum;
        int[] bucketKeyIndex = getBucketKeyIndex(tableDescriptor, tableDescriptor.getBucketKey());
        this.bucketKeyProjectedRow = ProjectedRow.from(bucketKeyIndex);
        DataType[] bucketKeyDataTypes =
                tableDescriptor.getSchema().toRowType().project(bucketKeyIndex).getChildren()
                        .stream()
                        .map(dataType -> dataType.accept(FlussDataTypeToPaimonDataType.INSTANCE))
                        .toArray(DataType[]::new);
        this.bucketKeyRowSerializer = new InternalRowSerializer(bucketKeyDataTypes);
        this.flussRowWrapper = new FlussRowWrapper();
    }

    private int[] getBucketKeyIndex(TableDescriptor tableDescriptor, List<String> bucketKeys) {
        List<String> columnNames = tableDescriptor.getSchema().getColumnNames();
        int[] bucketKeyIndex = new int[bucketKeys.size()];
        for (int i = 0; i < bucketKeys.size(); i++) {
            bucketKeyIndex[i] = columnNames.indexOf(bucketKeys.get(i));
        }
        return bucketKeyIndex;
    }

    public int assignBucket(InternalRow row) {
        BinaryRow bucketKey = getBucketRow(row);
        return KeyAndBucketExtractor.bucket(
                KeyAndBucketExtractor.bucketKeyHashCode(bucketKey), bucketNum);
    }

    private BinaryRow getBucketRow(InternalRow row) {
        InternalRow bucketRow = bucketKeyProjectedRow.replaceRow(row);
        // wrap to paimon's InternalRow
        flussRowWrapper.replace(bucketRow);
        return bucketKeyRowSerializer.toBinaryRow(flussRowWrapper);
    }
}
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.autoscaler.utils;

import org.apache.flink.api.common.JobID;
import org.apache.flink.autoscaler.JobAutoScalerContext;
import org.apache.flink.autoscaler.TestingAutoscalerUtils;
import org.apache.flink.autoscaler.config.AutoScalerOptions;
import org.apache.flink.autoscaler.event.TestingEventCollector;
import org.apache.flink.autoscaler.metrics.EvaluatedMetrics;
import org.apache.flink.autoscaler.metrics.EvaluatedScalingMetric;
import org.apache.flink.autoscaler.metrics.ScalingMetric;
import org.apache.flink.autoscaler.tuning.ConfigChanges;
import org.apache.flink.autoscaler.tuning.MemoryTuning;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.jobgraph.JobVertexID;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

/** Tests for {@link MemoryTuning}. */
public class MemoryTuningTest {

    TestingEventCollector<JobID, JobAutoScalerContext<JobID>> eventHandler =
            new TestingEventCollector<>();

    @Test
    void testMemoryTuning() {
        var context = TestingAutoscalerUtils.createResourceAwareContext();
        var config = context.getConfiguration();
        config.set(AutoScalerOptions.MEMORY_TUNING_ENABLED, true);
        config.set(TaskManagerOptions.NUM_TASK_SLOTS, 5);
        config.set(AutoScalerOptions.SCALING_EVENT_INTERVAL, Duration.ZERO);
        MemorySize totalMemory = MemorySize.parse("30 gb");
        config.set(TaskManagerOptions.TOTAL_PROCESS_MEMORY, totalMemory);

        var jobVertex1 = new JobVertexID();
        var jobVertex2 = new JobVertexID();
        var vertexMetrics =
                Map.of(
                        jobVertex1,
                        Map.of(
                                ScalingMetric.EXPECTED_PROCESSING_RATE,
                                        EvaluatedScalingMetric.of(50),
                                ScalingMetric.PARALLELISM, EvaluatedScalingMetric.of(50)),
                        jobVertex2,
                        Map.of(
                                ScalingMetric.EXPECTED_PROCESSING_RATE,
                                        EvaluatedScalingMetric.of(50),
                                ScalingMetric.PARALLELISM, EvaluatedScalingMetric.of(50)));

        var globalMetrics =
                Map.of(
                        ScalingMetric.HEAP_MEMORY_USED,
                        EvaluatedScalingMetric.avg(MemorySize.ofMebiBytes(5096).getBytes()),
                        ScalingMetric.MANAGED_MEMORY_USED,
                        EvaluatedScalingMetric.avg(MemorySize.ofMebiBytes(10000).getBytes()),
                        ScalingMetric.METASPACE_MEMORY_USED,
                        EvaluatedScalingMetric.avg(MemorySize.ofMebiBytes(100).getBytes()));

        var metrics = new EvaluatedMetrics(vertexMetrics, globalMetrics);

        ConfigChanges configChanges =
                MemoryTuning.tuneTaskManagerHeapMemory(context, metrics, eventHandler);
        // Test reducing overall memory
        assertThat(configChanges.getOverrides())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                TaskManagerOptions.MANAGED_MEMORY_FRACTION.key(),
                                "0.562",
                                TaskManagerOptions.NETWORK_MEMORY_FRACTION.key(),
                                "0.14",
                                TaskManagerOptions.JVM_METASPACE.key(),
                                "120 mb",
                                TaskManagerOptions.JVM_OVERHEAD_FRACTION.key(),
                                "0.099",
                                TaskManagerOptions.FRAMEWORK_HEAP_MEMORY.key(),
                                "0 bytes",
                                TaskManagerOptions.TOTAL_PROCESS_MEMORY.key(),
                                "10833048417 bytes"));

        assertThat(configChanges.getRemovals())
                .containsExactlyInAnyOrder(
                        TaskManagerOptions.TASK_HEAP_MEMORY.key(),
                        TaskManagerOptions.MANAGED_MEMORY_SIZE.key(),
                        TaskManagerOptions.MANAGED_MEMORY_SIZE
                                .fallbackKeys()
                                .iterator()
                                .next()
                                .getKey());

        assertThat(eventHandler.events.poll().getMessage())
                .startsWith(
                        "Memory tuning recommends the following configuration (automatic tuning is enabled):");

        // Test maximize managed memory
        config.set(AutoScalerOptions.MEMORY_TUNING_MAXIMIZE_MANAGED_MEMORY, true);
        configChanges = MemoryTuning.tuneTaskManagerHeapMemory(context, metrics, eventHandler);
        assertThat(configChanges.getOverrides())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                TaskManagerOptions.MANAGED_MEMORY_FRACTION.key(),
                                "0.689",
                                TaskManagerOptions.NETWORK_MEMORY_FRACTION.key(),
                                "0.1",
                                TaskManagerOptions.JVM_METASPACE.key(),
                                "120 mb",
                                TaskManagerOptions.JVM_OVERHEAD_FRACTION.key(),
                                "0.033",
                                TaskManagerOptions.FRAMEWORK_HEAP_MEMORY.key(),
                                "0 bytes",
                                TaskManagerOptions.TOTAL_PROCESS_MEMORY.key(),
                                totalMemory.toString()));

        // Test managed memory is zero
        metrics = new EvaluatedMetrics(vertexMetrics, new HashMap<>(globalMetrics));
        metrics.getGlobalMetrics()
                .put(ScalingMetric.MANAGED_MEMORY_USED, EvaluatedScalingMetric.avg(0));
        configChanges = MemoryTuning.tuneTaskManagerHeapMemory(context, metrics, eventHandler);
        assertThat(configChanges.getOverrides())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                TaskManagerOptions.MANAGED_MEMORY_FRACTION.key(),
                                "0.0",
                                TaskManagerOptions.NETWORK_MEMORY_FRACTION.key(),
                                "0.32",
                                TaskManagerOptions.JVM_METASPACE.key(),
                                "120 mb",
                                TaskManagerOptions.JVM_OVERHEAD_FRACTION.key(),
                                "0.099",
                                TaskManagerOptions.FRAMEWORK_HEAP_MEMORY.key(),
                                "0 bytes",
                                TaskManagerOptions.TOTAL_PROCESS_MEMORY.key(),
                                "10833048417 bytes"));

        // Test tuning disabled
        config.set(AutoScalerOptions.MEMORY_TUNING_ENABLED, false);
        assertThat(
                        MemoryTuning.tuneTaskManagerHeapMemory(context, metrics, eventHandler)
                                .getOverrides())
                .isEmpty();

        assertThat(eventHandler.events.poll().getMessage())
                .startsWith(
                        "Memory tuning recommends the following configuration (automatic tuning is disabled):");
    }
}
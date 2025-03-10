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

package org.apache.flink.kubernetes.operator.observer.sessionjob;

import org.apache.flink.kubernetes.operator.config.FlinkConfigManager;
import org.apache.flink.kubernetes.operator.crd.FlinkDeployment;
import org.apache.flink.kubernetes.operator.crd.FlinkSessionJob;
import org.apache.flink.kubernetes.operator.crd.status.FlinkSessionJobStatus;
import org.apache.flink.kubernetes.operator.crd.status.JobStatus;
import org.apache.flink.kubernetes.operator.observer.JobStatusObserver;
import org.apache.flink.kubernetes.operator.observer.Observer;
import org.apache.flink.kubernetes.operator.observer.SavepointObserver;
import org.apache.flink.kubernetes.operator.observer.context.VoidObserverContext;
import org.apache.flink.kubernetes.operator.reconciler.sessionjob.SessionJobReconciler;
import org.apache.flink.kubernetes.operator.service.FlinkService;
import org.apache.flink.kubernetes.operator.utils.EventRecorder;
import org.apache.flink.kubernetes.operator.utils.SavepointUtils;
import org.apache.flink.kubernetes.operator.utils.StatusRecorder;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.util.Preconditions;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** The observer of {@link FlinkSessionJob}. */
public class SessionJobObserver implements Observer<FlinkSessionJob> {

    private static final Logger LOG = LoggerFactory.getLogger(SessionJobObserver.class);
    private final FlinkConfigManager configManager;
    private final EventRecorder eventRecorder;
    private final SavepointObserver<FlinkSessionJobStatus> savepointObserver;
    private final JobStatusObserver<VoidObserverContext> jobStatusObserver;

    public SessionJobObserver(
            FlinkService flinkService,
            FlinkConfigManager configManager,
            StatusRecorder<FlinkSessionJobStatus> statusRecorder,
            EventRecorder eventRecorder) {
        this.configManager = configManager;
        this.eventRecorder = eventRecorder;
        this.savepointObserver =
                new SavepointObserver(flinkService, configManager, statusRecorder, eventRecorder);
        this.jobStatusObserver =
                new JobStatusObserver<>(flinkService, eventRecorder) {
                    @Override
                    protected void onTimeout(VoidObserverContext sessionJobObserverContext) {}

                    @Override
                    protected Optional<JobStatusMessage> filterTargetJob(
                            JobStatus status, List<JobStatusMessage> clusterJobStatuses) {
                        var jobId =
                                Preconditions.checkNotNull(
                                        status.getJobId(),
                                        "The jobID to be observed should not be null");
                        var matchedList =
                                clusterJobStatuses.stream()
                                        .filter(job -> job.getJobId().toHexString().equals(jobId))
                                        .collect(Collectors.toList());
                        Preconditions.checkArgument(
                                matchedList.size() <= 1,
                                String.format(
                                        "Expected one job for JobID: %s, but %d founded",
                                        status.getJobId(), matchedList.size()));

                        if (matchedList.size() == 0) {
                            LOG.info("No job found for JobID: {}", jobId);
                            return Optional.empty();
                        } else {
                            return Optional.of(matchedList.get(0));
                        }
                    }
                };
    }

    @Override
    public void observe(FlinkSessionJob flinkSessionJob, Context context) {
        var lastReconciledSpec =
                flinkSessionJob.getStatus().getReconciliationStatus().getLastReconciledSpec();

        if (lastReconciledSpec == null) {
            return;
        }

        Optional<FlinkDeployment> flinkDepOpt = context.getSecondaryResource(FlinkDeployment.class);

        if (!SessionJobReconciler.sessionClusterReady(flinkDepOpt)) {
            return;
        }

        var deployedConfig =
                configManager.getSessionJobConfig(flinkDepOpt.get(), flinkSessionJob.getSpec());
        var jobFound =
                jobStatusObserver.observe(
                        flinkSessionJob, deployedConfig, VoidObserverContext.INSTANCE);

        if (jobFound) {
            savepointObserver.observeSavepointStatus(flinkSessionJob, deployedConfig);
        }
        SavepointUtils.resetTriggerIfJobNotRunning(flinkSessionJob, eventRecorder);
    }
}

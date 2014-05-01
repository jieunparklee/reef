/**
 * Copyright (C) 2013 Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoft.reef.examples.ClrBridge;

import com.microsoft.reef.client.*;
import com.microsoft.reef.util.EnvironmentUtils;
import com.microsoft.tang.Configuration;
import com.microsoft.tang.annotations.NamedParameter;
import com.microsoft.tang.annotations.Parameter;
import com.microsoft.tang.annotations.Unit;
import com.microsoft.tang.exceptions.BindException;
import com.microsoft.tang.formats.ConfigurationModule;
import com.microsoft.wake.EventHandler;
import com.microsoft.wake.remote.impl.ObjectSerializableCodec;

import javax.inject.Inject;
import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clr Bridge Client.
 */
@Unit
public class JobClient {

    /**
     * Standard java logger.
     */
    private static final Logger LOG = Logger.getLogger(JobClient.class.getName());

    /**
     * Codec to translate messages to and from the job driver
     */
    private static final ObjectSerializableCodec<String> CODEC = new ObjectSerializableCodec<>();

    /**
     * Reference to the REEF framework.
     * This variable is injected automatically in the constructor.
     */
    private final REEF reef;

    /**
     * Job Driver configuration.
     */
    private Configuration driverConfiguration;

    private ConfigurationModule driverConfigModule;

    /**
     * A reference to the running job that allows client to send messages back to the job driver
     */
    private RunningJob runningJob;

    /**
     * Set to false when job driver is done.
     */
    private boolean isBusy = true;

    /**
     * Clr Bridge client.
     * Parameters are injected automatically by TANG.
     *
     * @param reef    Reference to the REEF framework.
     */
    @Inject
    JobClient(final REEF reef) throws BindException {

        this.reef = reef;

        this.driverConfigModule =
                EnvironmentUtils.addClasspath(DriverConfiguration.CONF, DriverConfiguration.GLOBAL_LIBRARIES)
                        .set(DriverConfiguration.DRIVER_IDENTIFIER, "clrBridge")
                        .set(DriverConfiguration.ON_EVALUATOR_ALLOCATED, JobDriver.AllocatedEvaluatorHandler.class)
                        .set(DriverConfiguration.ON_EVALUATOR_FAILED, JobDriver.FailedEvaluatorHandler.class)
                        .set(DriverConfiguration.ON_CONTEXT_ACTIVE, JobDriver.ActiveContextHandler.class)
                        .set(DriverConfiguration.ON_CONTEXT_CLOSED, JobDriver.ClosedContextHandler.class)
                        .set(DriverConfiguration.ON_CONTEXT_FAILED, JobDriver.FailedContextHandler.class)
                        .set(DriverConfiguration.ON_TASK_MESSAGE, JobDriver.TaskMessageHandler.class)
                        //.set(DriverConfiguration.ON_TASK_COMPLETED, JobDriver.CompletedTaskHandler.class)
                        .set(DriverConfiguration.ON_DRIVER_STARTED, JobDriver.StartHandler.class) ;
                        //.set(DriverConfiguration.ON_DRIVER_STOP, JobDriver.StopHandler.class);
    }

    private void addCLRFiles( final File folder) throws BindException{
        ConfigurationModule result = this.driverConfigModule;
        for (final File f : folder.listFiles()) {
            if (f.canRead() && f.exists() && f.isFile()) {
                result = result.set(DriverConfiguration.GLOBAL_FILES, f.getAbsolutePath());
            }
        }

        this.driverConfigModule  = result;
        this.driverConfiguration = this.driverConfigModule.build();
    }

    /**
     * Launch the job driver.
     *
     * @throws com.microsoft.tang.exceptions.BindException configuration error.
     */
    public void submit(File clrFolder) {
        try
        {
            addCLRFiles(clrFolder);
        }
        catch(final BindException e)
        {
            LOG.log(Level.FINE, "Failed to bind", e);
        }
        this.reef.submit(this.driverConfiguration);
    }

    /**
     * Receive notification from the job driver that the job had failed.
     */
    final class FailedJobHandler implements EventHandler<FailedJob> {
        @Override
        public void onNext(final FailedJob job) {
            LOG.log(Level.SEVERE, "Failed job: " + job.getId(), job.getMessage());
            stopAndNotify();
        }
    }

    /**
     * Receive notification from the job driver that the job had completed successfully.
     */
    final class CompletedJobHandler implements EventHandler<CompletedJob> {
        @Override
        public void onNext(final CompletedJob job) {
            LOG.log(Level.INFO, "Completed job: {0}", job.getId());
            stopAndNotify();
        }
    }

    /**
     * Receive notification that there was an exception thrown from the job driver.
     */
    final class RuntimeErrorHandler implements EventHandler<FailedRuntime> {
        @Override
        public void onNext(final FailedRuntime error) {
            LOG.log(Level.SEVERE, "Error in job driver: " + error, error.getMessage());
            stopAndNotify();
        }
    }

    /**
     * Notify the process in waitForCompletion() method that the main process has finished.
     */
    private synchronized void stopAndNotify() {
        this.runningJob = null;
        this.isBusy = false;
        this.notify();
    }

    /**
     * Wait for the job driver to complete. This method is called from Launcher.main()
     */
    public void waitForCompletion() {
        while (this.isBusy) {
            LOG.info("Waiting for the Job Driver to complete.");
            try {
                synchronized (this) {
                    this.wait();
                }
            } catch (final InterruptedException ex) {
                LOG.log(Level.WARNING, "Waiting for result interrupted.", ex);
            }
        }
        this.reef.close();
    }
}

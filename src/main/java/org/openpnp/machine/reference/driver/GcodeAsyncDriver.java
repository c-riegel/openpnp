/*
 * Copyright (C) 2020 <mark@makr.zone>
 * inspired and based on work
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.driver;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.machine.reference.driver.wizards.GcodeAsyncDriverSettings;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.Translations;
import org.openpnp.util.Collect;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

/**
 * The GcodeAsyncDriver extends the GcodeDriver for asynchronous communication with the controller. 
 * The goal is to increase the command throughput/decrease latency to allow for small time step motion 
 * path generation/interpolation. 
 *  
 * GcodeAsyncDriver creates a writer thread sending commands in the background while freeing the calling thread 
 * up to continue with the job. While the GcodeDriver performs hand-shaking for every command by waiting for a 
 * mandatory reply, the GcodeAsyncDriver will just blindly send commands, utilizing intermediate buffering
 * and pipelining in the communications chain, including the command buffers and look-ahead motion planning
 * in the controller itself.  
 * 
 * While the GcodeDriver (through its on-by-one hand-shaking) knows when each and every command is 
 * acknowledged by the controller, the GcodeAsyncDriver does not. The responses (mostly a stream of 
 * "ok"s) are too generic to reliably detect how many and which commands have been acknowledged. Sometimes 
 * controllers will also output additional unsolicited messages. GcodeAsyncDriver must therefore find a new 
 * way to implement hand-shaking when (and only when) it is really needed. Most importantly this is the case 
 * when OpenPnP wants to wait for the machine to physically have completed a motion sequence. GcodeAsyncDriver 
 * will therefore issue specific reporting commands where needed, making the responses uniquely recognizable, 
 * and marking the position in the response stream. 
 * 
 * FUTURE WORK:
 * 
 * To optimize the asynchronous operation, Actuator reads should also be handled differently. Often the 
 * commands to elicit sensor reading reports are shared by multiple Actuators. Therefore the responses are not 
 * distinguishable, when they arrive in the response stream. Furthermore, these commands are executed 
 * asynchronously on the controller, i.e. they create an immediate response with the readings, in parallel 
 * with any on-going motion i.e. not waiting for its completion first (which is of course a good thing). The 
 * textbook use case is 3D printing, where temperature readings must be monitored in parallel with the motion, 
 * therefore it is also the assumption that all relevant Open Source controllers provide this feature. 
 * 
 * All this prompts us to create a new way of Actuator reading. Actuators can be switched to monitoring mode 
 * and the (minimum) period of readings can be configured. GcodeAsyncDriver will then periodically insert the   
 * ACTUATOR_READ_COMMAND into the command stream. Whenever a response arrives, it is matched against all the 
 * Actuators' consolidated ACTUATOR_READ_REGEXes. Where they match, the parsed values are immediately
 * stored on the Actuators. If the Actuator is read, it will immediately return the latest value to the caller
 * speeding up the calling thread.
 * 
 * On monitoring Actuators, alarm limits can be (temporarily) set. If the readings violate the limits, an 
 * alarm status is stored. Task such as PartOn/PartOff vacuum sensing can therefore completely be done in 
 * the background, fully parallel to continuous motion, the alarm status can be checked in the next 
 * JobProcessor step. 
 * 
 */
public class GcodeAsyncDriver extends GcodeDriver {

    @Attribute(required=false)
    private long writerPollingInterval = 100;

    @Attribute(required=false)
    private long writerQueueTimeout = 60000;

    @Attribute(required=false)
    private int maxCommandsQueued = 1000;

    @Attribute(required=false)
    private boolean confirmationFlowControl = true;

    @Attribute(required=false)
    private boolean reportedLocationConfirmation = true;

    @Attribute(required = false)
    private boolean useCrc16 = false;

    @Attribute(required = false)
    private int crc16MaxRetries = 3;

    @Attribute(required = false)
    private int interpolationMaxSteps = 32;

    @Attribute(required = false)
    private int interpolationJerkSteps = 4; // relative to max acceleration

    @Attribute(required = false)
    private double interpolationTimeStep = 0.001;

    @Attribute(required = false)
    private int interpolationMinStep = 16;

    @Element(required = false)
    private Length junctionDeviation = new Length(0.02, LengthUnit.Millimeters);

    @Attribute(required = false)
    private boolean interpolationPerSegmentFeedRate = false;

    @Attribute(required = false)
    private Double interpolationMaxStepVelocity = null;

    @Attribute(required = false)
    private Double interpolationMinEncoderDistance = null;

    @Override
    public void home(Machine machine) throws Exception {
        super.home(machine);
    }

    private WriterThread writerThread;

    static public class CommandLine extends Line {
        final long timeout;

        public CommandLine(String line, long timeout) {
            super(line);
            this.timeout = timeout;
        }

        public long getTimeout() {
            return timeout;
        }
    }
    protected LinkedBlockingQueue<CommandLine> commandQueue;

    private boolean waitedForCommands;
    private int crc16SeqNum = -1;
    private java.util.concurrent.ConcurrentHashMap<Integer, String> crc16SentCommands = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean confirmationComplete;
    private volatile boolean resendRequested;

    public boolean isConfirmationFlowControl() {
        return confirmationFlowControl;
    }

    public void setConfirmationFlowControl(boolean confirmationFlowControl) {
        Object oldValue = confirmationFlowControl;
        this.confirmationFlowControl = confirmationFlowControl;
        firePropertyChange("confirmationFlowControl", oldValue, confirmationFlowControl);
    }

    public boolean isReportedLocationConfirmation() {
        return reportedLocationConfirmation;
    }

    public void setReportedLocationConfirmation(boolean reportedLocationConfirmation) {
        Object oldValue = reportedLocationConfirmation;
        this.reportedLocationConfirmation = reportedLocationConfirmation;
        firePropertyChange("reportedLocationConfirmation", oldValue, reportedLocationConfirmation);
    }

    public boolean isUseCrc16() {
        return useCrc16;
    }

    public void setUseCrc16(boolean useCrc16) {
        Object oldValue = this.useCrc16;
        this.useCrc16 = useCrc16;
        firePropertyChange("useCrc16", oldValue, useCrc16);
    }

    public int getCrc16MaxRetries() {
        return crc16MaxRetries;
    }

    public void setCrc16MaxRetries(int crc16MaxRetries) {
        Object oldValue = this.crc16MaxRetries;
        this.crc16MaxRetries = crc16MaxRetries;
        firePropertyChange("crc16MaxRetries", oldValue, crc16MaxRetries);
    }

    @Override
    public Integer getInterpolationMaxSteps() {
        return interpolationMaxSteps;
    }

    public void setInterpolationMaxSteps(Integer interpolationMaxSteps) {
        this.interpolationMaxSteps = interpolationMaxSteps;
    }

    @Override 
    public Integer getInterpolationJerkSteps() {
        return interpolationJerkSteps;
    }

    public void setInterpolationJerkSteps(Integer interpolationJerkSteps) {
        this.interpolationJerkSteps = interpolationJerkSteps;
    }

    @Override
    public Double getInterpolationTimeStep() {
        return interpolationTimeStep;
    }

    public void setInterpolationTimeStep(Double interpolationTimeStep) {
        this.interpolationTimeStep = interpolationTimeStep;
    }

    @Override
    public Integer getInterpolationMinStep() {
        return interpolationMinStep;
    }

    public void setInterpolationMinStep(Integer interpolationMinStep) {
        this.interpolationMinStep = interpolationMinStep;
    }

    @Override
    public Length getJunctionDeviation() {
        return junctionDeviation;
    }

    public void setJunctionDeviation(Length junctionDeviation) {
        this.junctionDeviation = junctionDeviation;
    }

    @Override
    public boolean getInterpolationPerSegmentFeedRate() {
        return interpolationPerSegmentFeedRate;
    }

    public void setInterpolationPerSegmentFeedRate(boolean interpolationPerSegmentFeedRate) {
        Object oldValue = this.interpolationPerSegmentFeedRate;
        this.interpolationPerSegmentFeedRate = interpolationPerSegmentFeedRate;
        firePropertyChange("interpolationPerSegmentFeedRate", oldValue, interpolationPerSegmentFeedRate);
    }

    @Override
    public Double getInterpolationMaxStepVelocity() {
        return interpolationMaxStepVelocity;
    }

    public void setInterpolationMaxStepVelocity(Double interpolationMaxStepVelocity) {
        Object oldValue = this.interpolationMaxStepVelocity;
        this.interpolationMaxStepVelocity = interpolationMaxStepVelocity;
        firePropertyChange("interpolationMaxStepVelocity", oldValue, interpolationMaxStepVelocity);
    }

    @Override
    public Double getInterpolationMinEncoderDistance() {
        return interpolationMinEncoderDistance;
    }

    public void setInterpolationMinEncoderDistance(Double interpolationMinEncoderDistance) {
        Object oldValue = this.interpolationMinEncoderDistance;
        this.interpolationMinEncoderDistance = interpolationMinEncoderDistance;
        firePropertyChange("interpolationMinEncoderDistance", oldValue, interpolationMinEncoderDistance);
    }

    @Override
    protected void connectThreads() throws Exception {
        super.connectThreads();
        commandQueue = new LinkedBlockingQueue<>(maxCommandsQueued);
        writerThread = new WriterThread();
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @Override
    protected void disconnectThreads() {
        try {
            if (writerThread != null && writerThread.isAlive()) {
                writerThread.join(3000);
            }
            commandQueue = null;
        }
        catch (Exception e) {
            Logger.error(e, "disconnect()");
        }

        super.disconnectThreads();
    }

    protected class WriterThread extends Thread {

        @Override
        public void run() {
            // Get the copies that are valid for this thread.
            LinkedBlockingQueue<CommandLine> commandQueue = GcodeAsyncDriver.this.commandQueue;
            ReferenceDriverCommunications comms = getCommunications();
            String connectionName = comms.getConnectionName();

            CommandLine lastCommand = null;
            CommandLine lastCrcCommand = null;
            int crcRetryCount = 0;
            while (!disconnectRequested) {
                CommandLine command;
                try {
                    command = commandQueue.poll(writerPollingInterval,
                            TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e1) {
                    continue;
                }
                if (command == null) {
                    continue;
                }
                try {
                    if (confirmationFlowControl && lastCommand != null) {
                        try {
                            // Wait for the previous command's ok. If CRC16 is enabled
                            // and firmware responds 'rs', resend the same command.
                            for (int attempt = 0; ; attempt++) {
                                resendRequested = false;
                                waitForConfirmation(lastCommand.toString(), lastCommand.getTimeout());
                                if (!resendRequested || !useCrc16) {
                                    break; // got ok — proceed
                                }
                                if (attempt < crc16MaxRetries) {
                                    Logger.warn("[{}] CRC16 resend {}/{}: {}",
                                            connectionName, attempt + 1, crc16MaxRetries, lastCommand);
                                    receivedConfirmationsQueue.clear();
                                    comms.writeLine(lastCommand.line);
                                    Logger.trace("[{}] >> {} (resend)", connectionName, lastCommand);
                                } else {
                                    errorResponse = new Line("CRC16 failed after "
                                            + crc16MaxRetries + " retries: " + lastCommand);
                                    break;
                                }
                            }
                        }
                        finally {
                            lastCommand = null;
                        }
                    }
                    if (command.line != null) {
                        // Set up the wanted confirmations for next time.
                        lastCommand = command;
                        receivedConfirmationsQueue.clear();
                        comms.writeLine(command.line);
                        Logger.trace("[{}] >> {}", connectionName, command);
                    }
                    else {
                        confirmationComplete = true;
                        synchronized(GcodeAsyncDriver.this) {
                            GcodeAsyncDriver.this.notify();
                        }
                        //Logger.trace("[{}] confirmation released.", getCommunications().getConnectionName());
                    }
                }
                catch (IOException e) {
                    Logger.error(e, "[{}] Write error", connectionName);
                    return;
                }
                catch (Exception e) {
                    // We probably got a timeout exception. We can't throw from the writer thread. Therefore, set 
                    // the exception as an error response, it will be reported when the driver wants to do the next step. 
                    errorResponse = new Line(e.getMessage());
                    //Logger.error("[{}] {}", getCommunications().getConnectionName(), e);
                }
            }
            Logger.trace("[{}] disconnectRequested, bye-bye.", connectionName);
        }
    }

    @Override
    protected void processResponse(Line line) {
        if (useCrc16 && line.getLine().startsWith("rs")) {
            // Extract sequence number from "rs N<seq>" and resend
            String rsLine = line.getLine().trim();
            int seq = -1;
            if (rsLine.length() > 3 && rsLine.charAt(3) == 'N') {
                try {
                    seq = Integer.parseInt(rsLine.substring(4).trim());
                } catch (NumberFormatException e) {
                    // no sequence number
                }
            }
            if (seq >= 0 && crc16SentCommands.containsKey(seq)) {
                String original = crc16SentCommands.get(seq);
                String resend = appendCrc16(original);
                Logger.warn("[CRC16] resend N{}: {}", seq, resend);
                try {
                    getCommunications().writeLine(resend);
                } catch (IOException e) {
                    Logger.error(e, "[CRC16] resend failed");
                }
            } else {
                Logger.warn("[CRC16] rs received but no sequence to resend: {}", rsLine);
            }
            return;
        }
        super.processResponse(line);
        if (useCrc16) {
            Logger.trace("[CRC16] processResponse: confirmQ size={} for line: {}",
                    receivedConfirmationsQueue.size(), line.getLine().substring(0, Math.min(40, line.getLine().length())));
        }
    }

    @Override
    protected void bailOnError() throws Exception {
        super.bailOnError();
        if (writerThread == null || ! writerThread.isAlive()) {
            throw new Exception(getCommunications().getConnectionName()+" IO Error on writing to the controller.");
        }
    }
    /**
     * Note this Override will completely change the way commands are sent and hand-shaking is done.
     * So it MUST NOT call super.sendCommand()
     */
    @Override
    public void sendCommand(String command, long timeout) throws Exception {
        if (waitedForCommands) {
            // We had a wait for commands and caller had the last chance to receive responses.
            waitedForCommands = false;
            // If the caller did not get them, clear them now.
            responseQueue.clear();
        }
        bailOnError();
        if (command == null) {
            return;
        }

        Logger.debug("[{}] commandQueue offer >> {}", getCommunications().getConnectionName(), command);
        command = preProcessCommand(command);
        if (command.isEmpty()) {
            Logger.debug("{} empty command after pre process", getCommunications().getConnectionName());
            return;
        }
        if (useCrc16) {
            int seq = ++crc16SeqNum;
            command = "N" + seq + " " + command;
            crc16SentCommands.put(seq, command);
            // Keep buffer bounded — remove old entries
            if (crc16SentCommands.size() > 100) {
                crc16SentCommands.keySet().removeIf(k -> k < seq - 50);
            }
            command = appendCrc16(command);
        }
        if (command.startsWith("$")) {
            waitForEmptyCommandQueue();
        }
        CommandLine commandLine = new CommandLine(command, timeout);
        commandQueue.offer(commandLine, writerQueueTimeout, TimeUnit.MILLISECONDS);
        if (command.startsWith("$")) {
            waitForEmptyCommandQueue();
            Logger.trace(getName()+" $-command, waiting "+dollarWaitTimeMilliseconds+"ms");
            Thread.sleep(dollarWaitTimeMilliseconds);
        }
    }

    /**
     * A crude way to at least wait for all prior commands to have been sent. 
     * This still does not guarantee that the controller has received and interpreted 
     * the commands, let alone that it is truly idle. But at least it handles typical 
     * CONNECT_COMMAND sequences properly, where this is typically needed (for $-commands
     * on TinyG).  
     * 
     * Conversely, inside the CONNECT_COMMAND, we don't want to use the waitForCompletion() 
     * method yet, as the controller might still not be properly configured for that, so 
     * we resort to this crude method. 
     * 
     * @throws InterruptedException
     */
    protected void waitForEmptyCommandQueue() {
        long t0 = System.currentTimeMillis();
        long t1 = t0 + getTimeoutAtMachineSpeed();
        while (System.currentTimeMillis() < t1) {
            if (commandQueue.size() == 0) {
                long dt = System.currentTimeMillis() - t0;
                if (dt > 1) {
                        Logger.trace("{} waited {}ms for empty command queue.", getName(), dt);
                }
                return; // --->
            }
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException e) {
            }
        }
        Logger.warn("{} timeout while waiting for empty command queue.", getName());
    }

    @Override
    public void waitForCompletion(HeadMountable hm,
                                  CompletionType completionType) throws Exception {
        waitedForCommands = true;
        if (!(completionType.isUnconditionalCoordination() 
                || isMotionPending())) {
            return;
        }
        // Issue the M400 in the super class.
        super.waitForCompletion(hm, completionType);
        if (completionType.isWaitingForDrivers()) {
            // Explicitly wait for the controller's acknowledgment here.
            long timeout = (completionType == CompletionType.WaitForStillstandIndefinitely ?
                    infinityTimeoutMilliseconds : getTimeoutAtMachineSpeed());
            if (reportedLocationConfirmation) {
                // Then make sure we get a uniquely recognizable confirmation. 
                // Confirmation is signaled with a position report.
                getReportedLocation(timeout);
            }
            else {
                drainCommandQueue(timeout);
            }
            Logger.trace("{} confirmation complete.", getName());
        }
    }

    @Override
    protected void drainCommandQueue(long timeout) throws Exception {
        // Normal confirmation report wanted. We queue a null command to drain the queue and confirm 
        // the last real command. 
        confirmationComplete = false;
        CommandLine commandLine = new CommandLine(null, 1);
        commandQueue.offer(commandLine, writerQueueTimeout, TimeUnit.MILLISECONDS);
        long t0 = System.currentTimeMillis();
        while (!confirmationComplete) {
            try {
                synchronized(this) { 
                    wait(timeout);
                }
            }
            catch (InterruptedException e) {
                Logger.warn(e, getName() +" was interrupted while waiting for completion.");
            }
            bailOnError();
        }
        long dt = System.currentTimeMillis() - t0;
        if (dt > 1) {
            Logger.trace("{} waited {}ms to drain command queue.", getName(), dt);
        }
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return Collect.concat(super.getPropertySheets(), new PropertySheet[] { 
                new PropertySheetWizardAdapter(new GcodeAsyncDriverSettings(this), Translations.getString("GCodeAsyncDriver.AdvancedSettings.title")) //$NON-NLS-1$
        });
    }
}

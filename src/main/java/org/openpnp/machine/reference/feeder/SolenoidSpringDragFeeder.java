/*
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

package org.openpnp.machine.reference.feeder;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.feeder.wizards.SolenoidSpringDragFeederConfigurationWizard;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Length;
import org.openpnp.model.Location;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.util.Utils2D;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Element;

/**
 * Drag feeder for solenoid-actuated pins with spring return.
 *
 * This subclass handles the specific timing and safety requirements of drag pins
 * where a solenoid energizes to extend the pin and a mechanical spring retracts it.
 * The key differences from {@link ReferenceDragFeeder} are:
 *
 * <h3>Early de-energize</h3>
 * The solenoid is de-energized (actuate(false)) immediately after the drag completes,
 * BEFORE the backoff move. This serves two purposes:
 * <ul>
 *   <li>Minimizes coil heating — the solenoid only stays energized for the drag duration</li>
 *   <li>Gives the spring time to begin retracting the pin during the slow backoff</li>
 * </ul>
 * In the generic ReferenceDragFeeder, the pin is retracted AFTER backoff, which assumes
 * positive control over both pin-up and pin-down. With a spring return mechanism,
 * waiting until after backoff wastes time and overheats the coil.
 *
 * <h3>Slow configurable backoff</h3>
 * The backoff move runs at a configurable speed (default 10%) rather than the feed speed.
 * This gives the spring time to fully retract the pin before the head moves away.
 * The speed is configurable via the "Backoff Speed %" field in the GUI.
 *
 * <h3>Pin state verification</h3>
 * After extending the pin, the actuator is read to verify the pin actually extended.
 * After backoff, the actuator is read to verify the pin retracted. If retraction fails,
 * a double-backoff fallback is attempted — the pin is re-extended, peel-off fires again,
 * and a second backoff at half speed gives the spring more time.
 *
 * <h3>Peel-off without deactivate</h3>
 * The peel-off actuator fires (actuate(true)) but is NOT deactivated (no actuate(false))
 * before the backoff move. This keeps the cover tape held back while the pin retracts.
 * The peel-off actuator is deactivated only after successful pin retraction.
 *
 * <p>The actuator read convention is firmware-specific: {@code actuator.read()} returns
 * {@code "1"} when the pin sensor is clear (pin retracted) and {@code "0"} when the
 * sensor is blocked (pin extended). This convention matches the Charmhigh CHM-T series
 * machines but may need adjustment for other hardware.</p>
 */
public class SolenoidSpringDragFeeder extends ReferenceDragFeeder {

    /**
     * Backoff speed as a fraction of machine speed (0.0 to 1.0).
     * Default 0.1 (10%) gives the spring time to retract the pin during backoff.
     * Configurable via the GUI "Backoff Speed %" field.
     */
    @Element(required = false)
    private double backoffSpeed = 0.1;

    @Override
    public Wizard getConfigurationWizard() {
        return new SolenoidSpringDragFeederConfigurationWizard(this);
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        Logger.debug("feed({})", nozzle);

        if (actuatorName == null) {
            throw new Exception("No actuator name set.");
        }

        Head head = nozzle.getHead();

        Actuator actuator = head.getActuatorByName(actuatorName);

        if (actuator == null) {
            throw new Exception(String.format("No Actuator found with name %s on feed Head %s",
                    actuatorName, head.getName()));
        }

        Actuator peelOffActuator = null;

        if (peelOffActuatorName != null) {
            peelOffActuator = head.getActuatorByName(peelOffActuatorName);

            if (peelOffActuator == null) {
                throw new Exception(String.format("No Actuator found with name %s on feed Head %s",
                        peelOffActuatorName, head.getName()));
            }
        }

        head.moveToSafeZ();

        if (vision.isEnabled()) {
            if (visionOffset == null) {
                Logger.debug("First feed, running vision pre-flight.");
                visionOffset = getVisionOffsets(head, location);
                feededCount = 0;
            }
            Logger.debug("visionOffsets " + visionOffset);
        }

        if (feededCount == 0) {
            Location feedStartLocation = this.feedStartLocation;
            Location feedEndLocation = this.feedEndLocation;
            if (vision.isEnabled() && visionOffset != null) {
                feedStartLocation = feedStartLocation.subtract(visionOffset);
                Logger.debug("New drag distance with visionOffset " + feedStartLocation.subtract(feedEndLocation));
            }

            // Move the actuator above the feed start location
            actuator.moveTo(feedStartLocation.derive(null, null, Double.NaN, Double.NaN));

            // Energize the solenoid to extend the pin
            actuator.actuate(true);

            // Verify pin actually extended by reading the actuator state.
            // "0" = sensor blocked = pin is extended. Retry once if not.
            actuator.waitForCompletion(CompletionType.WaitForStillstand);
            String pinState = actuator.read();
            if (!"0".equals(pinState)) {
                Logger.warn("Pin did not extend on first attempt (read: {}), retrying", pinState);
                actuator.actuate(false);
                Thread.sleep(100);
                actuator.actuate(true);
                actuator.waitForCompletion(CompletionType.WaitForStillstand);
                pinState = actuator.read();
                if (!"0".equals(pinState)) {
                    throw new Exception("Drag pin failed to extend after retry (read: " + pinState + ")");
                }
            }

            // Insert the pin (move to feed start Z)
            actuator.moveTo(feedStartLocation);

            // Drag the tape
            actuator.moveTo(feedEndLocation, feedSpeed * actuator.getHead().getMachine().getSpeed());

            // De-energize solenoid immediately after drag completes.
            // Unlike ReferenceDragFeeder which retracts the pin AFTER backoff, we de-energize
            // NOW to minimize coil heating and give the spring time to retract during backoff.
            actuator.actuate(false);

            if (peelOffActuator != null) {
                // Fire peel-off but do NOT deactivate yet — keep cover tape held back
                // while the pin retracts during backoff
                peelOffActuator.actuate(true);
            }

            // Backoff at slow configurable speed to give the spring time to retract the pin.
            // ReferenceDragFeeder uses feedSpeed here; we use backoffSpeed (default 10%).
            if (backoffDistance.getValue() != 0) {
                Location backoffLocation = Utils2D.getPointAlongLine(feedEndLocation, feedStartLocation, backoffDistance);
                actuator.moveTo(backoffLocation, backoffSpeed * actuator.getHead().getMachine().getSpeed());
            }

            // Verify pin retracted after backoff.
            // "1" = sensor clear = pin is retracted.
            actuator.waitForCompletion(CompletionType.WaitForStillstand);
            pinState = actuator.read();
            if (!"1".equals(pinState)) {
                // Double-backoff fallback: re-extend, peel-off again, backoff at half speed
                Logger.warn("Pin did not retract after backoff (read: {}), attempting double-backoff", pinState);
                actuator.actuate(true);
                Thread.sleep(50);
                actuator.actuate(false);

                if (peelOffActuator != null) {
                    peelOffActuator.actuate(true);
                }

                if (backoffDistance.getValue() != 0) {
                    // Move back to feed end and backoff again at half the backoff speed
                    Location backoffLocation = Utils2D.getPointAlongLine(feedEndLocation, feedStartLocation, backoffDistance);
                    actuator.moveTo(feedEndLocation, backoffSpeed * actuator.getHead().getMachine().getSpeed());
                    actuator.moveTo(backoffLocation, (backoffSpeed / 2.0) * actuator.getHead().getMachine().getSpeed());
                }

                actuator.waitForCompletion(CompletionType.WaitForStillstand);
                pinState = actuator.read();
                if (!"1".equals(pinState)) {
                    throw new Exception("Drag pin failed to retract after double-backoff (read: " + pinState + ")");
                }
            }

            // Pin is confirmed retracted — now deactivate peel-off
            if (peelOffActuator != null) {
                peelOffActuator.actuate(false);
            }

            if(this.isPart0402() == true){
                partPitch = new Length(2, LengthUnit.Millimeters);
            }

            if (partPitch.convertToUnits(LengthUnit.Millimeters).getValue() == 2) {
                feededCount = 2;
            }
        }
        else {
            Logger.debug("Multi parts drag feeder: skipping drag " + feededCount);
        }

        head.moveToSafeZ();

        if (feededCount > 0) {
            feededCount--;
            if (feededCount > 0) {
                partPick = new Location(LengthUnit.Millimeters, partsPitchX * feededCount,
                        partsPitchY * feededCount, 0, 0);
            }
            else {
                partPick = null;
            }
        }

        if (vision.isEnabled()) {
            visionOffset = getVisionOffsets(head, location);
            Logger.debug("final visionOffsets " + visionOffset);
            Logger.debug("Modified pickLocation {}", location.subtract(visionOffset));
        }
    }

    public double getBackoffSpeed() {
        return backoffSpeed;
    }

    public void setBackoffSpeed(double backoffSpeed) {
        this.backoffSpeed = backoffSpeed;
    }
}

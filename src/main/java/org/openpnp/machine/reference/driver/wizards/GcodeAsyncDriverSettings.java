package org.openpnp.machine.reference.driver.wizards;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

import org.openpnp.Translations;
import org.openpnp.gui.components.ComponentDecorators;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.DoubleConverter;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.machine.reference.driver.GcodeAsyncDriver;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;
import java.awt.Component;
import javax.swing.Box;
import java.awt.event.ItemListener;
import java.awt.event.ItemEvent;

public class GcodeAsyncDriverSettings extends AbstractConfigurationWizard {
    private final GcodeDriver driver;
    private JCheckBox confirmationFlowControl;
    private JTextField interpolationTimeStep;
    private JTextField interpolationMinStep;
    private JTextField interpolationMaxSteps;
    private JTextField junctionDeviation;
    private JTextField interpolationJerkSteps;
    private JCheckBox reportedLocationConfirmation;
    private JCheckBox useCrc16;
    private JTextField crc16MaxRetries;
    private JCheckBox interpolationPerSegmentFeedRate;
    private JTextField interpolationMaxStepVelocity;
    private JTextField interpolationMinEncoderDistance;

    public GcodeAsyncDriverSettings(GcodeAsyncDriver driver) {
        this.driver = driver;

        JPanel settingsPanel = new JPanel();
        settingsPanel.setBorder(new TitledBorder(null,
                Translations.getString("GcodeAsyncDriverSettings.SettingsPanel.Border.title"), //$NON-NLS-1$ 
                TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(settingsPanel);
        settingsPanel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,},
            new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,}));
        JPanel interpolationPanel = new JPanel();
        interpolationPanel.setBorder(new TitledBorder(null, 
                Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.Border.title"), //$NON-NLS-1$ 
                TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(interpolationPanel);
        interpolationPanel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,},
            new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                RowSpec.decode("fill:default:grow"),}));

        JLabel lblMaximumNumberOf = new JLabel(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MaximumNumberofStepsLabel.text")); //$NON-NLS-1$
        lblMaximumNumberOf.setToolTipText(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MaximumNumberofStepsLabel.toolTipText")); //$NON-NLS-1$
        interpolationPanel.add(lblMaximumNumberOf, "2, 2, right, default");

        interpolationMaxSteps = new JTextField();
        interpolationPanel.add(interpolationMaxSteps, "4, 2");
        interpolationMaxSteps.setColumns(10);
        
        JLabel lblMaxumNumberOf = new JLabel(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MaximumNumberofJerkStepsLabel.text")); //$NON-NLS-1$
        lblMaxumNumberOf.setToolTipText(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MaximumNumberofJerkStepsLabel.toolTipText")); //$NON-NLS-1$
        interpolationPanel.add(lblMaxumNumberOf, "2, 4, right, default");
        
        interpolationJerkSteps = new JTextField();
        interpolationPanel.add(interpolationJerkSteps, "4, 4, fill, default");
        interpolationJerkSteps.setColumns(10);

        JLabel lblInterpolationTimeStep = new JLabel(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MinimumStepTimeLabel.text")); //$NON-NLS-1$
        interpolationPanel.add(lblInterpolationTimeStep, "2, 6, right, default");
        lblInterpolationTimeStep.setToolTipText(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MinimumStepTimeLabel.toolTipText")); //$NON-NLS-1$

        interpolationTimeStep = new JTextField();
        interpolationPanel.add(interpolationTimeStep, "4, 6");
        interpolationTimeStep.setColumns(10);

        JLabel lblInterpolationMinimumTicks = new JLabel(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MinimumAxisResolutionTicksLabel.text")); //$NON-NLS-1$
        interpolationPanel.add(lblInterpolationMinimumTicks, "2, 8, right, default");
        lblInterpolationMinimumTicks.setToolTipText(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MinimumAxisResolutionTicksLabel.toolTipText")); //$NON-NLS-1$

        interpolationMinStep = new JTextField();
        interpolationPanel.add(interpolationMinStep, "4, 8");
        interpolationMinStep.setColumns(10);
        
        JLabel lblJunctionDeviation = new JLabel(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MaximumJunctionDeviationLabel.text")); //$NON-NLS-1$
        lblJunctionDeviation.setToolTipText(Translations.getString("GcodeAsyncDriverSettings.InterpolationPanel.MaximumJunctionDeviationLabel.toolTipText")); //$NON-NLS-1$
        interpolationPanel.add(lblJunctionDeviation, "2, 10, right, default");
        
        junctionDeviation = new JTextField();
        interpolationPanel.add(junctionDeviation, "4, 10, fill, default");
        junctionDeviation.setColumns(10);

        JLabel lblPerSegmentFeedRate = new JLabel("Per-Step Feed Rate?");
        lblPerSegmentFeedRate.setToolTipText("<html>"
                + "Emit the actual average velocity as F on every interpolation step,<br/>"
                + "instead of the global peak velocity on the first step only.<br/><br/>"
                + "When enabled, M204 acceleration commands are also suppressed, since<br/>"
                + "the per-step F value fully defines the stepping rate.<br/><br/>"
                + "Enable this for controllers that buffer steps (e.g. via M920) and<br/>"
                + "step at a constant rate per segment, using encoder feedback for<br/>"
                + "position detection rather than acceleration-based motion control."
                + "</html>");
        interpolationPanel.add(lblPerSegmentFeedRate, "2, 12, right, default");

        interpolationPerSegmentFeedRate = new JCheckBox("");
        interpolationPanel.add(interpolationPerSegmentFeedRate, "4, 12");

        JLabel lblMaxStepVelocity = new JLabel("Maximum Step Velocity Change [/s]");
        lblMaxStepVelocity.setToolTipText("<html>"
                + "Maximum allowed velocity change within a single interpolation step,<br/>"
                + "in machine units per second (e.g. mm/s).<br/><br/>"
                + "When set, forces additional step boundaries during constant-acceleration<br/>"
                + "phases to keep velocity jumps between consecutive steps within stepper<br/>"
                + "motor capabilities. Only effective when Per-Step Feed Rate is enabled.<br/><br/>"
                + "Leave blank to disable. Start with 50 and tune as needed."
                + "</html>");
        interpolationPanel.add(lblMaxStepVelocity, "2, 14, right, default");

        interpolationMaxStepVelocity = new JTextField();
        interpolationPanel.add(interpolationMaxStepVelocity, "4, 14, fill, default");
        interpolationMaxStepVelocity.setColumns(10);

        JLabel lblMinEncoderDistance = new JLabel("Minimum Encoder Distance [mm]");
        lblMinEncoderDistance.setToolTipText("<html>"
                + "Minimum total X/Y distance (in mm) for encoder-controlled S-curve segments.<br/>"
                + "Moves below this threshold use the legacy planner instead of M920 buffering.<br/>"
                + "This prevents tiny runout corrections during nozzle rotation from being<br/>"
                + "driven at excessive stepping rates. Leave blank to always use M920."
                + "</html>");
        interpolationPanel.add(lblMinEncoderDistance, "2, 16, right, default");

        interpolationMinEncoderDistance = new JTextField();
        interpolationPanel.add(interpolationMinEncoderDistance, "4, 16, fill, default");
        interpolationMinEncoderDistance.setColumns(10);

        JLabel lblConfirmationFlowControl = new JLabel(Translations.getString("GcodeAsyncDriverSettings.SettingsPanel.ConfirmationFlowControlLabel.text")); //$NON-NLS-1$
        lblConfirmationFlowControl.setToolTipText(Translations.getString("GcodeAsyncDriverSettings.SettingsPanel.ConfirmationFlowControlLabel.toolTipText")); //$NON-NLS-1$
        settingsPanel.add(lblConfirmationFlowControl, "2, 2, right, default");

        confirmationFlowControl = new JCheckBox("");
        confirmationFlowControl.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                if (!confirmationFlowControl.isSelected()) {
                    reportedLocationConfirmation.setSelected(true);
                }
            }
        });
        settingsPanel.add(confirmationFlowControl, "4, 2");

        JLabel lblRequestLocation = new JLabel(Translations.getString("GcodeAsyncDriverSettings.SettingsPanel.LocationConfirmationLabel.text")); //$NON-NLS-1$
        lblRequestLocation.setToolTipText(Translations.getString("GcodeAsyncDriverSettings.SettingsPanel.LocationConfirmationLabel.toolTipText")); //$NON-NLS-1$
        settingsPanel.add(lblRequestLocation, "2, 4, right, default");

        reportedLocationConfirmation = new JCheckBox("");
        reportedLocationConfirmation.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                if (!reportedLocationConfirmation.isSelected()) {
                    confirmationFlowControl.setSelected(true);
                }
            }
        });
        settingsPanel.add(reportedLocationConfirmation, "4, 4");

        JLabel lblUseCrc16 = new JLabel("Use CRC16?");
        lblUseCrc16.setToolTipText("<html>"
                + "Append CRC16 checksum to every command sent to the controller.<br/>"
                + "The controller verifies the checksum and responds with 'rs' (resend)<br/>"
                + "if corruption is detected. When Confirmation Flow Control is also enabled,<br/>"
                + "corrupted commands are automatically resent up to Max Retries times."
                + "</html>");
        settingsPanel.add(lblUseCrc16, "2, 6, right, default");

        useCrc16 = new JCheckBox("");
        settingsPanel.add(useCrc16, "4, 6");

        JLabel lblCrc16MaxRetries = new JLabel("CRC16 Max Retries");
        lblCrc16MaxRetries.setToolTipText("Maximum number of resend attempts when CRC16 verification fails.");
        settingsPanel.add(lblCrc16MaxRetries, "6, 6, right, default");

        crc16MaxRetries = new JTextField();
        settingsPanel.add(crc16MaxRetries, "8, 6, fill, default");
        crc16MaxRetries.setColumns(5);

    }

    @Override
    public void createBindings() {
        IntegerConverter intConverter = new IntegerConverter();
        //DoubleConverter doubleConverter = new DoubleConverter(Configuration.get().getLengthDisplayFormat());
        DoubleConverter doubleConverterFine = new DoubleConverter("%.6f");
        LengthConverter lengthConverter = new LengthConverter();

        addWrappedBinding(driver, "confirmationFlowControl", confirmationFlowControl, "selected");
        addWrappedBinding(driver, "reportedLocationConfirmation", reportedLocationConfirmation, "selected");
        addWrappedBinding(driver, "useCrc16", useCrc16, "selected");
        addWrappedBinding(driver, "crc16MaxRetries", crc16MaxRetries, "text", intConverter);
        ComponentDecorators.decorateWithAutoSelect(crc16MaxRetries);
        addWrappedBinding(driver, "interpolationMaxSteps", interpolationMaxSteps, "text", intConverter);
        addWrappedBinding(driver, "interpolationJerkSteps", interpolationJerkSteps, "text", intConverter);
        addWrappedBinding(driver, "interpolationTimeStep", interpolationTimeStep, "text", doubleConverterFine);
        addWrappedBinding(driver, "interpolationMinStep", interpolationMinStep, "text", intConverter);
        addWrappedBinding(driver, "junctionDeviation", junctionDeviation, "text", lengthConverter);
        addWrappedBinding(driver, "interpolationPerSegmentFeedRate", interpolationPerSegmentFeedRate, "selected");
        DoubleConverter doubleConverter = new DoubleConverter("%.1f");
        addWrappedBinding(driver, "interpolationMaxStepVelocity", interpolationMaxStepVelocity, "text", doubleConverter);
        addWrappedBinding(driver, "interpolationMinEncoderDistance", interpolationMinEncoderDistance, "text", doubleConverter);

        ComponentDecorators.decorateWithAutoSelect(interpolationMaxSteps);
        ComponentDecorators.decorateWithAutoSelect(interpolationJerkSteps);
        ComponentDecorators.decorateWithAutoSelect(interpolationTimeStep);
        ComponentDecorators.decorateWithAutoSelect(interpolationMinStep);
        ComponentDecorators.decorateWithAutoSelect(junctionDeviation);
        ComponentDecorators.decorateWithAutoSelect(interpolationMaxStepVelocity);
        ComponentDecorators.decorateWithAutoSelect(interpolationMinEncoderDistance);
    }
}

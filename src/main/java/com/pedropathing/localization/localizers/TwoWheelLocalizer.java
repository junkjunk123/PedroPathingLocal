package com.pedropathing.localization.localizers;

import com.acmerobotics.dashboard.config.Config;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.pedropathing.localization.Encoder;
import com.pedropathing.localization.Localizer;
import com.pedropathing.localization.Matrix;
import com.pedropathing.localization.Pose;
import com.pedropathing.pathgen.MathFunctions;
import com.pedropathing.pathgen.Vector;
import com.pedropathing.util.NanoTimer;
import com.pedropathing.localization.GoBildaPinpointDriver;


/**
 * This is the TwoWheelPinpointIMULocalizer class. This class extends the Localizer superclass and is a
 * localizer that uses the two wheel odometry with the Pinpoint IMU set up. The diagram below, which is modified from
 * Road Runner, shows a typical set up.
 *
 * The view is from the top of the robot looking downwards.
 *
 * left on robot is the y positive direction
 *
 * forward on robot is the x positive direction
 *
 *                         forward (x positive)
 *                                △
 *                                |
 *                                |
 *                         /--------------\
 *                         |              |
 *                         |              |
 *                         |           || |
 *  left (y positive) <--- |           || |
 *                         |     ____     |
 *                         |     ----     |
 *                         \--------------/
 *
 * @author Anyi Lin - 10158 Scott's Bots
 * @author Havish Sripada - 12808 RevAmped Robotics
 * @author Baron Henderson - 20077 The Indubitables
 * @version 1.0, 11/28/2024
 */
@Config
public class TwoWheelLocalizer extends Localizer {
    private HardwareMap hardwareMap;
    private GoBildaPinpointDriver pinpoint;
    private Pose startPose;
    private Pose displacementPose;
    private Pose currentVelocity;
    private Matrix prevRotationMatrix;
    private NanoTimer timer;
    private long deltaTimeNano;
    private Encoder forwardEncoder;
    private Encoder strafeEncoder;
    private Pose forwardEncoderPose;
    private Pose strafeEncoderPose;
    private double previousHeading;
    private double deltaRadians;
    private double totalHeading;
    public static double FORWARD_TICKS_TO_INCHES = 0.002;
    public static double STRAFE_TICKS_TO_INCHES = 0.002;

    /**
     * This creates a new TwoWheelLocalizer from a HardwareMap, with a starting Pose at (0,0)
     * facing 0 heading.
     *
     * @param map the HardwareMap
     */
    public TwoWheelLocalizer(HardwareMap map) {
        this(map, new Pose());
    }

    /**
     * This creates a new TwoWheelLocalizer from a HardwareMap and a Pose, with the Pose
     * specifying the starting pose of the localizer.
     *
     * @param map          the HardwareMap
     * @param setStartPose the Pose to start from
     */
    public TwoWheelLocalizer(HardwareMap map, Pose setStartPose) {
        // TODO: replace these with your encoder positions
        forwardEncoderPose = new Pose(0, 0.75, 0);
        strafeEncoderPose = new Pose(-6.6, 0, Math.toRadians(90));

        hardwareMap = map;

        pinpoint = hardwareMap.get(GoBildaPinpointDriver.class, "pinpoint");
        pinpoint.resetPosAndIMU();

        // TODO: replace these with your encoder ports
        forwardEncoder = new Encoder(hardwareMap.get(DcMotorEx.class, "motor_rb"));
        strafeEncoder = new Encoder(hardwareMap.get(DcMotorEx.class, "motor_lb"));

        // TODO: reverse any encoders necessary
        forwardEncoder.setDirection(Encoder.FORWARD);
        strafeEncoder.setDirection(Encoder.FORWARD);

        setStartPose(setStartPose);
        timer = new NanoTimer();
        deltaTimeNano = 1;
        displacementPose = new Pose();
        currentVelocity = new Pose();
        previousHeading = startPose.getHeading();
        deltaRadians = 0;
    }

    /**
     * This returns the current pose estimate.
     *
     * @return returns the current pose estimate as a Pose
     */
    @Override
    public Pose getPose() {
        return new Pose(startPose.getX()+displacementPose.getX(), startPose.getY()+displacementPose.getY(),displacementPose.getHeading());
    }

    /**
     * This returns the current velocity estimate.
     *
     * @return returns the current velocity estimate as a Pose
     */
    @Override
    public Pose getVelocity() {
        return currentVelocity.copy();
    }

    /**
     * This returns the current velocity estimate.
     *
     * @return returns the current velocity estimate as a Vector
     */
    @Override
    public Vector getVelocityVector() {
        return currentVelocity.getVector();
    }

    /**
     * This sets the start pose. Changing the start pose should move the robot as if all its
     * previous movements were displacing it from its new start pose.
     *
     * @param setStart the new start pose
     */
    @Override
    public void setStartPose(Pose setStart) {
        startPose = setStart;
    }

    /**
     * This sets the Matrix that contains the previous pose's heading rotation.
     *
     * @param heading the rotation of the Matrix
     */
    public void setPrevRotationMatrix(double heading) {
        prevRotationMatrix = new Matrix(3, 3);
        prevRotationMatrix.set(0, 0, Math.cos(heading));
        prevRotationMatrix.set(0, 1, -Math.sin(heading));
        prevRotationMatrix.set(1, 0, Math.sin(heading));
        prevRotationMatrix.set(1, 1, Math.cos(heading));
        prevRotationMatrix.set(2, 2, 1.0);
    }

    /**
     * This sets the current pose estimate. Changing this should just change the robot's current
     * pose estimate, not anything to do with the start pose.
     *
     * @param setPose the new current pose estimate
     */
    @Override
    public void setPose(Pose setPose) {
        displacementPose = MathFunctions.subtractPoses(setPose, startPose);
        resetEncoders();
    }

    /**
     * This updates the elapsed time timer that keeps track of time between updates, as well as the
     * change position of the Encoders and the IMU readings. Then, the robot's global change in
     * position is calculated using the pose exponential method.
     */
    @Override
    public void update() {
        deltaTimeNano = timer.getElapsedTime();
        timer.resetTimer();

        updateEncoders();
        Matrix robotDeltas = getRobotDeltas();
        Matrix globalDeltas;
        setPrevRotationMatrix(getPose().getHeading());

        Matrix transformation = new Matrix(3, 3);
        if (Math.abs(robotDeltas.get(2, 0)) < 0.001) {
            transformation.set(0, 0, 1.0 - (Math.pow(robotDeltas.get(2, 0), 2) / 6.0));
            transformation.set(0, 1, -robotDeltas.get(2, 0) / 2.0);
            transformation.set(1, 0, robotDeltas.get(2, 0) / 2.0);
            transformation.set(1, 1, 1.0 - (Math.pow(robotDeltas.get(2, 0), 2) / 6.0));
            transformation.set(2, 2, 1.0);
        } else {
            transformation.set(0, 0, Math.sin(robotDeltas.get(2, 0)) / robotDeltas.get(2, 0));
            transformation.set(0, 1, (Math.cos(robotDeltas.get(2, 0)) - 1.0) / robotDeltas.get(2, 0));
            transformation.set(1, 0, (1.0 - Math.cos(robotDeltas.get(2, 0))) / robotDeltas.get(2, 0));
            transformation.set(1, 1, Math.sin(robotDeltas.get(2, 0)) / robotDeltas.get(2, 0));
            transformation.set(2, 2, 1.0);
        }

        globalDeltas = Matrix.multiply(Matrix.multiply(prevRotationMatrix, transformation), robotDeltas);

        displacementPose.add(new Pose(globalDeltas.get(0, 0), globalDeltas.get(1, 0), globalDeltas.get(2, 0)));
        currentVelocity = new Pose(globalDeltas.get(0, 0) / (deltaTimeNano / Math.pow(10.0, 9)), globalDeltas.get(1, 0) / (deltaTimeNano / Math.pow(10.0, 9)), globalDeltas.get(2, 0) / (deltaTimeNano / Math.pow(10.0, 9)));

        totalHeading += globalDeltas.get(2, 0);
    }

    /**
     * This updates the Encoders as well as the IMU.
     */
    public void updateEncoders() {
        forwardEncoder.update();
        strafeEncoder.update();

        pinpoint.update(GoBildaPinpointDriver.readData.ONLY_UPDATE_HEADING);
        double currentHeading = startPose.getHeading() + MathFunctions.normalizeAngle(pinpoint.getHeading());
        deltaRadians = MathFunctions.getTurnDirection(previousHeading, currentHeading) * MathFunctions.getSmallestAngleDifference(currentHeading, previousHeading);
        previousHeading = currentHeading;
    }

    /**
     * This resets the Encoders.
     */
    public void resetEncoders() {
        forwardEncoder.reset();
        strafeEncoder.reset();
    }

    /**
     * This calculates the change in position from the perspective of the robot using information
     * from the Encoders and IMU.
     *
     * @return returns a Matrix containing the robot relative movement.
     */
    public Matrix getRobotDeltas() {
        Matrix returnMatrix = new Matrix(3, 1);
        // x/forward movement
        returnMatrix.set(0, 0, FORWARD_TICKS_TO_INCHES * (forwardEncoder.getDeltaPosition() - forwardEncoderPose.getY() * deltaRadians));
        //y/strafe movement
        returnMatrix.set(1, 0, STRAFE_TICKS_TO_INCHES * (strafeEncoder.getDeltaPosition() - strafeEncoderPose.getX() * deltaRadians));
        // theta/turning
        returnMatrix.set(2, 0, deltaRadians);
        return returnMatrix;
    }

    /**
     * This returns how far the robot has turned in radians, in a number not clamped between 0 and
     * 2 * pi radians. This is used for some tuning things and nothing actually within the following.
     *
     * @return returns how far the robot has turned in total, in radians.
     */
    public double getTotalHeading() {
        return totalHeading;
    }

    /**
     * This returns the multiplier applied to forward movement measurement to convert from encoder
     * ticks to inches. This is found empirically through a tuner.
     *
     * @return returns the forward ticks to inches multiplier
     */
    public double getForwardMultiplier() {
        return FORWARD_TICKS_TO_INCHES;
    }

    /**
     * This returns the multiplier applied to lateral/strafe movement measurement to convert from
     * encoder ticks to inches. This is found empirically through a tuner.
     *
     * @return returns the lateral/strafe ticks to inches multiplier
     */
    public double getLateralMultiplier() {
        return STRAFE_TICKS_TO_INCHES;
    }

    /**
     * This returns the multiplier applied to turning movement measurement to convert from encoder
     * ticks to radians. This is found empirically through a tuner.
     *
     * @return returns the turning ticks to radians multiplier
     */
    public double getTurningMultiplier() {
        return 1;
    }

    /**
     * This resets the IMU.
     */

    @Override
    public void resetIMU() throws InterruptedException {
        pinpoint.recalibrateIMU();

        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isNAN() {
        return false;
    }

    @Override
    public void setGuardAgainstFlying(boolean guardAgainstFlying) {}

    /**
     * This resets the pinpoint.
     */
    private void resetPinpoint() throws InterruptedException{
        pinpoint.resetPosAndIMU();

        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
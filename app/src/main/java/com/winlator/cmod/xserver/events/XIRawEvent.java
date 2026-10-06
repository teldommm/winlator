package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.extensions.XInputExtension;

import java.io.IOException;

/**
 * XInput2 raw event (XI_RawMotion / XI_RawButtonPress / XI_RawButtonRelease), sent as a
 * GenericEvent. Wire layout is xXIRawEvent (32 bytes), followed by the valuator mask and then
 * two FP3232 arrays: the "normal" values and the raw (unaccelerated) values.
 */
public class XIRawEvent extends Event {
    private static final byte GENERIC_EVENT = 35;
    private final short evtype;
    private final int timestamp;
    private final int detail;
    private final boolean hasMotion;
    private final double dx;
    private final double dy;
    private final double rawDx;
    private final double rawDy;

    private XIRawEvent(short evtype, int timestamp, int detail, boolean hasMotion, double dx, double dy, double rawDx, double rawDy) {
        super(GENERIC_EVENT);
        this.evtype = evtype;
        this.timestamp = timestamp;
        this.detail = detail;
        this.hasMotion = hasMotion;
        this.dx = dx;
        this.dy = dy;
        this.rawDx = rawDx;
        this.rawDy = rawDy;
    }

    public static XIRawEvent motion(int timestamp, double dx, double dy, double rawDx, double rawDy) {
        return new XIRawEvent(XInputExtension.XI_RAW_MOTION, timestamp, 0, true, dx, dy, rawDx, rawDy);
    }

    public static XIRawEvent button(int timestamp, int button, boolean pressed) {
        short evtype = pressed ? XInputExtension.XI_RAW_BUTTON_PRESS : XInputExtension.XI_RAW_BUTTON_RELEASE;
        return new XIRawEvent(evtype, timestamp, button, false, 0, 0, 0, 0);
    }

    public short getEvtype() {
        return evtype;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        // mask: 1 CARD32 with bits 0 (X) and 1 (Y); values: 2 normal + 2 raw FP3232 (8 bytes each)
        int valuatorsLen = hasMotion ? 1 : 0;
        int extraLength = hasMotion ? valuatorsLen + 4 * 2 : 0;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(XInputExtension.MAJOR_OPCODE);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(extraLength);
            outputStream.writeShort(evtype);
            outputStream.writeShort(XInputExtension.DEVICE_MASTER_POINTER);
            outputStream.writeInt(timestamp);
            outputStream.writeInt(detail);
            outputStream.writeShort(XInputExtension.DEVICE_SLAVE_POINTER);
            outputStream.writeShort((short)valuatorsLen);
            outputStream.writeInt(0); // flags
            outputStream.writeInt(0); // pad

            if (hasMotion) {
                outputStream.writeInt(0x3);
                writeFP3232(outputStream, dx);
                writeFP3232(outputStream, dy);
                writeFP3232(outputStream, rawDx);
                writeFP3232(outputStream, rawDy);
            }
        }
    }

    /* FP3232 is value = integral + frac / 2^32 with an unsigned frac, so negative values need floor(). */
    public static void writeFP3232(XOutputStream outputStream, double value) {
        double integral = Math.floor(value);
        long frac = (long)((value - integral) * 4294967296.0);
        outputStream.writeInt((int)integral);
        outputStream.writeInt((int)frac);
    }
}

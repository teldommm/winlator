package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.ClientOpcodes;
import com.winlator.cmod.xserver.Visual;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.extensions.Extension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Minimal but valid replies for core requests that expect a reply and are not otherwise
 * implemented. A client waiting for a reply would block forever, and answering with an X error
 * is not an option either: Wine passes unexpected X errors to Xlib's default handler, which
 * terminates the process. So these requests get a harmless, well-formed answer.
 *
 * The server only has TrueColor visuals, so colors are computed from the root visual's masks
 * instead of being allocated from a colormap.
 */
public abstract class FallbackRequests {
    /** Returns true if the request was answered; the caller consumes whatever was not read. */
    public static boolean handle(XClient client, byte opcode, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        switch (opcode) {
            case ClientOpcodes.LIST_PROPERTIES: listProperties(client, inputStream, outputStream); return true;
            case ClientOpcodes.GRAB_KEYBOARD: replyEmpty(client, outputStream, (byte)0); return true; // Success
            case ClientOpcodes.GET_MOTION_EVENTS: replyEmpty(client, outputStream, (byte)0); return true; // nEvents = 0
            case ClientOpcodes.QUERY_FONT: queryFont(client, outputStream); return true;
            case ClientOpcodes.QUERY_TEXT_EXTENTS: replyEmpty(client, outputStream, (byte)0); return true;
            case ClientOpcodes.LIST_FONTS_WITH_INFO: listFontsWithInfo(client, outputStream); return true;
            case ClientOpcodes.GET_FONT_PATH: replyEmpty(client, outputStream, (byte)0); return true; // nPaths = 0
            case ClientOpcodes.LIST_INSTALLED_COLORMAPS: replyEmpty(client, outputStream, (byte)0); return true; // none
            case ClientOpcodes.ALLOC_COLOR: allocColor(client, inputStream, outputStream); return true;
            case ClientOpcodes.ALLOC_NAMED_COLOR: allocNamedColor(client, inputStream, outputStream); return true;
            case ClientOpcodes.ALLOC_COLOR_CELLS: allocColorCells(client, inputStream, outputStream); return true;
            case ClientOpcodes.ALLOC_COLOR_PLANES: allocColorPlanes(client, inputStream, outputStream); return true;
            case ClientOpcodes.QUERY_COLORS: queryColors(client, inputStream, outputStream); return true;
            case ClientOpcodes.LOOKUP_COLOR: lookupColor(client, inputStream, outputStream); return true;
            case ClientOpcodes.QUERY_BEST_SIZE: queryBestSize(client, inputStream, outputStream); return true;
            case ClientOpcodes.LIST_EXTENSIONS: listExtensions(client, outputStream); return true;
            case ClientOpcodes.GET_KEYBOARD_CONTROL: getKeyboardControl(client, outputStream); return true;
            case ClientOpcodes.GET_POINTER_CONTROL: getPointerControl(client, outputStream); return true;
            case ClientOpcodes.LIST_HOSTS: replyEmpty(client, outputStream, (byte)0); return true; // disabled, no hosts
            case ClientOpcodes.SET_POINTER_MAPPING: replyEmpty(client, outputStream, (byte)0); return true; // Success
            case ClientOpcodes.SET_MODIFIER_MAPPING: replyEmpty(client, outputStream, (byte)0); return true; // Success
            default: return false;
        }
    }

    /* 32-byte reply with only the data byte set and no additional data */
    private static void replyEmpty(XClient client, XOutputStream outputStream, byte data) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, data, 0);
            outputStream.writePad(24);
        }
    }

    private static void writeHeader(XClient client, XOutputStream outputStream, byte data, int length) {
        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
        outputStream.writeByte(data);
        outputStream.writeShort(client.getSequenceNumber());
        outputStream.writeInt(length);
    }

    private static void listProperties(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        int[] atoms = window != null ? window.getPropertyAtoms() : new int[0];

        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, atoms.length);
            outputStream.writeShort((short)atoms.length);
            outputStream.writePad(22);
            for (int atom : atoms) outputStream.writeInt(atom);
        }
    }

    /* A font with no glyphs and no properties (60-byte fixed part) */
    private static void queryFont(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, 7);
            outputStream.writePad(52);
        }
    }

    /* Only the last reply of the series: name length 0 terminates the list */
    private static void listFontsWithInfo(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, 7);
            outputStream.writePad(52);
        }
    }

    /* ---------------------------------------------------------------- colors (TrueColor) */

    private static Visual getRootVisual(XClient client) {
        return client.xServer.windowManager.rootWindow.getContent().visual;
    }

    private static int toPixelComponent(int value16, int mask) {
        if (mask == 0) return 0;
        int shift = Integer.numberOfTrailingZeros(mask);
        int bits = Integer.bitCount(mask);
        return ((value16 & 0xffff) >>> (16 - bits)) << shift;
    }

    private static int fromPixelComponent(int pixel, int mask) {
        if (mask == 0) return 0;
        int shift = Integer.numberOfTrailingZeros(mask);
        int bits = Integer.bitCount(mask);
        int value = (pixel & mask) >>> shift;
        return (int)Math.round(value * 65535.0 / ((1L << bits) - 1));
    }

    private static int toPixel(Visual visual, int red, int green, int blue) {
        return toPixelComponent(red, visual.redMask) | toPixelComponent(green, visual.greenMask) | toPixelComponent(blue, visual.blueMask);
    }

    private static void allocColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4); // colormap
        int red = inputStream.readUnsignedShort();
        int green = inputStream.readUnsignedShort();
        int blue = inputStream.readUnsignedShort();

        Visual visual = getRootVisual(client);
        int pixel = toPixel(visual, red, green, blue);

        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, 0);
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.redMask));
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.greenMask));
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.blueMask));
            outputStream.writePad(2);
            outputStream.writeInt(pixel);
            outputStream.writePad(12);
        }
    }

    private static int[] readColorName(XInputStream inputStream) {
        inputStream.skip(4); // colormap
        int length = inputStream.readUnsignedShort();
        inputStream.skip(2);
        return parseColor(inputStream.readString8(length));
    }

    private static void allocNamedColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int[] rgb = readColorName(inputStream);
        Visual visual = getRootVisual(client);
        int pixel = toPixel(visual, rgb[0], rgb[1], rgb[2]);

        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, 0);
            outputStream.writeInt(pixel);
            outputStream.writeShort((short)rgb[0]);
            outputStream.writeShort((short)rgb[1]);
            outputStream.writeShort((short)rgb[2]);
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.redMask));
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.greenMask));
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.blueMask));
            outputStream.writePad(8);
        }
    }

    private static void lookupColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int[] rgb = readColorName(inputStream);
        Visual visual = getRootVisual(client);
        int pixel = toPixel(visual, rgb[0], rgb[1], rgb[2]);

        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, 0);
            outputStream.writeShort((short)rgb[0]);
            outputStream.writeShort((short)rgb[1]);
            outputStream.writeShort((short)rgb[2]);
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.redMask));
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.greenMask));
            outputStream.writeShort((short)fromPixelComponent(pixel, visual.blueMask));
            outputStream.writePad(12);
        }
    }

    /* Read-write cells do not exist on TrueColor; answer with the requested number of entries
     * (all zero) so Xlib reads exactly what it expects. Only used by palette code for
     * PseudoColor visuals, which this server never reports. */
    private static void allocColorCells(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4); // colormap
        int colors = inputStream.readUnsignedShort();
        int planes = inputStream.readUnsignedShort();

        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, colors + planes);
            outputStream.writeShort((short)colors);
            outputStream.writeShort((short)planes);
            outputStream.writePad(20);
            outputStream.writePad(4 * (colors + planes));
        }
    }

    private static void allocColorPlanes(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4); // colormap
        int colors = inputStream.readUnsignedShort();

        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, colors);
            outputStream.writeShort((short)colors);
            outputStream.writePad(2);
            outputStream.writeInt(0); // red mask
            outputStream.writeInt(0); // green mask
            outputStream.writeInt(0); // blue mask
            outputStream.writePad(8);
            outputStream.writePad(4 * colors);
        }
    }

    private static void queryColors(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4); // colormap
        int count = client.getRemainingRequestLength() / 4;
        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) pixels[i] = inputStream.readInt();

        Visual visual = getRootVisual(client);
        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, 2 * count);
            outputStream.writeShort((short)count);
            outputStream.writePad(22);
            for (int pixel : pixels) {
                outputStream.writeShort((short)fromPixelComponent(pixel, visual.redMask));
                outputStream.writeShort((short)fromPixelComponent(pixel, visual.greenMask));
                outputStream.writeShort((short)fromPixelComponent(pixel, visual.blueMask));
                outputStream.writePad(2);
            }
        }
    }

    /* Accepts "#rgb", "#rrggbb", "#rrrrggggbbbb", "rgb:r/g/b" and a few common names; anything
     * else is black. */
    static int[] parseColor(String name) {
        String value = name.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.startsWith("#") && value.length() > 1 && (value.length() - 1) % 3 == 0) {
                int digits = (value.length() - 1) / 3;
                int[] rgb = new int[3];
                for (int i = 0; i < 3; i++) {
                    rgb[i] = scaleHex(value.substring(1 + i * digits, 1 + (i + 1) * digits));
                }
                return rgb;
            }
            if (value.startsWith("rgb:")) {
                String[] parts = value.substring(4).split("/");
                if (parts.length == 3) return new int[]{scaleHex(parts[0]), scaleHex(parts[1]), scaleHex(parts[2])};
            }
        }
        catch (NumberFormatException e) {
            return new int[]{0, 0, 0};
        }

        switch (value.replace(" ", "")) {
            case "white": return new int[]{0xffff, 0xffff, 0xffff};
            case "red": return new int[]{0xffff, 0, 0};
            case "green": return new int[]{0, 0xffff, 0};
            case "blue": return new int[]{0, 0, 0xffff};
            case "yellow": return new int[]{0xffff, 0xffff, 0};
            case "cyan": return new int[]{0, 0xffff, 0xffff};
            case "magenta": return new int[]{0xffff, 0, 0xffff};
            case "gray": case "grey": return new int[]{0xbebe, 0xbebe, 0xbebe};
            default: return new int[]{0, 0, 0};
        }
    }

    /* hex digits of any length 1..4 scaled to 16 bits, as Xlib does for "#" and "rgb:" specs */
    private static int scaleHex(String hex) {
        if (hex.isEmpty() || hex.length() > 4) throw new NumberFormatException(hex);
        int value = Integer.parseInt(hex, 16);
        int max = (1 << (4 * hex.length())) - 1;
        return (int)Math.round(value * 65535.0 / max);
    }

    /* ---------------------------------------------------------------- misc */

    private static void queryBestSize(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4); // drawable
        short width = inputStream.readShort();
        short height = inputStream.readShort();

        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, 0);
            outputStream.writeShort(width);
            outputStream.writeShort(height);
            outputStream.writePad(20);
        }
    }

    private static void listExtensions(XClient client, XOutputStream outputStream) throws IOException {
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < client.xServer.extensions.size(); i++) {
            Extension extension = client.xServer.extensions.valueAt(i);
            names.add(extension.getName());
        }

        int bytes = 0;
        for (String name : names) bytes += 1 + name.length();
        int padded = (bytes + 3) & ~3;

        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)names.size(), padded / 4);
            outputStream.writePad(24);
            for (String name : names) {
                outputStream.writeByte((byte)name.length());
                outputStream.write(name.getBytes(com.winlator.cmod.xserver.XServer.LATIN1_CHARSET));
            }
            outputStream.writePad(padded - bytes);
        }
    }

    private static void getKeyboardControl(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)1, 5); // global auto repeat: on
            outputStream.writeInt(0); // led mask
            outputStream.writeByte((byte)0); // key click percent
            outputStream.writeByte((byte)50); // bell percent
            outputStream.writeShort((short)400); // bell pitch
            outputStream.writeShort((short)100); // bell duration
            outputStream.writePad(2);
            for (int i = 0; i < 32; i++) outputStream.writeByte((byte)0xff); // every key repeats
        }
    }

    private static void getPointerControl(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeHeader(client, outputStream, (byte)0, 0);
            outputStream.writeShort((short)1); // acceleration numerator
            outputStream.writeShort((short)1); // acceleration denominator
            outputStream.writeShort((short)0); // threshold
            outputStream.writePad(18);
        }
    }
}

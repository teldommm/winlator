package com.winlator.cmod.xserver.extensions;

import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadFence;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public class SyncExtension implements Extension, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -104;
    /* SYNC has 3 errors (Counter, Alarm, Fence). They used to start at 128 like MIT-SHM's
     * BadShmSeg; GLX uses 131-144 and XInputExtension 150-154. */
    public static final byte FIRST_ERROR_ID = (byte)160;
    private final SparseArray<SyncFence> fences = new SparseArray<SyncFence>();
    private XServer xserver;

    private static abstract class ClientOpcodes {
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }
    
    private class SyncFence {
        int fenceId;
        int drawableId;
        boolean triggered;
    }
    
    public SyncExtension(XServer xserver) {
        this.xserver = xserver;
        this.xserver.pixmapManager.addOnResourceLifecycleListener(this);
        this.xserver.windowManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return FIRST_ERROR_ID;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) {
                SyncFence fence = fences.get(id);
                fence.triggered = true;
                fences.notifyAll();
            }
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int drawableId = inputStream.readInt();
            int id = inputStream.readInt();

            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            boolean initiallyTriggered = inputStream.readByte() == 1;
            inputStream.skip(3);
            
            SyncFence fence = new SyncFence();
            fence.drawableId = drawableId;
            fence.fenceId = id;
            fence.triggered = initiallyTriggered;
            fences.put(id, fence);
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            SyncFence fence = fences.get(id);
            fence.triggered = true;
            fences.notifyAll();
        }
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);

            SyncFence fence = fences.get(id);
            if (!fence.triggered) throw new BadMatch();
            
            fence.triggered = false;
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            fences.delete(id);
            fences.notifyAll();
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int length = client.getRemainingRequestLength();
            int[] ids = new int[length / 4];
            int i = 0;

            while (length != 0) {
                ids[i++] = inputStream.readInt();
                length -= 4;
            }

            /* Wait with the monitor released: fences are triggered from other threads
             * (setTriggered from Present), and the old busy loop held the monitor, so a fence
             * that was not triggered yet could never become triggered and the X server's only
             * request thread spun forever. */
            while (true) {
                for (int id : ids) {
                    if (fences.indexOfKey(id) < 0) throw new BadFence(id);
                    if (fences.get(id).triggered) return;
                }
                try {
                    fences.wait();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
    
    @Override
    public void onFreeResource(XResource resource) {
        if (!(resource instanceof Pixmap) && !(resource instanceof Window)) return;

        /* iterate backwards: removing while walking forwards skipped the next fence */
        synchronized (fences) {
            for (int i = fences.size() - 1; i >= 0; i--) {
                if (fences.valueAt(i).drawableId == resource.id) fences.removeAt(i);
            }
            fences.notifyAll();
        }
    }

    /* Fence ids come from the client's resource id range, which is handed to the next client
     * after a disconnect; a leftover fence would make that client's CreateFence fail with
     * BadIdChoice. */
    @Override
    public void onClientDisconnected(XClient client) {
        synchronized (fences) {
            for (int i = fences.size() - 1; i >= 0; i--) {
                if (client.isValidResourceId(fences.keyAt(i))) fences.removeAt(i);
            }
            fences.notifyAll();
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}

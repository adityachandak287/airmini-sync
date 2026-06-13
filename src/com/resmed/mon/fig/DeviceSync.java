package com.resmed.mon.fig;

import android.bluetooth.BluetoothDevice;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.List;
import java.util.UUID;
import java.util.Date;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import org.json.JSONObject;
import org.json.JSONArray;

public class DeviceSync {
    private static android.os.IBinder bluetoothBinder = null;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: DeviceSync <4-digit-pin>");
            System.exit(1);
        }
        String pin = args[0].trim();
        if (pin.length() != 4) {
            System.err.println("Error: PIN must be 4 digits.");
            System.exit(1);
        }

        String targetFilter = null;
        if (args.length >= 2) {
            String arg1 = args[1].trim();
            if (!arg1.startsWith("{")) {
                targetFilter = arg1;
            }
        }

        String latestTimestampsJson = "{}";
        java.io.File tsFile = new java.io.File("/data/local/tmp/latest_sync.json");
        if (tsFile.exists()) {
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(tsFile);
                byte[] data = new byte[(int) tsFile.length()];
                int bytesRead = 0;
                while (bytesRead < data.length) {
                    int r = fis.read(data, bytesRead, data.length - bytesRead);
                    if (r == -1) break;
                    bytesRead += r;
                }
                fis.close();
                latestTimestampsJson = new String(data, 0, bytesRead, "UTF-8");
            } catch (Exception e) {
                System.err.println("Warning: Failed to read latest_sync.json: " + e.getMessage());
            }
        }

        try {
            // Initialize main Looper for internal handlers
            android.os.Looper.prepareMainLooper();

            // Load native decryption library figlib
            System.loadLibrary("figlib");

            System.err.println("Connecting to system Bluetooth services...");
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = smClass.getMethod("getService", String.class);
            Object binder = getServiceMethod.invoke(null, "bluetooth_manager");

            if (binder == null) {
                System.err.println("Error: Could not obtain bluetooth_manager service binder.");
                System.exit(1);
            }

            Class<?> ibmStubClass = Class.forName("android.bluetooth.IBluetoothManager$Stub");
            Method asInterfaceMethod = ibmStubClass.getMethod("asInterface", android.os.IBinder.class);
            Object iBtManager = asInterfaceMethod.invoke(null, binder);
            
            if (iBtManager == null) {
                System.err.println("Error: Failed to wrap IBluetoothManager.");
                System.exit(1);
            }

            Class<?> callbackClass = Class.forName("android.bluetooth.IBluetoothManagerCallback");
            
            final android.os.Binder realBinder = new android.os.Binder() {
                @Override
                protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
                    try {
                        if (code == 4) { // onBluetoothServiceUp
                            data.enforceInterface("android.bluetooth.IBluetoothManagerCallback");
                            android.os.IBinder ib = data.readStrongBinder();
                            synchronized (DeviceSync.class) {
                                bluetoothBinder = ib;
                                DeviceSync.class.notifyAll();
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return true;
                }
            };

            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    if (method.getName().equals("asBinder")) {
                        return realBinder;
                    }
                    return null;
                }
            };

            Object callbackProxy = Proxy.newProxyInstance(
                callbackClass.getClassLoader(),
                new Class<?>[] { callbackClass },
                handler
            );

            Method registerAdapterMethod = iBtManager.getClass().getMethod("registerAdapter", callbackClass);
            android.os.IBinder iBtBinder = (android.os.IBinder) registerAdapterMethod.invoke(iBtManager, new Object[]{callbackProxy});
            
            if (iBtBinder == null) {
                synchronized (DeviceSync.class) {
                    if (bluetoothBinder == null) {
                        DeviceSync.class.wait(5000);
                    }
                    iBtBinder = bluetoothBinder;
                }
            }
            
            if (iBtBinder == null) {
                System.err.println("Error: Failed to obtain IBluetooth binder callback.");
                System.exit(1);
            }

            Class<?> ibStubClass = Class.forName("android.bluetooth.IBluetooth$Stub");
            Method asIbInterfaceMethod = ibStubClass.getMethod("asInterface", android.os.IBinder.class);
            Object iBluetooth = asIbInterfaceMethod.invoke(null, iBtBinder);
            
            // Construct AttributionSource reflectively
            int myUid = android.os.Process.myUid();
            Class<?> asClass = Class.forName("android.content.AttributionSource");
            Class<?> asBuilderClass = Class.forName("android.content.AttributionSource$Builder");
            java.lang.reflect.Constructor<?> builderCtor = asBuilderClass.getConstructor(int.class);
            Object asBuilder = builderCtor.newInstance(myUid);
            Method setPackageNameMethod = asBuilderClass.getMethod("setPackageName", String.class);
            setPackageNameMethod.invoke(asBuilder, "com.android.shell");
            Method buildMethod = asBuilderClass.getMethod("build");
            Object attributionSource = buildMethod.invoke(asBuilder);
            
            // Query bonded devices
            System.err.println("Scanning bonded Bluetooth devices...");
            Method getBondedDevicesMethod = iBluetooth.getClass().getMethod("getBondedDevices", asClass);
            List<BluetoothDevice> devices = (List<BluetoothDevice>) getBondedDevicesMethod.invoke(iBluetooth, attributionSource);
            
            BluetoothDevice targetDevice = null;
            if (devices != null) {
                java.lang.reflect.Method getRemoteNameMethod = iBluetooth.getClass().getMethod("getRemoteName", BluetoothDevice.class, asClass);
                
                // If filter specified, look for exact or substring match
                if (targetFilter != null) {
                    for (BluetoothDevice dev : devices) {
                        String name = (String) getRemoteNameMethod.invoke(iBluetooth, dev, attributionSource);
                        if (name == null) name = "";
                        if (name.equalsIgnoreCase(targetFilter) || dev.getAddress().equalsIgnoreCase(targetFilter) || 
                            name.toLowerCase().contains(targetFilter.toLowerCase())) {
                            targetDevice = dev;
                            System.err.println("Found matching device via filter: " + name + " [" + dev.getAddress() + "]");
                            break;
                        }
                    }
                } else {
                    // Try to find "588020" first as preferred
                    for (BluetoothDevice dev : devices) {
                        String name = (String) getRemoteNameMethod.invoke(iBluetooth, dev, attributionSource);
                        if (name != null && name.toLowerCase().contains("588020")) {
                            targetDevice = dev;
                            System.err.println("Found preferred ResMed device (588020): " + name + " [" + dev.getAddress() + "]");
                            break;
                        }
                    }
                    
                    // Fallback to any ResMed or AirMini device (case-insensitive)
                    if (targetDevice == null) {
                        for (BluetoothDevice dev : devices) {
                            String name = (String) getRemoteNameMethod.invoke(iBluetooth, dev, attributionSource);
                            if (name != null) {
                                String lowerName = name.toLowerCase();
                                if (lowerName.contains("resmed") || lowerName.contains("airmini")) {
                                    targetDevice = dev;
                                    System.err.println("Found ResMed/AirMini device: " + name + " [" + dev.getAddress() + "]");
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            
            if (targetDevice == null) {
                System.err.println("Error: No paired ResMed/AirMini device found. Please pair the CPAP machine to the phone first.");
                System.exit(1);
            }

            // Get Bluetooth Socket Manager
            Method getSocketManagerMethod = iBluetooth.getClass().getMethod("getSocketManager");
            Object iBtSocketManager = getSocketManagerMethod.invoke(iBluetooth);
            if (iBtSocketManager == null) {
                System.err.println("Error: Could not obtain IBluetoothSocketManager.");
                System.exit(1);
            }

            // Connect to RFCOMM socket
            System.err.println("Connecting to RFCOMM socket...");
            ParcelUuid sppUuid = new ParcelUuid(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
            Method connectSocketMethod = iBtSocketManager.getClass().getMethod("connectSocket", 
                BluetoothDevice.class, int.class, ParcelUuid.class, int.class, int.class, asClass);
            
            ParcelFileDescriptor pfd = (ParcelFileDescriptor) connectSocketMethod.invoke(iBtSocketManager, 
                targetDevice, 
                1, // TYPE_RFCOMM
                sppUuid, 
                -1, // port
                3, // securityFlags (SECURE)
                attributionSource
            );

            if (pfd == null) {
                System.err.println("Error: Failed to connect (pfd is null). Make sure the AirMini is turned on and in range.");
                System.exit(1);
            }

            System.err.println("RFCOMM link established. Waiting 3 seconds for Bluetooth link to settle...");
            Thread.sleep(3000);

            FileDescriptor fd = pfd.getFileDescriptor();
            FileInputStream in = new FileInputStream(fd);
            FileOutputStream out = new FileOutputStream(fd);

            // Consume the 32-byte system Bluetooth socket handshake
            byte[] sysHandshake = new byte[32];
            int sysRead = 0;
            while (sysRead < 32) {
                int r = in.read(sysHandshake, sysRead, 32 - sysRead);
                if (r == -1) throw new IOException("EOF reading system socket handshake");
                sysRead += r;
            }
            System.err.println("Authenticating and pairing with CPAP machine...");
            FigWrapper wrapper = FigWrapper.getUnencryptedInstance();
            String pairReq = "{\"jsonrpc\":\"2.0\",\"method\":\"GetPairKey\",\"params\":{\"passKey\":\"" + pin + "\"},\"id\":1}";
            byte[] encoded = wrapper.encodePacket(pairReq);
            out.write(encoded);
            out.flush();

            String decoded = null;
            while (decoded == null) {
                byte[] resBytes = readFullPacket(in);
                decoded = wrapper.decodePacket(resBytes);
                
                byte[] txData = wrapper.pullTxData();
                if (txData != null && txData.length > 0) {
                    out.write(txData);
                    out.flush();
                }
            }

            JSONObject pairRes = new JSONObject(decoded);
            if (pairRes.has("error")) {
                System.err.println("Error: Pairing failed: " + pairRes.get("error").toString());
                pfd.close();
                System.exit(1);
            }

            JSONObject result = pairRes.getJSONObject("result");
            String sessionKeyHex = result.getString("sessionKey");
            System.err.println("Session key negotiated successfully. Handshake complete.");

            byte[] sessionKey = hexToBytes(sessionKeyHex);
            FigWrapper encWrapper = FigWrapper.getEncryptedInstance(sessionKey);

            String[] dataIds = {
                "UsageEvents-TherapyStatusEvent",
                "TherapyEvents-RespiratoryEvent",
                "TherapyOneMinutePeriodic-InspiratoryPressure",
                "TherapyOneMinutePeriodic-Leak"
            };

            JSONObject latestTimes = new JSONObject(latestTimestampsJson);
            JSONObject allData = new JSONObject();

            for (String dataId : dataIds) {
                String fromTime = "2000-01-01T00:00:00.000Z";
                if (latestTimes.has(dataId)) {
                    fromTime = latestTimes.getString(dataId);
                }
                System.err.println("Syncing " + dataId + " since " + fromTime + "...");
                
                String loggedDataJson = "{\"jsonrpc\":\"2.0\",\"method\":\"GetLoggedData\",\"params\":[{\"dataId\":\"" + dataId + "\",\"fromTime\":\"" + fromTime + "\"}],\"id\":2}";
                byte[] reqBytes = encWrapper.encodePacket(loggedDataJson);
                out.write(reqBytes);
                out.flush();

                JSONArray dataArr = new JSONArray();
                allData.put(dataId, dataArr);

                int expectedStreamId = -1;
                boolean streamFinished = false;
                int totalItems = 0;

                while (!streamFinished) {
                    byte[] packetBytes = readFullPacket(in);
                    String decodedJsonStr = encWrapper.decodePacket(packetBytes);
                    if (decodedJsonStr == null || decodedJsonStr.equals("null")) {
                        continue;
                    }

                    JSONObject resObj = new JSONObject(decodedJsonStr);
                    if (resObj.has("error")) {
                        System.err.println("Error from device: " + resObj.get("error").toString());
                        break;
                    }

                    if (resObj.optInt("id", -1) == 2) {
                        JSONObject resResult = resObj.optJSONObject("result");
                        if (resResult != null && resResult.has("logStreamId")) {
                            expectedStreamId = resResult.getInt("logStreamId");
                        }
                    }

                    String method = resObj.optString("method");
                    if ("LoggedData".equals(method)) {
                        JSONObject params = resObj.optJSONObject("params");
                        if (params != null) {
                            int streamId = params.optInt("logStreamId", -1);
                            if (expectedStreamId == -1 || streamId == expectedStreamId) {
                                JSONArray data = params.optJSONArray("data");
                                if (data != null) {
                                    boolean batchIsComplete = true;
                                    for (int i = 0; i < data.length(); i++) {
                                        JSONObject item = data.getJSONObject(i);
                                        if (isNewItem(item, fromTime)) {
                                            dataArr.put(item);
                                            totalItems++;
                                        }
                                        if (!item.optBoolean("complete", true)) {
                                            batchIsComplete = false;
                                        }
                                    }
                                    if (batchIsComplete) {
                                        streamFinished = true;
                                    }
                                }
                            }
                        }
                    }
                }
                System.err.println(" -> Done. Synced " + totalItems + " new items.");
            }

            System.err.println("Data sync complete. Printing output JSON...");
            System.out.println(allData.toString(2));

            pfd.close();
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static byte[] readFullPacket(InputStream in) throws IOException {
        byte[] header = new byte[16];
        int read = 0;
        while (read < 16) {
            int r = in.read(header, read, 16 - read);
            if (r == -1) throw new IOException("EOF reading packet header");
            read += r;
        }

        if (header[0] != (byte)0xbe || header[1] != (byte)0xba || header[2] != (byte)0xfe || header[3] != (byte)0xca) {
            throw new IOException("Invalid packet magic: " + bytesToHex(header, 16) + " (Expected bebafeca...)");
        }

        int len = ((header[7] & 0xFF) << 8) | (header[6] & 0xFF);
        byte[] payload = new byte[len];
        read = 0;
        while (read < len) {
            int r = in.read(payload, read, len - read);
            if (r == -1) throw new IOException("EOF reading packet payload");
            read += r;
        }

        byte[] packet = new byte[16 + len];
        System.arraycopy(header, 0, packet, 0, 16);
        System.arraycopy(payload, 0, packet, 16, len);
        return packet;
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(bytes.length, maxLen);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }

    private static boolean isNewItem(JSONObject item, String latestTime) {
        if (latestTime.equals("2000-01-01T00:00:00.000Z")) {
            return true;
        }
        
        if (item.has("periodic")) {
            JSONObject periodic = item.optJSONObject("periodic");
            if (periodic != null && periodic.has("startTime")) {
                String startTime = periodic.optString("startTime");
                return startTime.compareTo(latestTime) > 0;
            }
        }
        
        if (item.has("events")) {
            JSONArray events = item.optJSONArray("events");
            if (events != null && events.length() > 0) {
                JSONObject firstEvent = events.optJSONObject(0);
                if (firstEvent != null && firstEvent.has("time")) {
                    String eventTime = firstEvent.optString("time");
                    return eventTime.compareTo(latestTime) > 0;
                }
            }
        }
        
        return true;
    }
}

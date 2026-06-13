package com.resmed.mon.fig;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;

public class ListBt {
    private static android.os.IBinder bluetoothBinder = null;

    public static void main(String[] args) {
        try {
            System.out.println("--- Probing ServiceManager for bluetooth_manager ---");
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = smClass.getMethod("getService", String.class);
            Object binder = getServiceMethod.invoke(null, "bluetooth_manager");
            System.out.println("bluetooth_manager binder: " + binder);

            if (binder != null) {
                Class<?> ibmStubClass = Class.forName("android.bluetooth.IBluetoothManager$Stub");
                Method asInterfaceMethod = ibmStubClass.getMethod("asInterface", android.os.IBinder.class);
                Object iBtManager = asInterfaceMethod.invoke(null, binder);
                
                if (iBtManager != null) {
                    Class<?> callbackClass = Class.forName("android.bluetooth.IBluetoothManagerCallback");
                    
                    final android.os.Binder realBinder = new android.os.Binder() {
                        @Override
                        protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
                            try {
                                if (code == 4) { // onBluetoothServiceUp
                                    data.enforceInterface("android.bluetooth.IBluetoothManagerCallback");
                                    android.os.IBinder ib = data.readStrongBinder();
                                    System.out.println("onBluetoothServiceUp callback! Binder: " + ib);
                                    synchronized (ListBt.class) {
                                        bluetoothBinder = ib;
                                        ListBt.class.notifyAll();
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
                    System.out.println("Registering adapter via proxy...");
                    android.os.IBinder iBtBinder = (android.os.IBinder) registerAdapterMethod.invoke(iBtManager, new Object[]{callbackProxy});
                    
                    if (iBtBinder == null) {
                        System.out.println("Waiting for callback...");
                        synchronized (ListBt.class) {
                            if (bluetoothBinder == null) {
                                ListBt.class.wait(5000);
                            }
                            iBtBinder = bluetoothBinder;
                        }
                    }
                    
                    if (iBtBinder != null) {
                        System.out.println("Success! Got IBluetooth binder.");
                        Class<?> ibStubClass = Class.forName("android.bluetooth.IBluetooth$Stub");
                        Method asIbInterfaceMethod = ibStubClass.getMethod("asInterface", android.os.IBinder.class);
                        Object iBluetooth = asIbInterfaceMethod.invoke(null, iBtBinder);
                        
                        // Get Socket Manager
                        Method getSocketManagerMethod = iBluetooth.getClass().getMethod("getSocketManager");
                        Object iBtSocketManager = getSocketManagerMethod.invoke(iBluetooth);
                        System.out.println("IBluetoothSocketManager instance: " + iBtSocketManager);
                        
                        if (iBtSocketManager != null) {
                            Class<?> ibsmClass = Class.forName("android.bluetooth.IBluetoothSocketManager");
                            System.out.println("Methods in IBluetoothSocketManager:");
                            for (Method m : ibsmClass.getDeclaredMethods()) {
                                System.out.println("  " + m.getReturnType().getSimpleName() + " " + m.getName() + 
                                    "(" + getParamsString(m) + ")");
                            }
                        }
                    } else {
                        System.out.println("Failed to obtain IBluetooth binder.");
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String getParamsString(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }
}

package com.resmed.mon.fig;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: Main <encode|decode> [key_hex_or_null] [payload]");
            System.exit(1);
        }
        try {
            System.loadLibrary("figlib");
            String action = args[0];
            
            String keyHex = "null";
            if (args.length >= 2) {
                keyHex = args[1];
            }
            
            byte[] key = null;
            if (!keyHex.equals("null")) {
                key = hexToBytes(keyHex);
            }
            
            FigWrapper wrapper;
            if (key != null) {
                wrapper = FigWrapper.getEncryptedInstance(key);
            } else {
                wrapper = FigWrapper.getUnencryptedInstance();
            }

            // Read payload from arg if present, otherwise from stdin
            String payload = "";
            if (args.length >= 3) {
                payload = args[2];
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                payload = sb.toString().trim();
            }

            if (action.equals("encode")) {
                byte[] encoded = wrapper.encodePacket(payload);
                System.out.println(bytesToHex(encoded));
            } else if (action.equals("decode")) {
                byte[] packet = hexToBytes(payload);
                String decoded = wrapper.decodePacket(packet);
                if (decoded != null) {
                    System.out.println(decoded);
                } else {
                    System.out.println("null");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
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

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

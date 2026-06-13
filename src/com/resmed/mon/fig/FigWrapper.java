package com.resmed.mon.fig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class FigWrapper {
    private long nativeHandle; // Used by JNI
    public byte[] aesKey;
    private SecureRandom random = new SecureRandom();
    public static String lastDecoded = null;

    public static FigWrapper getUnencryptedInstance() {
        FigWrapper wrapper = new FigWrapper();
        wrapper.initialise(0, false, 0);
        return wrapper;
    }

    public static FigWrapper getEncryptedInstance(byte[] key) {
        FigWrapper wrapper = new FigWrapper();
        wrapper.aesKey = key;
        wrapper.initialise(0, true, 0);
        return wrapper;
    }

    // JNI Callbacks
    public byte[] encrypt(byte[] bArr) throws Exception {
        byte[] iv = new byte[16];
        this.random.nextBytes(iv);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(this.aesKey, "AES"), new IvParameterSpec(iv));
        output.write(cipher.doFinal(bArr));
        return output.toByteArray();
    }

    public byte[] decrypt(byte[] bArr, byte[] bArr2) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(this.aesKey, "AES"), new IvParameterSpec(bArr2));
        return cipher.doFinal(bArr);
    }

    public void callBack(String str) {
        lastDecoded = str;
    }

    public void figLogCallback(String str) {
        if (str.startsWith("E|") || str.startsWith("W|") || str.toLowerCase().contains("error") || str.toLowerCase().contains("fail")) {
            System.err.println("JNI LOG: " + str);
        }
    }

    public byte[] generateRandomData(int i10) {
        byte[] bArr = new byte[i10];
        this.random.nextBytes(bArr);
        return bArr;
    }

    public byte[] hash(byte[] bArr) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bArr);
        } catch (NoSuchAlgorithmException unused) {
            return null;
        }
    }

    public byte[] hmac(byte[] bArr, byte[] bArr2) {
        SecretKeySpec secretKeySpec = new SecretKeySpec(bArr, "HmacSHA256");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKeySpec);
            return mac.doFinal(bArr2);
        } catch (Exception e) {
            return null;
        }
    }

    public String decodePacket(byte[] bArr) {
        lastDecoded = null;
        nativeDecode(bArr);
        return lastDecoded;
    }

    public byte[] encodePacket(String str) {
        return nativeEncode(str);
    }

    public native void initialise(int i10, boolean z10, int i11);
    public native byte[] nativeDecode(byte[] bArr);
    public native byte[] nativeEncode(String str);
    public native byte[] pullTxData();
}

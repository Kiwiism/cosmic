package com.cosmic.agentclient;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

final class AgentMapleCrypto {
    private static final short MAPLE_VERSION = 83;
    private static final SecretKeySpec AES_KEY = new SecretKeySpec(new byte[]{
            0x13, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x06, 0x00, 0x00, 0x00, (byte) 0xB4, 0x00, 0x00, 0x00,
            0x1B, 0x00, 0x00, 0x00, 0x0F, 0x00, 0x00, 0x00,
            0x33, 0x00, 0x00, 0x00, 0x52, 0x00, 0x00, 0x00
    }, "AES");
    private static final byte[] FUNNY_BYTES = new byte[]{
            (byte) 0xEC, (byte) 0x3F, (byte) 0x77, (byte) 0xA4, (byte) 0x45, (byte) 0xD0, (byte) 0x71, (byte) 0xBF,
            (byte) 0xB7, (byte) 0x98, (byte) 0x20, (byte) 0xFC, (byte) 0x4B, (byte) 0xE9, (byte) 0xB3, (byte) 0xE1,
            (byte) 0x5C, (byte) 0x22, (byte) 0xF7, (byte) 0x0C, (byte) 0x44, (byte) 0x1B, (byte) 0x81, (byte) 0xBD,
            (byte) 0x63, (byte) 0x8D, (byte) 0xD4, (byte) 0xC3, (byte) 0xF2, (byte) 0x10, (byte) 0x19, (byte) 0xE0,
            (byte) 0xFB, (byte) 0xA1, (byte) 0x6E, (byte) 0x66, (byte) 0xEA, (byte) 0xAE, (byte) 0xD6, (byte) 0xCE,
            (byte) 0x06, (byte) 0x18, (byte) 0x4E, (byte) 0xEB, (byte) 0x78, (byte) 0x95, (byte) 0xDB, (byte) 0xBA,
            (byte) 0xB6, (byte) 0x42, (byte) 0x7A, (byte) 0x2A, (byte) 0x83, (byte) 0x0B, (byte) 0x54, (byte) 0x67,
            (byte) 0x6D, (byte) 0xE8, (byte) 0x65, (byte) 0xE7, (byte) 0x2F, (byte) 0x07, (byte) 0xF3, (byte) 0xAA,
            (byte) 0x27, (byte) 0x7B, (byte) 0x85, (byte) 0xB0, (byte) 0x26, (byte) 0xFD, (byte) 0x8B, (byte) 0xA9,
            (byte) 0xFA, (byte) 0xBE, (byte) 0xA8, (byte) 0xD7, (byte) 0xCB, (byte) 0xCC, (byte) 0x92, (byte) 0xDA,
            (byte) 0xF9, (byte) 0x93, (byte) 0x60, (byte) 0x2D, (byte) 0xDD, (byte) 0xD2, (byte) 0xA2, (byte) 0x9B,
            (byte) 0x39, (byte) 0x5F, (byte) 0x82, (byte) 0x21, (byte) 0x4C, (byte) 0x69, (byte) 0xF8, (byte) 0x31,
            (byte) 0x87, (byte) 0xEE, (byte) 0x8E, (byte) 0xAD, (byte) 0x8C, (byte) 0x6A, (byte) 0xBC, (byte) 0xB5,
            (byte) 0x6B, (byte) 0x59, (byte) 0x13, (byte) 0xF1, (byte) 0x04, (byte) 0x00, (byte) 0xF6, (byte) 0x5A,
            (byte) 0x35, (byte) 0x79, (byte) 0x48, (byte) 0x8F, (byte) 0x15, (byte) 0xCD, (byte) 0x97, (byte) 0x57,
            (byte) 0x12, (byte) 0x3E, (byte) 0x37, (byte) 0xFF, (byte) 0x9D, (byte) 0x4F, (byte) 0x51, (byte) 0xF5,
            (byte) 0xA3, (byte) 0x70, (byte) 0xBB, (byte) 0x14, (byte) 0x75, (byte) 0xC2, (byte) 0xB8, (byte) 0x72,
            (byte) 0xC0, (byte) 0xED, (byte) 0x7D, (byte) 0x68, (byte) 0xC9, (byte) 0x2E, (byte) 0x0D, (byte) 0x62,
            (byte) 0x46, (byte) 0x17, (byte) 0x11, (byte) 0x4D, (byte) 0x6C, (byte) 0xC4, (byte) 0x7E, (byte) 0x53,
            (byte) 0xC1, (byte) 0x25, (byte) 0xC7, (byte) 0x9A, (byte) 0x1C, (byte) 0x88, (byte) 0x58, (byte) 0x2C,
            (byte) 0x89, (byte) 0xDC, (byte) 0x02, (byte) 0x64, (byte) 0x40, (byte) 0x01, (byte) 0x5D, (byte) 0x38,
            (byte) 0xA5, (byte) 0xE2, (byte) 0xAF, (byte) 0x55, (byte) 0xD5, (byte) 0xEF, (byte) 0x1A, (byte) 0x7C,
            (byte) 0xA7, (byte) 0x5B, (byte) 0xA6, (byte) 0x6F, (byte) 0x86, (byte) 0x9F, (byte) 0x73, (byte) 0xE6,
            (byte) 0x0A, (byte) 0xDE, (byte) 0x2B, (byte) 0x99, (byte) 0x4A, (byte) 0x47, (byte) 0x9C, (byte) 0xDF,
            (byte) 0x09, (byte) 0x76, (byte) 0x9E, (byte) 0x30, (byte) 0x0E, (byte) 0xE4, (byte) 0xB2, (byte) 0x94,
            (byte) 0xA0, (byte) 0x3B, (byte) 0x34, (byte) 0x1D, (byte) 0x28, (byte) 0x0F, (byte) 0x36, (byte) 0xE3,
            (byte) 0x23, (byte) 0xB4, (byte) 0x03, (byte) 0xD8, (byte) 0x90, (byte) 0xC8, (byte) 0x3C, (byte) 0xFE,
            (byte) 0x5E, (byte) 0x32, (byte) 0x24, (byte) 0x50, (byte) 0x1F, (byte) 0x3A, (byte) 0x43, (byte) 0x8A,
            (byte) 0x96, (byte) 0x41, (byte) 0x74, (byte) 0xAC, (byte) 0x52, (byte) 0x33, (byte) 0xF0, (byte) 0xD9,
            (byte) 0x29, (byte) 0x80, (byte) 0xB1, (byte) 0x16, (byte) 0xD3, (byte) 0xAB, (byte) 0x91, (byte) 0xB9,
            (byte) 0x84, (byte) 0x7F, (byte) 0x61, (byte) 0x1E, (byte) 0xCF, (byte) 0xC5, (byte) 0xD1, (byte) 0x56,
            (byte) 0x3D, (byte) 0xCA, (byte) 0xF4, (byte) 0x05, (byte) 0xC6, (byte) 0xE5, (byte) 0x08, (byte) 0x49
    };

    private AgentMapleCrypto() {
    }

    static MapleSession fromHello(byte[] clientSendIv, byte[] clientReceiveIv) {
        return new MapleSession(
                new MapleAesOfb(clientSendIv, MAPLE_VERSION),
                new MapleAesOfb(clientReceiveIv, (short) (0xFFFF - MAPLE_VERSION))
        );
    }

    static byte[] encryptClientPacket(byte[] plain, MapleSession session) {
        byte[] body = Arrays.copyOf(plain, plain.length);
        byte[] header = session.toServer().packetHeader(body.length);
        customEncrypt(body);
        session.toServer().crypt(body);
        byte[] framed = new byte[header.length + body.length];
        System.arraycopy(header, 0, framed, 0, header.length);
        System.arraycopy(body, 0, framed, header.length, body.length);
        return framed;
    }

    static byte[] decryptServerPacket(byte[] encrypted, MapleSession session) {
        byte[] body = Arrays.copyOf(encrypted, encrypted.length);
        session.fromServer().crypt(body);
        customDecrypt(body);
        return body;
    }

    static int packetLengthFromHeader(int header) {
        int length = ((header >>> 16) ^ (header & 0xFFFF));
        return ((length << 8) & 0xFF00) | ((length >>> 8) & 0xFF);
    }

    static int readIntBE(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }

    record MapleSession(MapleAesOfb toServer, MapleAesOfb fromServer) {}

    static final class MapleAesOfb {
        private final Cipher cipher;
        private final short mapleVersion;
        private byte[] iv;

        MapleAesOfb(byte[] iv, short mapleVersion) {
            try {
                this.cipher = Cipher.getInstance("AES");
                this.cipher.init(Cipher.ENCRYPT_MODE, AES_KEY);
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to initialize Maple AES cipher", exception);
            }
            this.iv = Arrays.copyOf(iv, iv.length);
            this.mapleVersion = (short) (((mapleVersion >> 8) & 0xFF) | ((mapleVersion << 8) & 0xFF00));
        }

        synchronized byte[] crypt(byte[] data) {
            int remaining = data.length;
            int blockLength = 0x5B0;
            int start = 0;
            while (remaining > 0) {
                byte[] activeIv = multiply(iv, 4, 4);
                if (remaining < blockLength) {
                    blockLength = remaining;
                }
                for (int i = start; i < start + blockLength; i++) {
                    if ((i - start) % activeIv.length == 0) {
                        try {
                            byte[] nextIv = cipher.doFinal(activeIv);
                            System.arraycopy(nextIv, 0, activeIv, 0, activeIv.length);
                        } catch (Exception exception) {
                            throw new IllegalStateException("Unable to crypt Maple packet", exception);
                        }
                    }
                    data[i] ^= activeIv[(i - start) % activeIv.length];
                }
                start += blockLength;
                remaining -= blockLength;
                blockLength = 0x5B4;
            }
            iv = newIv(iv);
            return data;
        }

        byte[] packetHeader(int length) {
            int iiv = iv[3] & 0xFF;
            iiv |= (iv[2] << 8) & 0xFF00;
            iiv ^= mapleVersion;
            int swappedLength = ((length << 8) & 0xFF00) | (length >>> 8);
            int xoredIv = iiv ^ swappedLength;
            return new byte[]{
                    (byte) ((iiv >>> 8) & 0xFF),
                    (byte) (iiv & 0xFF),
                    (byte) ((xoredIv >>> 8) & 0xFF),
                    (byte) (xoredIv & 0xFF)
            };
        }
    }

    private static byte[] multiply(byte[] bytes, int count, int factor) {
        byte[] multiplied = new byte[count * factor];
        for (int i = 0; i < multiplied.length; i++) {
            multiplied[i] = bytes[i % count];
        }
        return multiplied;
    }

    private static byte[] newIv(byte[] oldIv) {
        byte[] next = {(byte) 0xF2, 0x53, 0x50, (byte) 0xC6};
        for (byte b : oldIv) {
            shuffleIv(b, next);
        }
        return next;
    }

    private static void shuffleIv(byte inputByte, byte[] in) {
        byte elina = in[1];
        byte anna = inputByte;
        byte moritz = FUNNY_BYTES[elina & 0xFF];
        moritz -= inputByte;
        in[0] += moritz;
        moritz = in[2];
        moritz ^= FUNNY_BYTES[anna & 0xFF];
        elina -= moritz & 0xFF;
        in[1] = elina;
        elina = in[3];
        moritz = elina;
        elina -= in[0] & 0xFF;
        moritz = FUNNY_BYTES[moritz & 0xFF];
        moritz += inputByte;
        moritz ^= in[2];
        in[2] = moritz;
        elina += FUNNY_BYTES[anna & 0xFF] & 0xFF;
        in[3] = elina;
        int value = in[0] & 0xFF;
        value |= (in[1] << 8) & 0xFF00;
        value |= (in[2] << 16) & 0xFF0000;
        value |= (in[3] << 24) & 0xFF000000;
        int shifted = value >>> 0x1D;
        value <<= 3;
        int rotated = shifted | value;
        in[0] = (byte) (rotated & 0xFF);
        in[1] = (byte) ((rotated >> 8) & 0xFF);
        in[2] = (byte) ((rotated >> 16) & 0xFF);
        in[3] = (byte) ((rotated >> 24) & 0xFF);
    }

    private static void customEncrypt(byte[] data) {
        for (int round = 0; round < 6; round++) {
            byte remember = 0;
            byte dataLength = (byte) (data.length & 0xFF);
            if (round % 2 == 0) {
                for (int i = 0; i < data.length; i++) {
                    byte current = data[i];
                    current = rollLeft(current, 3);
                    current += dataLength;
                    current ^= remember;
                    remember = current;
                    current = rollRight(current, dataLength & 0xFF);
                    current = (byte) (~current & 0xFF);
                    current += 0x48;
                    dataLength--;
                    data[i] = current;
                }
            } else {
                for (int i = data.length - 1; i >= 0; i--) {
                    byte current = data[i];
                    current = rollLeft(current, 4);
                    current += dataLength;
                    current ^= remember;
                    remember = current;
                    current ^= 0x13;
                    current = rollRight(current, 3);
                    dataLength--;
                    data[i] = current;
                }
            }
        }
    }

    private static void customDecrypt(byte[] data) {
        for (int round = 1; round <= 6; round++) {
            byte remember = 0;
            byte dataLength = (byte) (data.length & 0xFF);
            byte nextRemember;
            if (round % 2 == 0) {
                for (int i = 0; i < data.length; i++) {
                    byte current = data[i];
                    current -= 0x48;
                    current = (byte) (~current & 0xFF);
                    current = rollLeft(current, dataLength & 0xFF);
                    nextRemember = current;
                    current ^= remember;
                    remember = nextRemember;
                    current -= dataLength;
                    current = rollRight(current, 3);
                    data[i] = current;
                    dataLength--;
                }
            } else {
                for (int i = data.length - 1; i >= 0; i--) {
                    byte current = data[i];
                    current = rollLeft(current, 3);
                    current ^= 0x13;
                    nextRemember = current;
                    current ^= remember;
                    remember = nextRemember;
                    current -= dataLength;
                    current = rollRight(current, 4);
                    data[i] = current;
                    dataLength--;
                }
            }
        }
    }

    private static byte rollLeft(byte value, int count) {
        int temp = value & 0xFF;
        temp <<= count % 8;
        return (byte) ((temp & 0xFF) | (temp >> 8));
    }

    private static byte rollRight(byte value, int count) {
        int temp = value & 0xFF;
        temp = (temp << 8) >>> (count % 8);
        return (byte) ((temp & 0xFF) | (temp >>> 8));
    }
}

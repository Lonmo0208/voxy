package me.cortex.voxy.common.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class UnsafeUtil {
    private static final Object UNSAFE;
    private static final Method copyMemoryMethod;
    private static final Method arrayBaseOffsetMethod;

    static {
        try {
            Class<?> unsafeClass;
            try {
                unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            } catch (ClassNotFoundException e) {
                unsafeClass = Class.forName("sun.misc.Unsafe");
            }

            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = field.get(null);

            copyMemoryMethod = unsafeClass.getMethod("copyMemory",
                    Object.class, long.class,
                    Object.class, long.class,
                    long.class);
            arrayBaseOffsetMethod = unsafeClass.getMethod("arrayBaseOffset", Class.class);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize UnsafeUtil", e);
        }
    }

    private static long getArrayBaseOffset(Class<?> arrayClass) {
        try {
            return (long) arrayBaseOffsetMethod.invoke(UNSAFE, arrayClass);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long BYTE_ARRAY_BASE_OFFSET = getArrayBaseOffset(byte[].class);
    private static final long SHORT_ARRAY_BASE_OFFSET = getArrayBaseOffset(short[].class);

    private static void copyMemory(Object srcBase, long srcOffset,
                                   Object dstBase, long dstOffset,
                                   long length) {
        try {
            copyMemoryMethod.invoke(UNSAFE, srcBase, srcOffset, dstBase, dstOffset, length);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void memcpy(long src, long dst, long length) {
        copyMemory(null, src, null, dst, length);
    }

    public static void memcpy(long src, byte[] dst) {
        copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET, dst.length);
    }

    public static void memcpy(long src, int length, byte[] dst) {
        copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET, length);
    }

    public static void memcpy(long src, int length, byte[] dst, int offset) {
        copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET + offset, length);
    }

    public static void memcpy(byte[] src, long dst) {
        copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, src.length);
    }

    public static void memcpy(byte[] src, int len, long dst) {
        copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, len);
    }

    public static void memcpy(short[] src, long dst) {
        copyMemory(src, SHORT_ARRAY_BASE_OFFSET, null, dst, (long) src.length << 1);
    }
}
// SPDX-License-Identifier: MIT
package io.github.framecompat;

import org.objectweb.asm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

/** 独立的源文件补丁工具 / Source-only tooling; never edits an installed jar in place. */
public final class ZstdNetFramePatch {
    public static final String INPUT_SHA256 = "6a53c2eae4e922408e4f5a4715c0282c8b67bcbafbd7131d65196ee1c758785c";
    public static final String DECODER = "cn/tohsaka/factory/zstdnet/core/transport/ZstdFramedCodec$Decoder.class";
    public static final String HELPER = "cn/tohsaka/factory/zstdnet/core/transport/BoundedVanillaFrame.class";
    private static final int LIMIT = 64 * 1024 * 1024;
    private ZstdNetFramePatch() {}

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] bytes = new byte[65536];
                for (int count; (count = input.read(bytes)) >= 0;) digest.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = ZstdNetFramePatch.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing build resource: " + name);
            return input.readAllBytes();
        }
    }

    static byte[] patchDecoder(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] methods = {0}, counters = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (Set.of("rawBytes", "wireBytes").contains(name) && descriptor.equals("Ljava/util/function/LongConsumer;")) counters[0]++;
                return super.visitField(access, name, descriptor, signature, value);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("forwardVanillaFrame") || !descriptor.equals("(Lio/netty/buffer/ByteBuf;Ljava/util/List;)V")) return method;
                if ((access & Opcodes.ACC_STATIC) != 0) throw new IllegalStateException("Unexpected static decoder method");
                methods[0]++;
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ALOAD, 2);
                for (String field : List.of("rawBytes", "wireBytes")) {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitFieldInsn(Opcodes.GETFIELD, reader.getClassName(), field, "Ljava/util/function/LongConsumer;");
                }
                method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER.substring(0, HELPER.length() - 6), "forward", "(Lio/netty/buffer/ByteBuf;Ljava/util/List;Ljava/util/function/LongConsumer;Ljava/util/function/LongConsumer;)V", false);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(4, 3);
                method.visitEnd();
                return null;
            }
        }, 0);
        if (methods[0] != 1 || counters[0] != 2) throw new IllegalStateException("Unsupported decoder structure");
        return writer.toByteArray();
    }

    public static void patch(Path input, Path output) throws IOException {
        input = input.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        if (input.equals(output) || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output must be a new path");
        if (Files.size(input) > LIMIT || !sha256(input).equals(INPUT_SHA256)) throw new IOException("Unsupported upstream jar hash");
        Path parent = output.getParent();
        if (!Files.isDirectory(parent)) throw new IOException("Output directory must already exist");
        Path temporary = Files.createTempFile(parent, ".frame-compat-", ".pending");
        int decoderCount = 0, metadataCount = 0, total = 0;
        Set<String> seen = new HashSet<>();
        try {
            try (JarFile source = new JarFile(input.toFile()); JarOutputStream target = new JarOutputStream(Files.newOutputStream(temporary))) {
                for (Enumeration<JarEntry> entries = source.entries(); entries.hasMoreElements();) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName(), upper = name.toUpperCase(Locale.ROOT);
                    if (!seen.add(name) || name.startsWith("/") || Arrays.asList(name.split("/")).contains("..")) throw new IOException("Invalid jar entry");
                    if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) throw new IOException("Signed jars are not supported");
                    byte[] bytes;
                    try (InputStream stream = source.getInputStream(entry)) { bytes = stream.readNBytes(LIMIT + 1); }
                    total += bytes.length;
                    if (bytes.length > LIMIT || total > 2 * LIMIT) throw new IOException("Jar contents exceed the bounded input budget");
                    if (name.equals(DECODER)) { bytes = patchDecoder(bytes); decoderCount++; }
                    if (name.equals("META-INF/neoforge.mods.toml")) {
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        Matcher matcher = Pattern.compile("(?m)^(version\\s*=\\s*\")1\\.4\\.9(\"\\s*)$").matcher(text);
                        if (!matcher.find()) throw new IOException("Unexpected mod version metadata");
                        text = matcher.replaceAll(match -> match.group(1) + "1.4.9-framefix.2" + match.group(2));
                        bytes = text.getBytes(StandardCharsets.UTF_8); metadataCount++;
                    }
                    if (name.equals(HELPER)) throw new IOException("Already contains the helper class");
                    JarEntry copied = new JarEntry(name);
                    copied.setTime(entry.getTime());
                    if (entry.getComment() != null) copied.setComment(entry.getComment());
                    target.putNextEntry(copied); target.write(bytes); target.closeEntry();
                }
                if (decoderCount != 1 || metadataCount != 1) throw new IOException("Missing patch targets");
                target.putNextEntry(new JarEntry(HELPER)); target.write(resource(HELPER)); target.closeEntry();
                target.putNextEntry(new JarEntry("LICENSES/ZstdNet-MIT.txt")); target.write(resource("LICENSES/ZstdNet-MIT.txt")); target.closeEntry();
                target.putNextEntry(new JarEntry("ZSTDNET-FRAME-COMPAT.txt"));
                target.write("Modified by ZstdNet Frame Compat. Only the bounded vanilla-frame passthrough and version metadata are changed. Original compression, encoder and encryption classes remain unchanged. See the accompanying upstream MIT notice.\n".getBytes(StandardCharsets.UTF_8));
                target.closeEntry();
            }
            // 不覆盖现有输出 / Do not replace an existing output, including a raced creation.
            Files.move(temporary, output);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: zstdnet-frame-compat INPUT.jar NEW_OUTPUT.jar");
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        patch(input, output);
        System.out.println("Patched vanilla-frame passthrough; compression and encryption are unchanged.");
        System.out.println("Input SHA256: " + sha256(input));
        System.out.println("Output SHA256: " + sha256(output));
    }
}

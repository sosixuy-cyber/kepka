package ru.etc1337.client.modules.impl.combat.aura.ai3;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * MirageGlassPipeline — нативный Liquid Glass шейдер.
 *
 * Шейдеры лежат в:
 *   assets/dreamcore/shaders/core/mirage_glass_vertex.vsh
 *   assets/dreamcore/shaders/core/mirage_glass_fragment.fsh
 *   assets/dreamcore/pipelines/spiral/mirage_glass.json
 *
 * Использование:
 *   MirageGlassPipeline.draw(matrix, x, y, w, h, radius, blur, distortion, shine, tint, alpha, z);
 */
public class MirageGlassPipeline {

    private static final int BUFFER_SIZE = 256;
    private static final String NAMESPACE = "dreamcore"; // соответствует assets/dreamcore/...

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
                    .withLocation(Identifier.of(NAMESPACE, "spiral/mirage_glass"))
                    .withVertexShader(Identifier.of(NAMESPACE, "mirage_glass_vertex"))
                    .withFragmentShader(Identifier.of(NAMESPACE, "mirage_glass_fragment"))
                    .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
                    .withUniform("MirageGlassData", UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .build());

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f(0, 0, 0);
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private static GpuBuffer uniformBuffer;
    private static GpuBuffer dummyVertexBuffer;
    private static ByteBuffer dataBuffer;
    private static GpuTexture sourceTexture;
    private static GpuTextureView sourceTextureView;
    private static int lastWidth;
    private static int lastHeight;
    private static boolean initialized;

    public static void init() {
        if (initialized) return;

        dataBuffer = MemoryUtil.memAlloc(BUFFER_SIZE);

        ByteBuffer dummyData = MemoryUtil.memAlloc(4);
        dummyData.putInt(0);
        dummyData.flip();
        dummyVertexBuffer = RenderSystem.getDevice().createBuffer(
                () -> "dreamcore:mirage_glass_dummy_vertex",
                GpuBuffer.USAGE_VERTEX,
                dummyData);
        MemoryUtil.memFree(dummyData);

        initialized = true;
    }

    /** Главный метод отрисовки liquid glass панели */
    public static void draw(Matrix4f matrix, float x, float y, float width, float height, float radius,
                            float blur, float distortion, float shine, Color tint, float alpha, float z) {
        if (alpha <= 0.01f || width <= 0.5f || height <= 0.5f) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getFramebuffer() == null || client.getFramebuffer().getColorAttachment() == null) return;

        init();

        int fbWidth = client.getFramebuffer().textureWidth;
        int fbHeight = client.getFramebuffer().textureHeight;
        ensureTexture(fbWidth, fbHeight);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(
                client.getFramebuffer().getColorAttachment(),
                sourceTexture,
                0, 0, 0, 0, 0,
                fbWidth, fbHeight);

        // Используем напрямую Window scaled width/height (без RenderUtil)
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();

        prepareData(matrix, x, y, width, height, screenW, screenH,
                radius, blur, distortion, shine, tint, alpha, z);
        encoder.writeToBuffer(uniformBuffer.slice(), dataBuffer);

        GpuSampler sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .write(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        try (RenderPass pass = encoder.createRenderPass(
                () -> "dreamcore:mirage_glass",
                client.getFramebuffer().getColorAttachmentView(),
                OptionalInt.empty(),
                client.getFramebuffer().getDepthAttachmentView(),
                OptionalDouble.of(1.0))) {

            pass.setPipeline(PIPELINE);
            pass.setVertexBuffer(0, dummyVertexBuffer);
            pass.bindTexture("Sampler0", sourceTextureView, sampler);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setUniform("MirageGlassData", uniformBuffer);
            pass.draw(0, 6);
        }
    }

    private static void ensureTexture(int fbWidth, int fbHeight) {
        if (sourceTexture != null && fbWidth == lastWidth && fbHeight == lastHeight) return;

        if (sourceTextureView != null) { sourceTextureView.close(); sourceTextureView = null; }
        if (sourceTexture != null)     { sourceTexture.close();     sourceTexture = null;     }

        sourceTexture = RenderSystem.getDevice().createTexture(
                () -> "dreamcore:mirage_glass_source",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8,
                fbWidth, fbHeight, 1, 1);
        sourceTextureView = RenderSystem.getDevice().createTextureView(sourceTexture);
        lastWidth = fbWidth;
        lastHeight = fbHeight;
    }

    private static void prepareData(Matrix4f matrix, float x, float y, float w, float h, int screenW, int screenH,
                                    float radius, float blur, float distortion, float shine, Color tint,
                                    float alpha, float z) {
        float red = tint.getRed() / 255f;
        float green = tint.getGreen() / 255f;
        float blue = tint.getBlue() / 255f;
        float tintAlpha = tint.getAlpha() / 255f;
        float time = (System.currentTimeMillis() % 100000L) / 1000f;

        dataBuffer.clear();
        dataBuffer.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(matrix.m03());
        dataBuffer.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(matrix.m13());
        dataBuffer.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(matrix.m23());
        dataBuffer.putFloat(matrix.m30()).putFloat(matrix.m31()).putFloat(matrix.m32()).putFloat(matrix.m33());
        dataBuffer.putFloat(x).putFloat(y).putFloat(w).putFloat(h);
        dataBuffer.putFloat(screenW).putFloat(screenH).putFloat(0).putFloat(0);
        dataBuffer.putFloat(radius).putFloat(blur).putFloat(distortion).putFloat(shine);
        dataBuffer.putFloat(red).putFloat(green).putFloat(blue).putFloat(tintAlpha);
        dataBuffer.putFloat(alpha).putFloat(z).putFloat(time).putFloat(0);
        dataBuffer.flip();
        ensureBuffer();
    }

    private static void ensureBuffer() {
        int size = dataBuffer.remaining();
        if (uniformBuffer == null || uniformBuffer.size() < size) {
            if (uniformBuffer != null) uniformBuffer.close();
            uniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "dreamcore:mirage_glass_uniform",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    size);
        }
    }

    public static void shutdown() {
        if (uniformBuffer != null) { uniformBuffer.close(); uniformBuffer = null; }
        if (dummyVertexBuffer != null) { dummyVertexBuffer.close(); dummyVertexBuffer = null; }
        if (dataBuffer != null) { MemoryUtil.memFree(dataBuffer); dataBuffer = null; }
        if (sourceTextureView != null) { sourceTextureView.close(); sourceTextureView = null; }
        if (sourceTexture != null) { sourceTexture.close(); sourceTexture = null; }
        initialized = false;
        lastWidth = 0;
        lastHeight = 0;
    }
}
